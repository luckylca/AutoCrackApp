#!/usr/bin/env python3
import json
import os
import sys

import lief

MAX_ITEMS = 512


def enum_name(value):
    name = getattr(value, "name", None)
    return str(name if name is not None else value)


def item_name(value):
    name = getattr(value, "name", None)
    return str(name if name is not None else value)


def bounded(values, limit=MAX_ITEMS):
    result = []
    for value in values:
        result.append(value)
        if len(result) >= limit:
            break
    return result


def symbol_json(symbol):
    return {
        "name": str(getattr(symbol, "name", "")),
        "value": int(getattr(symbol, "value", 0) or 0),
        "size": int(getattr(symbol, "size", 0) or 0),
        "type": enum_name(getattr(symbol, "type", "")),
        "binding": enum_name(getattr(symbol, "binding", "")),
        "visibility": enum_name(getattr(symbol, "visibility", "")),
        "imported": bool(getattr(symbol, "is_imported", False)),
        "exported": bool(getattr(symbol, "is_exported", False)),
    }


def main():
    if len(sys.argv) == 2 and sys.argv[1] == "--self-test":
        print(f"LIEF_STATIC_SELF_TEST_OK version={getattr(lief, '__version__', 'unknown')}")
        return 0
    if len(sys.argv) != 2:
        print("Usage: lief-elf-report <ELF-or-SO-file>", file=sys.stderr)
        return 2

    path = sys.argv[1]
    if not os.path.isfile(path):
        print(f"Input file not found: {path}", file=sys.stderr)
        return 3

    binary = lief.parse(path)
    if binary is None:
        print(f"LIEF failed to parse: {path}", file=sys.stderr)
        return 4

    header = getattr(binary, "header", None)
    sections = []
    for section in bounded(getattr(binary, "sections", [])):
        sections.append({
            "name": str(getattr(section, "name", "")),
            "virtualAddress": int(getattr(section, "virtual_address", 0) or 0),
            "offset": int(getattr(section, "offset", 0) or 0),
            "size": int(getattr(section, "size", 0) or 0),
            "type": enum_name(getattr(section, "type", "")),
            "flags": str(getattr(section, "flags", "")),
        })

    segments = []
    for segment in bounded(getattr(binary, "segments", [])):
        segments.append({
            "type": enum_name(getattr(segment, "type", "")),
            "virtualAddress": int(getattr(segment, "virtual_address", 0) or 0),
            "virtualSize": int(getattr(segment, "virtual_size", 0) or 0),
            "fileOffset": int(getattr(segment, "file_offset", 0) or 0),
            "physicalSize": int(getattr(segment, "physical_size", 0) or 0),
            "flags": str(getattr(segment, "flags", "")),
        })

    imported_functions = bounded(item_name(x) for x in getattr(binary, "imported_functions", []))
    exported_functions = bounded(item_name(x) for x in getattr(binary, "exported_functions", []))
    symbols = bounded((symbol_json(x) for x in getattr(binary, "symbols", [])))

    relocations = []
    for relocation in bounded(getattr(binary, "relocations", [])):
        symbol = getattr(relocation, "symbol", None)
        relocations.append({
            "address": int(getattr(relocation, "address", 0) or 0),
            "type": enum_name(getattr(relocation, "type", "")),
            "symbol": str(getattr(symbol, "name", "")) if symbol is not None else "",
        })

    report = {
        "tool": "AutoCrackApp LIEF static ELF report",
        "liefVersion": str(getattr(lief, "__version__", "unknown")),
        "format": "ELF" if binary.__class__.__module__.startswith("lief._lief.ELF") or "ELF" in str(type(binary)) else binary.__class__.__name__,
        "fileSizeBytes": os.path.getsize(path),
        "entrypoint": int(getattr(binary, "entrypoint", 0) or 0),
        "imagebase": int(getattr(binary, "imagebase", 0) or 0),
        "interpreter": str(getattr(binary, "interpreter", "") or ""),
        "libraries": bounded(str(x) for x in getattr(binary, "libraries", [])),
        "header": {
            "fileType": enum_name(getattr(header, "file_type", "")) if header is not None else "",
            "machineType": enum_name(getattr(header, "machine_type", "")) if header is not None else "",
            "identityClass": enum_name(getattr(header, "identity_class", "")) if header is not None else "",
            "identityData": enum_name(getattr(header, "identity_data", "")) if header is not None else "",
        },
        "counts": {
            "sections": len(getattr(binary, "sections", [])),
            "segments": len(getattr(binary, "segments", [])),
            "symbols": len(getattr(binary, "symbols", [])),
            "relocations": len(getattr(binary, "relocations", [])),
            "importedFunctions": len(getattr(binary, "imported_functions", [])),
            "exportedFunctions": len(getattr(binary, "exported_functions", [])),
        },
        "sections": sections,
        "segments": segments,
        "importedFunctions": imported_functions,
        "exportedFunctions": exported_functions,
        "symbols": symbols,
        "relocations": relocations,
    }
    print(json.dumps(report, ensure_ascii=False, separators=(",", ":")))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
