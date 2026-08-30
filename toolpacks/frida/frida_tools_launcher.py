#!/usr/bin/env python3
"""Small deterministic console-script launcher for the vendored upstream frida-tools package."""

from __future__ import annotations

import importlib
import sys


ENTRY_POINTS = {
    "frida": "frida_tools.repl:main",
    "frida-ls-devices": "frida_tools.lsd:main",
    "frida-ps": "frida_tools.ps:main",
    "frida-kill": "frida_tools.kill:main",
    "frida-ls": "frida_tools.ls:main",
    "frida-rm": "frida_tools.rm:main",
    "frida-pull": "frida_tools.pull:main",
    "frida-push": "frida_tools.push:main",
    "frida-discover": "frida_tools.discoverer:main",
    "frida-trace": "frida_tools.tracer:main",
    "frida-strace": "frida_tools.stracer:main",
    "frida-itrace": "frida_tools.itracer:main",
    "frida-join": "frida_tools.join:main",
    "frida-create": "frida_tools.creator:main",
    "frida-compile": "frida_tools.compiler:main",
    "frida-pm": "frida_tools.pm:main",
    "frida-apk": "frida_tools.apk:main",
}


def main() -> None:
    if len(sys.argv) < 2:
        raise SystemExit("missing Frida console command")
    command = sys.argv[1]
    target = ENTRY_POINTS.get(command)
    if target is None:
        raise SystemExit(f"unsupported Frida console command: {command}")
    module_name, function_name = target.split(":", 1)
    sys.argv = [command, *sys.argv[2:]]
    function = getattr(importlib.import_module(module_name), function_name)
    function()


if __name__ == "__main__":
    main()
