# Android tcpdump skill

Use this `tcpdump` wrapper when packets must be captured from the real Android host. The command is invoked from Debian but executes the pinned host binary through `android-shell`.

## First steps

```bash
tcpdump --version
tcpdump -D
```

## Typical workflow

Capture a bounded trace into the shared workspace:

```bash
tcpdump -i any -nn -s0 -c 200 -w /workspace/capture.pcap 'tcp port 443'
pcap-summary /workspace/capture.pcap --mode protocols
```

Prefer packet-count or timeout bounds instead of unbounded captures. Store captures in `/workspace` so Debian analysis tools can read them directly. The Android `any` interface may warn that promiscuous mode is unsupported; that warning alone is not a capture failure.
