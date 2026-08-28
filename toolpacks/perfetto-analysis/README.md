# perfetto-analysis toolpack

Packages the pinned ARM64 Perfetto `trace_processor` binary used by `perfetto_target_stats`. Capture uses Android `/system/bin/perfetto`; analysis runs inside the Debian ARM64 rootfs with fixed read-only PerfettoSQL.
