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
    wvlu=sub.add_parser("webview-load-url"); add_target(wvlu); wvlu.add_argument("handle"); wvlu.add_argument("url")
    wvrl=sub.add_parser("webview-reload"); add_target(wvrl); wvrl.add_argument("handle")
    wvgb=sub.add_parser("webview-go-back"); add_target(wvgb); wvgb.add_argument("handle")
    wvgf=sub.add_parser("webview-go-forward"); add_target(wvgf); wvgf.add_argument("handle")
    wvcc=sub.add_parser("webview-clear-cache"); add_target(wvcc); wvcc.add_argument("--handle", default=""); wvcc.add_argument("--include-disk", action="store_true")
    ss=sub.add_parser("secure-status"); add_target(ss)
    sdiag=sub.add_parser("secure-diagnose"); add_target(sdiag)
    sd=sub.add_parser("secure-disable"); add_target(sd)
    so=sub.add_parser("so-inject"); add_target(so); so.add_argument("path")
    dl=sub.add_parser("so-dlopen"); add_target(dl); dl.add_argument("path"); dl.add_argument("--flags", type=int, default=2)
    adl=sub.add_parser("so-android-dlopen-ext"); add_target(adl); adl.add_argument("path"); adl.add_argument("--flags", type=int, default=2); adl.add_argument("--ext-flags", type=lambda x:int(x,0), default=0)
    ds=sub.add_parser("so-dlsym"); add_target(ds); ds.add_argument("symbol"); ds.add_argument("--handle", default="")
    act=sub.add_parser("activity-start"); add_target(act); act.add_argument("--component"); act.add_argument("--class-name"); act.add_argument("--action"); act.add_argument("--data"); act.add_argument("--flags", type=int)
    kill=sub.add_parser("process-kill"); add_target(kill); kill.add_argument("--delay-ms", type=int, default=350)
    fs=sub.add_parser("object-field-set"); add_target(fs); fs.add_argument("handle"); fs.add_argument("field"); fs.add_argument("--declaring-class", default=""); fs.add_argument("--value-json", required=True)
    mc=sub.add_parser("object-method-call"); add_target(mc); mc.add_argument("handle"); mc.add_argument("method"); mc.add_argument("--declaring-class", default=""); mc.add_argument("--arg-types-json", default="[]"); mc.add_argument("--args-json", default="[]")
    return p

def execute(a):
    if a.command=="status": return provider_call("runtime_status")
    if a.command=="webview-list": return runtime_request(target_payload(a,"webview.list"), a.timeout)
    if a.command=="webview-info": return runtime_request(target_payload(a,"webview.info", handle=a.handle), a.timeout)
    if a.command=="webview-debug": return runtime_request(target_payload(a,"webview.debug", enabled=not a.disable), a.timeout)
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
