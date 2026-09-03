#!/usr/bin/env python3
import argparse, json, os, sys
sys.path.insert(0, os.path.dirname(__file__))
from autocrack_runtime_client import *

def parser():
    p=argparse.ArgumentParser(prog="ui-inspect", description="Inspect and mutate live Android View roots through the shared AutoCrack Runtime")
    p.add_argument("--version", action="version", version=f"ui-inspect {VERSION}")
    sub=p.add_subparsers(dest="command", required=True)
    sub.add_parser("status")
    clear=sub.add_parser("clear"); clear.add_argument("--package")
    cap=sub.add_parser("capabilities"); add_target(cap)
    w=sub.add_parser("windows"); add_target(w); w.add_argument("--max-roots", type=int, default=64)
    t=sub.add_parser("tree"); add_target(t); t.add_argument("--max-nodes", type=int, default=4000); t.add_argument("--listeners", action="store_true")
    a=sub.add_parser("at"); add_target(a); a.add_argument("x", type=int); a.add_argument("y", type=int); a.add_argument("--max-nodes", type=int, default=4000); a.add_argument("--listeners", action="store_true"); a.add_argument("--include-hidden", action="store_true")
    f=sub.add_parser("find"); add_target(f); f.add_argument("--text", default=""); f.add_argument("--resource", default=""); f.add_argument("--class-name", default=""); f.add_argument("--max-nodes", type=int, default=512); f.add_argument("--include-hidden", action="store_true")
    for name in ("props","parent","siblings","listeners","stack"):
        q=sub.add_parser(name); add_target(q); q.add_argument("handle")
    ch=sub.add_parser("children"); add_target(ch); ch.add_argument("handle"); ch.add_argument("--max-children", type=int, default=256)
    img=sub.add_parser("image"); add_target(img); img.add_argument("handle")
    ir=sub.add_parser("image-result"); add_target(ir); ir.add_argument("token")
    act=sub.add_parser("action"); add_target(act); act.add_argument("handle"); act.add_argument("--action-json", required=True)
    comp=sub.add_parser("compose-tree"); add_target(comp); comp.add_argument("--max-nodes", type=int, default=512); comp.add_argument("--merged", action="store_true")
    return p

def execute(args):
    if args.command=="status": return provider_call("runtime_status")
    if args.command=="clear": return provider_call("runtime_clear", {"package": args.package} if args.package else {})
    if args.command=="capabilities": return runtime_request(target_payload(args,"runtime.capabilities"), args.timeout)
    if args.command=="windows": return runtime_request(target_payload(args,"ui.windows", max_roots=args.max_roots), args.timeout)
    if args.command=="tree": return runtime_request(target_payload(args,"ui.tree", max_nodes=args.max_nodes, include_listeners=args.listeners), args.timeout)
    if args.command=="at": return runtime_request(target_payload(args,"ui.at", x=args.x, y=args.y, max_nodes=args.max_nodes, include_listeners=args.listeners, include_hidden=args.include_hidden), args.timeout)
    if args.command=="find": return runtime_request(target_payload(args,"ui.find", text=args.text, resource=args.resource, class_name=args.class_name, max_nodes=args.max_nodes, include_hidden=args.include_hidden), args.timeout)
    if args.command=="props": return runtime_request(target_payload(args,"ui.props", handle=args.handle), args.timeout)
    if args.command=="parent": return runtime_request(target_payload(args,"ui.parent", handle=args.handle), args.timeout)
    if args.command=="children": return runtime_request(target_payload(args,"ui.children", handle=args.handle, max_children=args.max_children), args.timeout)
    if args.command=="siblings": return runtime_request(target_payload(args,"ui.siblings", handle=args.handle), args.timeout)
    if args.command=="listeners": return runtime_request(target_payload(args,"ui.listeners", handle=args.handle), args.timeout)
    if args.command=="stack": return runtime_request(target_payload(args,"ui.stack", handle=args.handle), args.timeout)
    if args.command=="image": return runtime_request(target_payload(args,"ui.image", handle=args.handle), args.timeout)
    if args.command=="image-result": return runtime_request(target_payload(args,"ui.image.result", token=args.token), args.timeout)
    if args.command=="action": return runtime_request(target_payload(args,"ui.action", handle=args.handle, action=parse_json_arg(args.action_json,"INVALID_ACTION_JSON")), args.timeout)
    if args.command=="compose-tree": return runtime_request(target_payload(args,"ui.compose.tree", max_nodes=args.max_nodes, unmerged=not args.merged), args.timeout)
    raise CliError("INVALID_COMMAND", args.command)

def main(argv=None):
    raw=list(sys.argv[1:] if argv is None else argv); json_output="--json" in raw; raw=[x for x in raw if x!="--json"]
    try: emit_result(execute(parser().parse_args(raw)), json_output); return 0
    except CliError as e: emit_error(e, json_output); return 1
if __name__=="__main__": raise SystemExit(main())
