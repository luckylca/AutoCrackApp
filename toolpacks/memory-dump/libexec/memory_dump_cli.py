#!/usr/bin/env python3
import argparse, os, sys, base64, copy, json
sys.path.insert(0, os.path.dirname(__file__))
from autocrack_runtime_client import *

def add_output(parser):
    parser.add_argument("--output", help="write returned bytes/text to a local file; module-dump writes a directory")


def parser():
    p=argparse.ArgumentParser(prog="memory-dump", description="Dump bounded maps, modules, Dex, assets and XML through AutoCrack Runtime")
    p.add_argument("--version", action="version", version=f"memory-dump {VERSION}")
    sub=p.add_subparsers(dest="command", required=True)
    cap=sub.add_parser("capabilities"); add_target(cap)
    maps=sub.add_parser("maps"); add_target(maps); maps.add_argument("--max-maps", type=int, default=4096); maps.add_argument("--path-contains", default=""); maps.add_argument("--permissions-contains", default="")
    mods=sub.add_parser("modules"); add_target(mods); mods.add_argument("--filter", default=""); mods.add_argument("--max-modules", type=int, default=1024)
    nmods=sub.add_parser("native-modules"); add_target(nmods); nmods.add_argument("--filter", default=""); nmods.add_argument("--max-modules", type=int, default=1024)
    rd=sub.add_parser("read"); add_target(rd); rd.add_argument("address"); rd.add_argument("size", type=int); add_output(rd)
    np=sub.add_parser("native-probe"); add_target(np)
    da=sub.add_parser("dladdr"); add_target(da); da.add_argument("address")
    md=sub.add_parser("module-dump"); add_target(md); md.add_argument("path"); md.add_argument("--max-bytes", type=int, default=4194304); add_output(md)
    mfd=sub.add_parser("module-file-dump"); add_target(mfd); mfd.add_argument("path"); mfd.add_argument("--max-bytes", type=int, default=4194304); add_output(mfd)
    ei=sub.add_parser("elf-info"); add_target(ei); ei.add_argument("--path", default=""); ei.add_argument("--entry", default=""); ei.add_argument("--apk-package", default=""); ei.add_argument("--apk-path", default=""); ei.add_argument("--max-bytes", type=int, default=4194304)
    es=sub.add_parser("elf-symbols"); add_target(es); es.add_argument("--path", default=""); es.add_argument("--entry", default=""); es.add_argument("--apk-package", default=""); es.add_argument("--apk-path", default=""); es.add_argument("--filter", default=""); es.add_argument("--include-symtab", action="store_true"); es.add_argument("--max-symbols", type=int, default=1024); es.add_argument("--max-bytes", type=int, default=4194304)
    er=sub.add_parser("elf-relocations"); add_target(er); er.add_argument("--path", default=""); er.add_argument("--entry", default=""); er.add_argument("--apk-package", default=""); er.add_argument("--apk-path", default=""); er.add_argument("--filter", default=""); er.add_argument("--max-relocations", type=int, default=1024); er.add_argument("--max-bytes", type=int, default=4194304)
    ed=sub.add_parser("elf-dynamic"); add_target(ed); ed.add_argument("--path", default=""); ed.add_argument("--entry", default=""); ed.add_argument("--apk-package", default=""); ed.add_argument("--apk-path", default=""); ed.add_argument("--max-entries", type=int, default=512); ed.add_argument("--max-bytes", type=int, default=4194304)
    dl=sub.add_parser("dex-list"); add_target(dl); dl.add_argument("--loader", default="", help="optional ClassLoader handle returned by runtime-inspect classloaders"); dl.add_argument("--class-count", action="store_true", help="count dex class entries up to the runtime safety cap")
    dap=sub.add_parser("dex-art-probe"); add_target(dap); dap.add_argument("--loader", default="", help="optional ClassLoader handle"); dap.add_argument("--class-count", action="store_true"); dap.add_argument("--max-dex", type=int, default=256); dap.add_argument("--no-context-loader", action="store_true")
    di=sub.add_parser("dex-info"); add_target(di); di.add_argument("--path", default=""); di.add_argument("--entry", default=""); di.add_argument("--apk-package", default=""); di.add_argument("--apk-path", default=""); di.add_argument("--max-bytes", type=int, default=4194304)
    ds=sub.add_parser("dex-scan"); add_target(ds); ds.add_argument("--path-contains", default=""); ds.add_argument("--max-maps", type=int, default=256); ds.add_argument("--max-scan-bytes-per-map", type=int, default=2097152); ds.add_argument("--max-candidates", type=int, default=64); ds.add_argument("--include-anonymous", action="store_true"); ds.add_argument("--dump-bytes", type=int, default=0); ds.add_argument("--output")
    dd=sub.add_parser("dex-dump"); add_target(dd); dd.add_argument("dex_handle"); dd.add_argument("--max-bytes", type=int, default=4194304); add_output(dd)
    al=sub.add_parser("assets-list"); add_target(al); al.add_argument("path", nargs="?", default=""); al.add_argument("--max-assets", type=int, default=20000)
    ap=sub.add_parser("assets-pull"); add_target(ap); ap.add_argument("path"); ap.add_argument("--max-bytes", type=int, default=4194304); add_output(ap)
    xp=sub.add_parser("xml-pull"); add_target(xp); xp.add_argument("resource_id", type=int); add_output(xp)
    xb=sub.add_parser("xml-binary"); add_target(xb); xb.add_argument("--resource-id", type=lambda x:int(x,0), default=0); xb.add_argument("--entry", default=""); xb.add_argument("--apk-package", default=""); xb.add_argument("--apk-path", default=""); xb.add_argument("--max-bytes", type=int, default=4194304); add_output(xb)
    xd=sub.add_parser("xml-axml-decode"); add_target(xd); xd.add_argument("--resource-id", type=lambda x:int(x,0), default=0); xd.add_argument("--entry", default=""); xd.add_argument("--apk-package", default=""); xd.add_argument("--apk-path", default=""); xd.add_argument("--max-bytes", type=int, default=4194304); xd.add_argument("--max-nodes", type=int, default=1024); xd.add_argument("--max-attributes", type=int, default=256)
    xt=sub.add_parser("xml-axml-text"); add_target(xt); xt.add_argument("--resource-id", type=lambda x:int(x,0), default=0); xt.add_argument("--entry", default=""); xt.add_argument("--apk-package", default=""); xt.add_argument("--apk-path", default=""); xt.add_argument("--max-bytes", type=int, default=4194304); xt.add_argument("--max-nodes", type=int, default=1024); xt.add_argument("--max-attributes", type=int, default=256); xt.add_argument("--no-declaration", action="store_true"); add_output(xt)
    ae=sub.add_parser("apk-entries"); add_target(ae); ae.add_argument("--prefix", default=""); ae.add_argument("--apk-package", default=""); ae.add_argument("--apk-path", default=""); ae.add_argument("--max-entries", type=int, default=5000)
    apkp=sub.add_parser("apk-pull"); add_target(apkp); apkp.add_argument("entry"); apkp.add_argument("--source", default="base"); apkp.add_argument("--apk-package", default=""); apkp.add_argument("--apk-path", default=""); apkp.add_argument("--max-bytes", type=int, default=4194304); add_output(apkp)
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
    if a.command=="elf-info": return runtime_request(target_payload(a,"memory.elf.info", path=a.path, entry=a.entry, apk_package=a.apk_package, apk_path=a.apk_path, max_bytes=a.max_bytes), a.timeout)
    if a.command=="elf-symbols": return runtime_request(target_payload(a,"memory.elf.symbols", path=a.path, entry=a.entry, apk_package=a.apk_package, apk_path=a.apk_path, filter=a.filter, include_symtab=a.include_symtab, max_symbols=a.max_symbols, max_bytes=a.max_bytes), a.timeout)
    if a.command=="elf-relocations": return runtime_request(target_payload(a,"memory.elf.relocations", path=a.path, entry=a.entry, apk_package=a.apk_package, apk_path=a.apk_path, filter=a.filter, max_relocations=a.max_relocations, max_bytes=a.max_bytes), a.timeout)
    if a.command=="elf-dynamic": return runtime_request(target_payload(a,"memory.elf.dynamic", path=a.path, entry=a.entry, apk_package=a.apk_package, apk_path=a.apk_path, max_entries=a.max_entries, max_bytes=a.max_bytes), a.timeout)
    if a.command=="dex-list": return runtime_request(target_payload(a,"memory.dex.list", loader=a.loader, include_class_count=a.class_count), a.timeout)
    if a.command=="dex-art-probe": return runtime_request(target_payload(a,"memory.dex.art_probe", loader=a.loader, include_class_count=a.class_count, max_dex=a.max_dex, include_context_loader=not a.no_context_loader), a.timeout)
    if a.command=="dex-info": return runtime_request(target_payload(a,"memory.dex.info", path=a.path, entry=a.entry, apk_package=a.apk_package, apk_path=a.apk_path, max_bytes=a.max_bytes), a.timeout)
    if a.command=="dex-scan": return runtime_request(target_payload(a,"memory.dex.scan", path_contains=a.path_contains, max_maps=a.max_maps, max_scan_bytes_per_map=a.max_scan_bytes_per_map, max_candidates=a.max_candidates, include_anonymous=a.include_anonymous, dump_bytes=a.dump_bytes, include_data=bool(a.output and a.dump_bytes>0)), a.timeout)
    if a.command=="dex-dump": return runtime_request(target_payload(a,"memory.dex.dump", dex=a.dex_handle, max_bytes=a.max_bytes), a.timeout)
    if a.command=="assets-list": return runtime_request(target_payload(a,"memory.assets.list", path=a.path, max_assets=a.max_assets), a.timeout)
    if a.command=="assets-pull": return runtime_request(target_payload(a,"memory.assets.pull", path=a.path, max_bytes=a.max_bytes), a.timeout)
    if a.command=="xml-pull": return runtime_request(target_payload(a,"memory.xml.pull", resource_id=a.resource_id), a.timeout)
    if a.command=="xml-binary": return runtime_request(target_payload(a,"memory.xml.binary", resource_id=a.resource_id, entry=a.entry, apk_package=a.apk_package, apk_path=a.apk_path, max_bytes=a.max_bytes), a.timeout)
    if a.command=="xml-axml-decode": return runtime_request(target_payload(a,"memory.xml.axml_decode", resource_id=a.resource_id, entry=a.entry, apk_package=a.apk_package, apk_path=a.apk_path, max_bytes=a.max_bytes, max_nodes=a.max_nodes, max_attributes=a.max_attributes), a.timeout)
    if a.command=="xml-axml-text": return runtime_request(target_payload(a,"memory.xml.axml_text", resource_id=a.resource_id, entry=a.entry, apk_package=a.apk_package, apk_path=a.apk_path, max_bytes=a.max_bytes, max_nodes=a.max_nodes, max_attributes=a.max_attributes, include_declaration=not a.no_declaration), a.timeout)
    if a.command=="apk-entries": return runtime_request(target_payload(a,"memory.apk.entries", prefix=a.prefix, apk_package=a.apk_package, apk_path=a.apk_path, max_entries=a.max_entries), a.timeout)
    if a.command=="apk-pull": return runtime_request(target_payload(a,"memory.apk.pull", entry=a.entry, source=a.source, apk_package=a.apk_package, apk_path=a.apk_path, max_bytes=a.max_bytes), a.timeout)
    raise CliError("INVALID_COMMAND", a.command)

def _safe_output_path(path):
    if not path:
        raise CliError("OUTPUT_REQUIRED", "--output path is required")
    return os.path.abspath(path)

def _write_single_data(result, output_path):
    if "data" not in result:
        raise CliError("NO_INLINE_DATA", "runtime result has no base64 data field to write")
    encoding = result.get("encoding")
    if encoding != "base64":
        raise CliError("UNSUPPORTED_ENCODING", f"expected base64 data, got {encoding!r}")
    data = base64.b64decode(result["data"])
    target = _safe_output_path(output_path)
    os.makedirs(os.path.dirname(target) or ".", exist_ok=True)
    with open(target, "wb") as f:
        f.write(data)
    out = copy.deepcopy(result)
    out.pop("data", None)
    out["output_path"] = target
    out["output_bytes"] = len(data)
    out["data_omitted"] = True
    return out

def _write_xml_text(result, output_path):
    if "xml" not in result:
        raise CliError("NO_XML_TEXT", "runtime result has no xml field to write")
    target = _safe_output_path(output_path)
    os.makedirs(os.path.dirname(target) or ".", exist_ok=True)
    data = result["xml"].encode("utf-8")
    with open(target, "wb") as f:
        f.write(data)
    out = copy.deepcopy(result)
    out["output_path"] = target
    out["output_bytes"] = len(data)
    return out

def _write_dex_scan_candidates(result, output_path):
    candidates = result.get("candidates")
    if not isinstance(candidates, list):
        raise CliError("NO_CANDIDATES", "runtime result has no candidates array")
    out_dir = _safe_output_path(output_path)
    os.makedirs(out_dir, exist_ok=True)
    out = copy.deepcopy(result)
    written = []
    for i, item in enumerate(out.get("candidates", [])):
        data = item.pop("data", None)
        if not data:
            continue
        if item.get("encoding") != "base64":
            raise CliError("UNSUPPORTED_ENCODING", f"candidate {i} is not base64")
        raw = base64.b64decode(data)
        addr = str(item.get("address", f"{i}" )).replace("0x", "")
        path = os.path.join(out_dir, f"dex_candidate_{i:03d}_{addr}.dex")
        with open(path, "wb") as f:
            f.write(raw)
        item["output_path"] = path
        item["output_bytes"] = len(raw)
        item["data_omitted"] = True
        written.append(path)
    manifest_path = os.path.join(out_dir, "manifest.json")
    out["output_dir"] = out_dir
    out["manifest_path"] = manifest_path
    out["written_candidates"] = len(written)
    with open(manifest_path, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=2)
    return out

def _write_module_segments(result, output_path):
    segments = result.get("segments")
    if not isinstance(segments, list):
        raise CliError("NO_SEGMENTS", "runtime result has no segments array to write")
    out_dir = _safe_output_path(output_path)
    os.makedirs(out_dir, exist_ok=True)
    out = copy.deepcopy(result)
    written = []
    total = 0
    for i, seg in enumerate(out.get("segments", [])):
        data = seg.pop("data", None)
        if not data:
            continue
        if seg.get("encoding") != "base64":
            raise CliError("UNSUPPORTED_ENCODING", f"segment {i} is not base64")
        raw = base64.b64decode(data)
        start = str(seg.get("start", f"{i}" )).replace("0x", "")
        path = os.path.join(out_dir, f"segment_{i:03d}_{start}.bin")
        with open(path, "wb") as f:
            f.write(raw)
        seg["output_path"] = path
        seg["output_bytes"] = len(raw)
        seg["data_omitted"] = True
        written.append(path)
        total += len(raw)
    manifest_path = os.path.join(out_dir, "manifest.json")
    out["output_dir"] = out_dir
    out["manifest_path"] = manifest_path
    out["written_segments"] = len(written)
    out["output_bytes"] = total
    with open(manifest_path, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=2)
    return out

def write_output_if_requested(args, result):
    output = getattr(args, "output", None)
    if not output:
        return result
    if getattr(args, "command", "") == "module-dump":
        return _write_module_segments(result, output)
    if getattr(args, "command", "") == "dex-scan":
        return _write_dex_scan_candidates(result, output)
    if getattr(args, "command", "") in ("xml-pull", "xml-axml-text"):
        return _write_xml_text(result, output)
    return _write_single_data(result, output)

def main(argv=None):
    raw=list(sys.argv[1:] if argv is None else argv); json_output="--json" in raw; raw=[x for x in raw if x!="--json"]
    try:
        args = parser().parse_args(raw)
        emit_result(write_output_if_requested(args, execute(args)), json_output)
        return 0
    except CliError as e: emit_error(e, json_output); return 1
if __name__=="__main__": raise SystemExit(main())
