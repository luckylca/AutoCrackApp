# jnitrace skill

Use this Toolpack to trace JNI and JavaVM API traffic made by native Android
libraries. It packages the complete upstream jnitrace 3.3.1 Python package and
the upstream compiled `jnitrace/build/jnitrace.js` tracing engine.

The `jnitrace` command is the upstream CLI. AutoCrack only supplies the
environment needed to reuse the existing `android-frida` Toolpack; no upstream
options are removed or replaced.

## Required companion Toolpack

Start the existing Frida server before tracing:

```bash
android-frida-server start
```

Because AutoCrack executes the client inside the same device-side Debian rootfs,
prefer upstream remote mode:

```bash
jnitrace -R -l libtarget.so com.example.target
```

`-R` without an argument uses the upstream default `127.0.0.1:27042`.

## Full upstream CLI

All upstream modes remain available, including:

- spawn or attach injection (`-m spawn|attach`);
- remote Frida targets (`-R [HOST:PORT]`);
- JNI/JVM include and exclude filters (`-i`, `-e`);
- exported-symbol include/exclude filters (`-I`, `-E`);
- fuzzy/accurate/no backtraces (`-b`);
- data visibility control;
- JNIEnv/JavaVM suppression;
- prepend and append Frida scripts (`-p`, `-a`);
- JSON trace output (`-o`);
- spawn auxiliary options (`--aux`);
- one or more library filters (`-l`).

Always use `jnitrace --help` for the exact upstream argument surface.

## Examples

```bash
jnitrace -R -l libfoo.so com.example.target

jnitrace -R -m attach -l libfoo.so \
  -i 'FindClass|GetMethodID|Call.*Method' \
  -b accurate \
  -o /workspace/jni-trace.json \
  com.example.target

jnitrace -R -l '*' -e 'GetVersion' --hide-data com.example.target
```

The output file produced by `-o` is upstream jnitrace JSON and can be retained
under `/workspace` for later Agent analysis.

## Capability boundary

jnitrace is a dynamic instrumentation client. It does not replace Frida server
lifecycle management, APK decompilation, native disassembly, or memory dumping.
Use the corresponding AutoCrack Toolpacks for those tasks.
