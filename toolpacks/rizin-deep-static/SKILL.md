# Rizin deep static analysis skill

Use this Toolpack for native binary inspection in Debian. Start with the bounded AutoCrack wrappers before opening an unrestricted interactive Rizin session.

## First steps

```bash
rz-deep-report /workspace/libtarget.so
rz-functions /workspace/libtarget.so
rz-disasm /workspace/libtarget.so
rizin --help
```

## Typical workflow

1. Identify the ELF with `file`, `checksec`, or `lief-elf-report`.
2. Use `rz-deep-report` for a bounded overview.
3. Narrow to functions/symbols, then request disassembly for only the relevant region.
4. Use raw `rizin` only when the wrappers cannot answer the question.

Prefer machine-readable or bounded text output and save large analyses to files under `/workspace` rather than streaming everything into the model context.
