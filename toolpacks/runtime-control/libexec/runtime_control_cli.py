#!/usr/bin/env python3
import argparse, os, sys
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
    wve=sub.add_parser("webview-eval"); add_target(wve); wve.add_argument("handle"); wve.add_argument("script")
    wvr=sub.add_parser("webview-eval-result"); add_target(wvr); wvr.add_argument("token")
    ss=sub.add_parser("secure-status"); add_target(ss)
    sd=sub.add_parser("secure-disable"); add_target(sd)
    so=sub.add_parser("so-inject"); add_target(so); so.add_argument("path")
    act=sub.add_parser("activity-start"); add_target(act); act.add_argument("--component"); act.add_argument("--class-name"); act.add_argument("--action"); act.add_argument("--data"); act.add_argument("--flags", type=int)
    kill=sub.add_parser("process-kill"); add_target(kill); kill.add_argument("--delay-ms", type=int, default=350)
    return p

def execute(a):
    if a.command=="status": return provider_call("runtime_status")
    if a.command=="webview-list": return runtime_request(target_payload(a,"webview.list"), a.timeout)
    if a.command=="webview-info": return runtime_request(target_payload(a,"webview.info", handle=a.handle), a.timeout)
    if a.command=="webview-debug": return runtime_request(target_payload(a,"webview.debug", enabled=not a.disable), a.timeout)
    if a.command=="webview-eval": return runtime_request(target_payload(a,"webview.eval", handle=a.handle, script=a.script), a.timeout)
    if a.command=="webview-eval-result": return runtime_request(target_payload(a,"webview.eval.result", token=a.token), a.timeout)
    if a.command=="secure-status": return runtime_request(target_payload(a,"control.secure.status"), a.timeout)
    if a.command=="secure-disable": return runtime_request(target_payload(a,"control.secure.disable"), a.timeout)
    if a.command=="so-inject": return runtime_request(target_payload(a,"control.so.inject", path=a.path), a.timeout)
    if a.command=="activity-start": return runtime_request(target_payload(a,"control.activity.start", component=a.component, **{"class":a.class_name}, action=a.action, data=a.data, flags=a.flags), a.timeout)
    if a.command=="process-kill": return runtime_request(target_payload(a,"control.process.kill", delay_ms=a.delay_ms), a.timeout)
    raise CliError("INVALID_COMMAND", a.command)

def main(argv=None):
    raw=list(sys.argv[1:] if argv is None else argv); json_output="--json" in raw; raw=[x for x in raw if x!="--json"]
    try: emit_result(execute(parser().parse_args(raw)), json_output); return 0
    except CliError as e: emit_error(e, json_output); return 1
if __name__=="__main__": raise SystemExit(main())
