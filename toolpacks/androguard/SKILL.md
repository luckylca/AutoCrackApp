# Androguard skill

Use this Toolpack when the Agent needs structured APK/DEX/resource analysis rather
than only decompiler text.

This is a full upstream Androguard 4.1.4 environment for Debian ARM64. It ships
the complete upstream Python package and every resolved runtime dependency in the
pinned wheel lock. The `androguard` command is the upstream Click CLI; no
subcommand has been removed.

## First steps

```bash
androguard --help
androguard analyze --help
androguard axml --help
androguard arsc --help
androguard cg --help
androguard decompile --help
androguard disassemble --help
androguard sign --help
```

Always inspect `androguard --help` for the exact upstream command surface before
constructing a less-common invocation.

## Full Python API

The shared Toolpack environment adds this Toolpack's `python/` directory to
`PYTHONPATH`, so normal upstream imports work directly:

```python
from androguard.misc import AnalyzeAPK
from androguard.core.apk import APK
from androguard.core.axml import AXMLPrinter, ARSCParser
```

This matters for Agent workflows that need call graphs, DEX objects, classes,
methods, resources or certificate data without parsing human-readable CLI text.

## Typical AutoCrack flow

1. APKiD first for protection/packer triage.
2. Use Androguard for structured manifest, DEX, resources and graph queries.
3. Use JADX when readable Java/Kotlin-oriented decompilation is the goal.
4. Escalate to runtime-inspect, SimpleHook, Frida, memory-dump, Rizin or LLDB
   when static evidence is not enough.

## Integrity

`WHEELHOUSE.lock.json` records every packaged wheel filename, exact version,
source page and SHA-256. The builder rejects missing, additional or changed
wheels, so dependency drift cannot silently enter the production Toolpack.
