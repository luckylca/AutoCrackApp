#!/usr/bin/env python3
import argparse
import json
import pathlib
import re
import sys
import time

import frida

ENDPOINT = "127.0.0.1:27042"
MAX_TEXT = 512
MODULE_RE = re.compile(r"^[A-Za-z0-9._+@-]{1,256}$")
CLASS_RE = re.compile(r"^[A-Za-z0-9_.$\[\]/-]{1,512}$")


def emit(value):
    print(json.dumps(value, ensure_ascii=False, separators=(",", ":")))


def fail(message, code=2):
    emit({"ok": False, "error": str(message)[:2048]})
    raise SystemExit(code)


def bounded_int(text, minimum, maximum, label):
    try:
        value = int(text)
    except (TypeError, ValueError):
        fail(f"{label} must be an integer")
    if not minimum <= value <= maximum:
        fail(f"{label} must be in {minimum}..{maximum}")
    return value


def parse_offset(text):
    raw = str(text).strip().lower()
    if not re.fullmatch(r"0x[0-9a-f]{1,16}", raw):
        fail("offset must be 0x-prefixed hexadecimal")
    value = int(raw, 16)
    if value < 0:
        fail("offset must not be negative")
    return hex(value)


def require_module(text):
    value = str(text).strip()
    if not MODULE_RE.fullmatch(value):
        fail("module must be a bounded module basename")
    return value


def require_class(text):
    value = str(text).strip()
    if not CLASS_RE.fullmatch(value):
        fail("class name contains unsupported characters")
    return value


def agent_source():
    path = pathlib.Path(__file__).resolve().parent.parent / "libexec" / "autocrack-frida-agent.js"
    return path.read_text(encoding="utf-8")


def connect(pid):
    if pid <= 0:
        fail("pid must be positive")
    manager = frida.get_device_manager()
    device = manager.add_remote_device(ENDPOINT)
    session = device.attach(pid)
    script = session.create_script(agent_source(), name="autocrack-bounded-agent")
    script.load()
    return session, script


def invoke(args):
    session = None
    script = None
    try:
        session, script = connect(args.pid)
        rpc = script.exports_sync
        if args.command == "ping":
            result = rpc.ping()
        elif args.command == "modules":
            result = rpc.modules(args.max_count)
        elif args.command == "exports":
            result = rpc.exports(require_module(args.module), args.query[:MAX_TEXT], args.max_count)
        elif args.command == "java-classes":
            result = rpc.javaclasses(args.query[:MAX_TEXT], args.max_count)
        elif args.command == "java-methods":
            result = rpc.javamethods(require_class(args.class_name), args.max_count)
        elif args.command == "net-stack":
            result = rpc.netstack(args.max_count)
        elif args.command == "tls-trace":
            start = rpc.tlstracestart(args.max_events, args.max_bytes_per_event)
            time.sleep(args.duration_ms / 1000.0)
            result = {"start": start, "trace": rpc.tlstracestop()}
        elif args.command == "native-trace":
            rpc.tracestart(require_module(args.module), parse_offset(args.offset), args.max_events)
            time.sleep(args.duration_ms / 1000.0)
            result = rpc.tracestop()
        else:
            fail("unsupported command")
        emit({"ok": True, "pid": args.pid, "command": args.command, "result": result})
    except frida.ProcessNotFoundError:
        fail("target process was not found", 3)
    except frida.ServerNotRunningError:
        fail("trusted Frida server is not running", 4)
    except frida.PermissionDeniedError:
        fail("Frida attach was denied", 5)
    except Exception as exc:
        fail(f"{type(exc).__name__}: {exc}", 6)
    finally:
        if script is not None:
            try:
                script.unload()
            except Exception:
                pass
        if session is not None:
            try:
                session.detach()
            except Exception:
                pass


def build_parser():
    parser = argparse.ArgumentParser(prog="frida-autocrack-client")
    parser.add_argument("--pid", required=True, type=int)
    sub = parser.add_subparsers(dest="command", required=True)

    sub.add_parser("ping")

    modules = sub.add_parser("modules")
    modules.add_argument("--max-count", type=lambda v: bounded_int(v, 1, 512, "max-count"), default=128)

    exports = sub.add_parser("exports")
    exports.add_argument("--module", required=True)
    exports.add_argument("--query", default="")
    exports.add_argument("--max-count", type=lambda v: bounded_int(v, 1, 512, "max-count"), default=128)

    classes = sub.add_parser("java-classes")
    classes.add_argument("--query", default="")
    classes.add_argument("--max-count", type=lambda v: bounded_int(v, 1, 512, "max-count"), default=128)

    methods = sub.add_parser("java-methods")
    methods.add_argument("--class-name", required=True)
    methods.add_argument("--max-count", type=lambda v: bounded_int(v, 1, 512, "max-count"), default=128)

    net_stack = sub.add_parser("net-stack")
    net_stack.add_argument("--max-count", type=lambda v: bounded_int(v, 1, 128, "max-count"), default=64)

    tls_trace = sub.add_parser("tls-trace")
    tls_trace.add_argument("--duration-ms", type=lambda v: bounded_int(v, 50, 5000, "duration-ms"), default=1000)
    tls_trace.add_argument("--max-events", type=lambda v: bounded_int(v, 1, 128, "max-events"), default=64)
    tls_trace.add_argument("--max-bytes-per-event", type=lambda v: bounded_int(v, 16, 1024, "max-bytes-per-event"), default=256)

    trace = sub.add_parser("native-trace")
    trace.add_argument("--module", required=True)
    trace.add_argument("--offset", required=True)
    trace.add_argument("--duration-ms", type=lambda v: bounded_int(v, 50, 5000, "duration-ms"), default=1000)
    trace.add_argument("--max-events", type=lambda v: bounded_int(v, 1, 128, "max-events"), default=64)
    return parser


if __name__ == "__main__":
    invoke(build_parser().parse_args())
