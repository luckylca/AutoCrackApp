# Perfetto analysis skill

Use `trace_processor` to query Perfetto traces inside Debian.

## First steps

```bash
trace_processor --help
ls -lh /workspace/*.perfetto-trace /workspace/*.trace 2>/dev/null
```

## Typical workflow

1. Keep the trace file in `/workspace`.
2. Start with narrow PerfettoSQL queries for process/thread/slice or scheduling questions.
3. Aggregate before returning results; avoid dumping entire trace tables.
4. Save larger query results as TSV/CSV/JSON files in `/workspace`.

This Toolpack analyzes traces; Android trace collection is a separate host-side operation. Check `--help` for the exact batch/query syntax supported by the pinned `trace_processor`.
