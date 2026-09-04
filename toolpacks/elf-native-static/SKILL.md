# ELF native static analysis skill

Use `checksec`, `elf-deps`, and `elf-report` for a fast first pass over native ELF files in Debian.

## First steps

```bash
file /workspace/libtarget.so
checksec --file=/workspace/libtarget.so
elf-deps /workspace/libtarget.so
elf-report /workspace/libtarget.so
```

## Typical workflow

1. Confirm architecture/type with `file`.
2. Use `checksec` for hardening properties.
3. Use `elf-deps` for dynamic dependencies.
4. Use `elf-report` for the bounded consolidated report.
5. Escalate to LIEF or Rizin only for questions that need richer metadata/disassembly.

These tools are static/read-only. Keep generated reports in `/workspace` when they are too large to return directly.
