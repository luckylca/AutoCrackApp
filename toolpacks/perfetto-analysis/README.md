# perfetto-analysis toolpack

Packages the pinned upstream ARM64 Perfetto `trace_processor` CLI. The Mobile Pi Agent can open traces directly and execute arbitrary valid PerfettoSQL through the standard CLI. `perfetto_target_stats` remains an optional fixed-query UI helper, not a capability boundary. Android capture uses `/system/bin/perfetto` through unrestricted `android-shell` argv forwarding.
