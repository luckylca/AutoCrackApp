#!/usr/bin/env python3
import argparse, os, sys
sys.path.insert(0, os.path.dirname(__file__))
from autocrack_runtime_client import *

def parser():
    p=argparse.ArgumentParser(prog="memory-dump", description="Dump bounded maps, modules, Dex, assets and XML through AutoCrack Runtime")
    p.add_argument("--version", action="version", version=f"memory-dump {VERSION}")
    sub=p.add_subparsers(dest="command", required=True)
    cap=sub.add_parser("capabilities"); add_target(cap)
    maps=sub.add_parser("maps"); add_target(maps); maps.add_argument("--max-maps", type=int, default=4096); maps.add_argument("--path-contains", default=""); maps.add_argument("--permissions-contains", default="")
    mods=sub.add_parser("modules"); add_target(mods); mods.add_argument("--filter", default=""); mods.add_argument("--max-modules", type=int, default=1024)
    nmods=sub.add_parser("native-modules"); add_target(nmods); nmods.add_argument("--filter", default=""); nmods.add_argument("--max-modules", type=int, default=1024)
    rd=sub.add_parser("read"); add_target(rd); rd.add_argument("address"); rd.add_argument("size", type=int)
    np=sub.add_parser("native-probe"); add_target(np)
    da=sub.add_parser("dladdr"); add_target(da); da.add_argument("address")
    md=sub.add_parser("module-dump"); add_target(md); md.add_argument("path"); md.add_argument("--max-bytes", type=int, default=4194304)
    mfd=sub.add_parser("module-file-dump"); add_target(mfd); mfd.add_argument("path"); mfd.add_argument("--max-bytes", type=int, default=4194304)
    dl=sub.add_parser("dex-list"); add_target(dl); dl.add_argument("--loader", default="", help="optional ClassLoader handle returned by runtime-inspect classloaders"); dl.add_argument("--class-count", action="store_true", help="count dex class entries up to the runtime safety cap")
    dd=sub.add_parser("dex-dump"); add_target(dd); dd.add_argument("dex_handle"); dd.add_argument("--max-bytes", type=int, default=4194304)
    al=sub.add_parser("assets-list"); add_target(al); al.add_argument("path", nargs="?", default=""); al.add_argument("--max-assets", type=int, default=20000)
    ap=sub.add_parser("assets-pull"); add_target(ap); ap.add_argument("path"); ap.add_argument("--max-bytes", type=int, default=4194304)
    xp=sub.add_parser("xml-pull"); add_target(xp); xp.add_argument("resource_id", type=int)
    xb=sub.add_parser("xml-binary"); add_target(xb); xb.add_argument("--resource-id", type=lambda x:int(x,0), default=0); xb.add_argument("--entry", default=""); xb.add_argument("--apk-package", default=""); xb.add_argument("--apk-path", default=""); xb.add_argument("--max-bytes", type=int, default=4194304)
    ae=sub.add_parser("apk-entries"); add_target(ae); ae.add_argument("--prefix", default=""); ae.add_argument("--apk-package", default=""); ae.add_argument("--apk-path", default=""); ae.add_argument("--max-entries", type=int, default=5000)
    apkp=sub.add_parser("apk-pull"); add_target(apkp); apkp.add_argument("entry"); apkp.add_argument("--source", default="base"); apkp.add_argument("--apk-package", default=""); apkp.add_argument("--apk-path", default=""); apkp.add_argument("--max-bytes", type=int, default=4194304)
    return p

def execute(a):
    if a.command=="capabilities": return runtime_request(target_payload(a,"memory.capabilities"), a.timeout)
    if a.command=="maps": return runtime_request(target_payload(a,"memory.maps", max_maps=a.max_maps, path_contains=a.path_contains, permissions_contains=a.permissions_contains), a.timeout)
    if a.command=="modules": return runtime_request(target_payload(a,"memory.modules", filter=a.filter, max_modules=a.max_modules), a.timeout)
    if a.command=="native-modules": return runtime_request(target_payload(a,"memory.native.modules", filter=a.filter, max_modules=a.max_modules), a.timeout)
    if a.command=="read": return runtime_request(target_payload(a,"memory.read", address=a.address, size=a.size), a.timeout)
    if a.command=="native-probe": return runtime_request(target_payload(a,"memory.native.probe"), a.timeout)
    if a.command=="dladdr": return runtime_request(target_payload(a,"memory.dladdr", address=a.address), a.timeout)
    if a.command=="module-dump": return runtime_request(target_payload(a,"memory.module.dump", path=a.path, max_bytes=a.max_bytes), a.timeout)
    if a.command=="module-file-dump": return runtime_request(target_payload(a,"memory.module.file_dump", path=a.path, max_bytes=a.max_bytes), a.timeout)
    if a.command=="dex-list": return runtime_request(target_payload(a,"memory.dex.list", loader=a.loader, include_class_count=a.class_count), a.timeout)
    if a.command=="dex-dump": return runtime_request(target_payload(a,"memory.dex.dump", dex=a.dex_handle, max_bytes=a.max_bytes), a.timeout)
    if a.command=="assets-list": return runtime_request(target_payload(a,"memory.assets.list", path=a.path, max_assets=a.max_assets), a.timeout)
    if a.command=="assets-pull": return runtime_request(target_payload(a,"memory.assets.pull", path=a.path, max_bytes=a.max_bytes), a.timeout)
    if a.command=="xml-pull": return runtime_request(target_payload(a,"memory.xml.pull", resource_id=a.resource_id), a.timeout)
    if a.command=="xml-binary": return runtime_request(target_payload(a,"memory.xml.binary", resource_id=a.resource_id, entry=a.entry, apk_package=a.apk_package, apk_path=a.apk_path, max_bytes=a.max_bytes), a.timeout)
    if a.command=="apk-entries": return runtime_request(target_payload(a,"memory.apk.entries", prefix=a.prefix, apk_package=a.apk_package, apk_path=a.apk_path, max_entries=a.max_entries), a.timeout)
    if a.command=="apk-pull": return runtime_request(target_payload(a,"memory.apk.pull", entry=a.entry, source=a.source, apk_package=a.apk_package, apk_path=a.apk_path, max_bytes=a.max_bytes), a.timeout)
    raise CliError("INVALID_COMMAND", a.command)

def main(argv=None):
    raw=list(sys.argv[1:] if argv is None else argv); json_output="--json" in raw; raw=[x for x in raw if x!="--json"]
    try: emit_result(execute(parser().parse_args(raw)), json_output); return 0
    except CliError as e: emit_error(e, json_output); return 1
if __name__=="__main__": raise SystemExit(main())
