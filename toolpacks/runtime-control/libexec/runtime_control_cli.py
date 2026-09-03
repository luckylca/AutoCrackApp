#!/usr/bin/env python3
import argparse, os, subprocess, sys
sys.path.insert(0, os.path.dirname(__file__))
from autocrack_runtime_client import *

def parser():
    p=argparse.ArgumentParser(prog="runtime-control", description="Perform explicit runtime mutations through AutoCrack Runtime")
    p.add_argument("--version", action="version", version=f"runtime-control {VERSION}")
    sub=p.add_subparsers(dest="command", required=True)
    sub.add_parser("status")
    wvl=sub.add_parser("webview-list"); add_target(wvl)
    wvi=sub.add_parser("webview-info"); add_target(wvi); wvi.add_argument("--handle", default="")
    wvd=sub.add_parser("webview-debug"); add_target(wvd); wvd.add_argument("--disable", action="store_true")
    wvds=sub.add_parser("webview-devtools-sockets"); add_target(wvds)
    wve=sub.add_parser("webview-eval"); add_target(wve); wve.add_argument("handle"); wve.add_argument("script")
    wvr=sub.add_parser("webview-eval-result"); add_target(wvr); wvr.add_argument("token")
    wvlu=sub.add_parser("webview-load-url"); add_target(wvlu); wvlu.add_argument("handle"); wvlu.add_argument("url")
    wvrl=sub.add_parser("webview-reload"); add_target(wvrl); wvrl.add_argument("handle")
    wvgb=sub.add_parser("webview-go-back"); add_target(wvgb); wvgb.add_argument("handle")
    wvgf=sub.add_parser("webview-go-forward"); add_target(wvgf); wvgf.add_argument("handle")
    wvcc=sub.add_parser("webview-clear-cache"); add_target(wvcc); wvcc.add_argument("--handle", default=""); wvcc.add_argument("--include-disk", action="store_true")
    ss=sub.add_parser("secure-status"); add_target(ss)
    sdiag=sub.add_parser("secure-diagnose"); add_target(sdiag)
    sd=sub.add_parser("secure-disable"); add_target(sd)
    so=sub.add_parser("so-inject"); add_target(so); so.add_argument("path")
    sodiag=sub.add_parser("so-diagnose"); add_target(sodiag)
    dl=sub.add_parser("so-dlopen"); add_target(dl); dl.add_argument("path"); dl.add_argument("--flags", type=int, default=2)
    adl=sub.add_parser("so-android-dlopen-ext"); add_target(adl); adl.add_argument("path"); adl.add_argument("--flags", type=int, default=2); adl.add_argument("--ext-flags", type=lambda x:int(x,0), default=0)
    ds=sub.add_parser("so-dlsym"); add_target(ds); ds.add_argument("symbol"); ds.add_argument("--handle", default="")
    act=sub.add_parser("activity-start"); add_target(act); act.add_argument("--component"); act.add_argument("--class-name"); act.add_argument("--action"); act.add_argument("--data"); act.add_argument("--flags", type=int)
    kill=sub.add_parser("process-kill"); add_target(kill); kill.add_argument("--delay-ms", type=int, default=350)
    fs=sub.add_parser("object-field-set"); add_target(fs); fs.add_argument("handle"); fs.add_argument("field"); fs.add_argument("--declaring-class", default=""); fs.add_argument("--value-json", required=True)
    mc=sub.add_parser("object-method-call"); add_target(mc); mc.add_argument("handle"); mc.add_argument("method"); mc.add_argument("--declaring-class", default=""); mc.add_argument("--arg-types-json", default="[]"); mc.add_argument("--args-json", default="[]")
    return p

def _android_shell_run(args, timeout=5.0):
    cmd=android_shell()+list(args)
    return subprocess.run(cmd, text=True, capture_output=True, timeout=timeout)

def _target_pids(package, process=None):
    name=(process or package or "").strip()
    if not name:
        return []
    try:
        completed=_android_shell_run(["pidof", name], timeout=3.0)
    except Exception:
        return []
    if completed.returncode!=0:
        return []
    out=[]
    for token in completed.stdout.split():
        if token.isdigit(): out.append(token)
    return out

def _parse_devtools_sockets(text, package, pids, max_sockets=128):
    sockets=[]
    seen=set()
    for raw in text.splitlines()[1:]:
        if "devtools_remote" not in raw:
            continue
        parts=raw.split()
        if len(parts)<7:
            continue
        path=parts[7] if len(parts)>=8 else ""
        if "devtools_remote" not in path:
            continue
        name=path[1:] if path.startswith("@") else path
        target_match=(package and package in name) or any(pid in name for pid in pids)
        if not target_match or name in seen:
            continue
        seen.add(name)
        sockets.append({
            "name":name,
            "raw_path":path,
            "abstract":path.startswith("@"),
            "inode":parts[6],
            "type":parts[4] if len(parts)>4 else None,
            "state":parts[5] if len(parts)>5 else None,
            "matched_pids":[pid for pid in pids if pid in name],
            "package_match":bool(package and package in name),
            "forward_target":"localabstract:"+name,
        })
        if len(sockets)>=max_sockets:
            break
    return sockets

def webview_devtools_sockets(a):
    package=(a.package or "").strip()
    pids=_target_pids(package, a.process)
    try:
        completed=_android_shell_run(["cat", "/proc/net/unix"], timeout=max(3.0, float(a.timeout)))
        if completed.returncode==0:
            sockets=_parse_devtools_sockets(completed.stdout, package, pids)
            return {
                "ok":True,
                "source":"rootfs:/proc/net/unix",
                "package":package,
                "process":a.process,
                "target_pids":pids,
                "socket_count":len(sockets),
                "sockets":sockets,
                "host_bridge_required":True,
                "forward_template":"adb forward tcp:<port> localabstract:<socket-name>",
                "note":"Rootfs discovery is read-only and returns only sockets matching the requested package/process. It does not create a forward or connect to CDP.",
            }
        shell_error=(completed.stderr or completed.stdout).strip() or f"exit {completed.returncode}"
    except Exception as exc:
        shell_error=str(exc)
    try:
        runtime=runtime_request(target_payload(a,"webview.devtools_socket"), a.timeout)
        runtime["source"]="target-runtime-fallback"
        runtime["rootfs_error"]=shell_error
        runtime["target_pids"]=pids
        return runtime
    except CliError as exc:
        raise CliError("DEVTOOLS_SOCKET_UNAVAILABLE", f"rootfs scan failed: {shell_error}; runtime fallback failed: {exc}") from exc

def execute(a):
    if a.command=="status": return provider_call("runtime_status")
    if a.command=="webview-list": return runtime_request(target_payload(a,"webview.list"), a.timeout)
    if a.command=="webview-info": return runtime_request(target_payload(a,"webview.info", handle=a.handle), a.timeout)
    if a.command=="webview-debug": return runtime_request(target_payload(a,"webview.debug", enabled=not a.disable), a.timeout)
    if a.command=="webview-devtools-sockets": return webview_devtools_sockets(a)
    if a.command=="webview-eval": return runtime_request(target_payload(a,"webview.eval", handle=a.handle, script=a.script), a.timeout)
    if a.command=="webview-eval-result": return runtime_request(target_payload(a,"webview.eval.result", token=a.token), a.timeout)
    if a.command=="webview-load-url": return runtime_request(target_payload(a,"webview.load_url", handle=a.handle, url=a.url), a.timeout)
    if a.command=="webview-reload": return runtime_request(target_payload(a,"webview.reload", handle=a.handle), a.timeout)
    if a.command=="webview-go-back": return runtime_request(target_payload(a,"webview.go_back", handle=a.handle), a.timeout)
    if a.command=="webview-go-forward": return runtime_request(target_payload(a,"webview.go_forward", handle=a.handle), a.timeout)
    if a.command=="webview-clear-cache": return runtime_request(target_payload(a,"webview.clear_cache", handle=a.handle, include_disk_files=a.include_disk), a.timeout)
    if a.command=="secure-status": return runtime_request(target_payload(a,"control.secure.status"), a.timeout)
    if a.command=="secure-diagnose": return runtime_request(target_payload(a,"control.secure.diagnose"), a.timeout)
    if a.command=="secure-disable": return runtime_request(target_payload(a,"control.secure.disable"), a.timeout)
    if a.command=="so-inject": return runtime_request(target_payload(a,"control.so.inject", path=a.path), a.timeout)
    if a.command=="so-diagnose": return runtime_request(target_payload(a,"control.so.diagnose"), a.timeout)
    if a.command=="so-dlopen": return runtime_request(target_payload(a,"control.so.dlopen", path=a.path, flags=a.flags), a.timeout)
    if a.command=="so-android-dlopen-ext": return runtime_request(target_payload(a,"control.so.android_dlopen_ext", path=a.path, flags=a.flags, ext_flags=a.ext_flags), a.timeout)
    if a.command=="so-dlsym": return runtime_request(target_payload(a,"control.so.dlsym", symbol=a.symbol, handle=a.handle), a.timeout)
    if a.command=="activity-start": return runtime_request(target_payload(a,"control.activity.start", component=a.component, **{"class":a.class_name}, action=a.action, data=a.data, flags=a.flags), a.timeout)
    if a.command=="process-kill": return runtime_request(target_payload(a,"control.process.kill", delay_ms=a.delay_ms), a.timeout)
    if a.command=="object-field-set": return runtime_request(target_payload(a,"control.object.field.set", handle=a.handle, field=a.field, declaring_class=a.declaring_class, value=parse_json_arg(a.value_json,"INVALID_VALUE_JSON")), a.timeout)
    if a.command=="object-method-call": return runtime_request(target_payload(a,"control.object.method.call", handle=a.handle, method=a.method, declaring_class=a.declaring_class, arg_types=parse_json_arg(a.arg_types_json,"INVALID_ARG_TYPES_JSON"), args=parse_json_arg(a.args_json,"INVALID_ARGS_JSON")), a.timeout)
    raise CliError("INVALID_COMMAND", a.command)

def main(argv=None):
    raw=list(sys.argv[1:] if argv is None else argv); json_output="--json" in raw; raw=[x for x in raw if x!="--json"]
    try: emit_result(execute(parser().parse_args(raw)), json_output); return 0
    except CliError as e: emit_error(e, json_output); return 1
if __name__=="__main__": raise SystemExit(main())
