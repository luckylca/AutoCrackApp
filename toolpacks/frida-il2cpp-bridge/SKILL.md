# frida-il2cpp-bridge full IL2CPP skill

This Toolpack preserves the complete official `frida-il2cpp-bridge 0.13.2`
npm package and its upstream CLI/library surface.

It depends on the existing AutoCrack `android-frida` Toolpack for the Frida
17.17.0 Python binding, `frida-tools`, CLI and managed Android server.

## Upstream CLI

```bash
android-frida-server start
frida-il2cpp-bridge --help
frida-il2cpp-bridge --version
frida-il2cpp-bridge -H 127.0.0.1:27042 -f com.example.app dump \
  --out-dir /workspace/il2cpp-dump
```

The AutoCrack command invokes the upstream `cli/main.py`; target/device,
spawn/attach and Frida ConsoleApplication options remain upstream options.

Use `--help` on the installed command for the authoritative option surface.

## Complete JavaScript library

The complete compiled upstream library is installed at:

```text
/opt/autocrack/toolpacks/active/frida-il2cpp-bridge/dist/index.js
```

and its type definitions/source map are preserved beside it.

The upstream build exposes the global `Il2Cpp` object. This includes the
runtime APIs represented in the shipped type surface, including:

- `Il2Cpp.perform(...)`;
- class/image/domain/assembly enumeration;
- `Il2Cpp.dump()` and `Il2Cpp.dumpTree()`;
- `Il2Cpp.trace(...)` / tracer configuration;
- method invocation and method implementation replacement/interception;
- fields, objects, strings, arrays, value types, memory snapshots and GC-related
  runtime structures supported upstream.

For a plain JavaScript agent, concatenate the already-compiled upstream prelude
before your own script and load it with the normal Frida CLI:

```bash
ROOT=/opt/autocrack/toolpacks/active/frida-il2cpp-bridge
cat "$ROOT/dist/index.js" /workspace/my-il2cpp-agent.js \
  > /workspace/my-il2cpp-agent.bundle.js
frida -H 127.0.0.1:27042 -f com.example.app \
  -l /workspace/my-il2cpp-agent.bundle.js
```

This uses the same complete library that the upstream dump CLI prepends to its
own `cli/src/dump/agent.js`.

## Python 3.11 compatibility

The upstream 0.13.2 CLI uses several Python 3.12-only typing constructs.
AutoCrack applies only a documented typing-syntax compatibility patch so the
upstream runtime can execute on Debian Bookworm Python 3.11. No runtime feature
is removed. See `AUTOCRACK_PATCH.md`.

An untouched copy of the npm package is retained at `upstream-original/`.

## Typical workflow

1. Start the managed Frida server.
2. Confirm the target with `frida-ps -H 127.0.0.1:27042`.
3. Use the upstream bridge CLI for structured IL2CPP dumps.
4. Use the complete `dist/index.js` prelude for custom trace/intercept/runtime
   agents.
5. Save dumps and scripts under `/workspace`.
6. Stop the managed Frida server when finished.
