# LIEF static analysis skill

Use `lief-elf-report` for structured ELF metadata in Debian and the bundled Python `lief` module when a custom bounded query is needed.

## First steps

```bash
lief-elf-report --help
lief-elf-report /workspace/libtarget.so
python3 -c 'import lief; print(lief.__version__)'
```

## Typical workflow

1. Run the report helper for headers, segments, sections, imports/exports and hardening context.
2. Combine with `checksec`/Rizin only when deeper analysis is required.
3. For custom Python, print only the fields needed for the current question and save large results under `/workspace`.

Treat malformed or unsupported binaries as explicit parse failures rather than empty results.
