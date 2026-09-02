#!/usr/bin/env python3
import argparse, os, sys
sys.path.insert(0, os.path.dirname(__file__))
from autocrack_runtime_client import *

def parser():
    p=argparse.ArgumentParser(prog="runtime-inspect", description="Inspect process, Activity, ClassLoader, class and object state through AutoCrack Runtime")
    p.add_argument("--version", action="version", version=f"runtime-inspect {VERSION}")
    sub=p.add_subparsers(dest="command", required=True)
    sub.add_parser("status")
    cap=sub.add_parser("capabilities"); add_target(cap)
    proc=sub.add_parser("process"); add_target(proc)
    acts=sub.add_parser("activities"); add_target(acts)
    decl=sub.add_parser("declared-activities"); add_target(decl)
    cls=sub.add_parser("classloaders"); add_target(cls)
    search=sub.add_parser("class-search"); add_target(search); search.add_argument("query"); search.add_argument("--mode", choices=["substring","regex","exact"], default="substring"); search.add_argument("--loader"); search.add_argument("--max-classes", type=int, default=1000)
    desc=sub.add_parser("class-describe"); add_target(desc); desc.add_argument("class_name"); desc.add_argument("--loader"); desc.add_argument("--no-load", action="store_true"); desc.add_argument("--max-members", type=int, default=512)
    for name in ("object","object-fields","object-dump"):
        q=sub.add_parser(name); add_target(q); q.add_argument("handle")
    pin=sub.add_parser("object-pin"); add_target(pin); pin.add_argument("handle"); pin.add_argument("--unpin", action="store_true")
    rel=sub.add_parser("object-release"); add_target(rel); rel.add_argument("handle")
    clear=sub.add_parser("object-clear-session"); add_target(clear); clear.add_argument("session")
    return p

def execute(a):
    if a.command=="status": return provider_call("runtime_status")
    if a.command=="capabilities": return runtime_request(target_payload(a,"runtime.capabilities"), a.timeout)
    if a.command=="process": return runtime_request(target_payload(a,"runtime.process"), a.timeout)
    if a.command=="activities": return runtime_request(target_payload(a,"runtime.activities"), a.timeout)
    if a.command=="declared-activities": return runtime_request(target_payload(a,"runtime.declared_activities"), a.timeout)
    if a.command=="classloaders": return runtime_request(target_payload(a,"runtime.classloaders"), a.timeout)
    if a.command=="class-search": return runtime_request(target_payload(a,"runtime.class.search", query=a.query, mode=a.mode, loader=a.loader, max_classes=a.max_classes), a.timeout)
    if a.command=="class-describe": return runtime_request(target_payload(a,"runtime.class.describe", **{"class":a.class_name}, loader=a.loader, allow_load=not a.no_load, max_members=a.max_members), a.timeout)
    if a.command=="object": return runtime_request(target_payload(a,"object.describe", handle=a.handle), a.timeout)
    if a.command=="object-fields": return runtime_request(target_payload(a,"object.fields", handle=a.handle), a.timeout)
    if a.command=="object-dump": return runtime_request(target_payload(a,"object.dump", handle=a.handle), a.timeout)
    if a.command=="object-pin": return runtime_request(target_payload(a,"object.pin", handle=a.handle, pin=not a.unpin), a.timeout)
    if a.command=="object-release": return runtime_request(target_payload(a,"object.release", handle=a.handle), a.timeout)
    if a.command=="object-clear-session": return runtime_request(target_payload(a,"object.clear_session", session=a.session), a.timeout)
    raise CliError("INVALID_COMMAND", a.command)

def main(argv=None):
    raw=list(sys.argv[1:] if argv is None else argv); json_output="--json" in raw; raw=[x for x in raw if x!="--json"]
    try: emit_result(execute(parser().parse_args(raw)), json_output); return 0
    except CliError as e: emit_error(e, json_output); return 1
if __name__=="__main__": raise SystemExit(main())
