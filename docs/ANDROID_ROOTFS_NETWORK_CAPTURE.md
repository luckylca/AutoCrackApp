# Android/rootfs network capture plan

This network design assumes AutoCrackApp's final runtime has no Mac, no desktop host worker, and no VPNService capture mode.

The target device may already run Surfing / box-style transparent proxy modules under Magisk, KernelSU, or APatch. AutoCrackApp should integrate with that environment instead of replacing it.

## Goals

```text
No VPNService dependency
No Mac-side mitmproxy / tshark / adb runtime dependency
Reuse the user's existing Surfing / box proxy when present
Keep every capture artifact inside the AutoCrackApp workspace
Prefer read-only detection before changing Surfing, iptables, nftables, certificates, or proxy settings
```

## Non-goals for the first implementation

```text
Do not auto-bypass certificate pinning.
Do not silently install CA certificates.
Do not take ownership of the user's Surfing configuration.
Do not expose arbitrary iptables/nftables edits to the Agent.
Do not use a desktop host as a packet analyzer or HTTPS proxy.
```

## Execution surfaces

```text
Android root shell
  - /proc/net, ss, ip, iptables/nft, dumpsys connectivity, logcat
  - tcpdump or another Android ARM64 packet capture binary
  - Surfing / box config and status files under /data/adb when authorized

Debian ARM64 rootfs
  - pcap parsers or tshark-like tools packaged as ARM64 toolpacks
  - bounded JSON summarizers
  - optional mitmdump only if packaged for ARM64 and launched locally on the device

Android loopback
  - local helper communication only, for example 127.0.0.1 between rootfs clients and Android helpers
```

## Surfing / box integration layer

First add a read-only integration executor before any traffic redirection or capture mutation:

```text
android.surfing.detect
android.surfing.status
android.surfing.config_summary
android.surfing.target_policy_check
android.surfing.log_tail_bounded
```

The executor should detect common Surfing / box locations without assuming a single fork:

```text
/data/adb/box_bll/
/data/adb/box/
/data/adb/modules/*surfing*/
/data/adb/modules/*box*/
```

Allowed first-pass outputs:

```text
modulePresent
serviceState
proxyCoreName, if visible
proxyMode, if visible
selected config files and SHA-256 values
whether the selected target package appears in include/exclude package filters
bounded last N log lines with secret redaction
```

The Agent should not edit Surfing configuration in the first phase. Any later edit tool must be explicit, reversible, and scoped to the currently selected package.

## Packet capture layer

The first Android-root Agent implementation now exposes managed-device tools equivalent to:

```text
android_surfing_status
android_net_environment
android_net_target_connections
android_pcap_start
android_pcap_stop
android_pcap_status
```

`android_pcap_start` is exposed only after explicit dynamic-tool authorization. It accepts only `duration_seconds`, `snaplen`, and `max_bytes`, and rejects unknown model-supplied arguments so the Agent cannot choose arbitrary interfaces, BPF filters, iptables/nftables rules, Surfing config patches, CA installation, VPN mode, or MITM mode through this surface. It is bounded by both duration and file size. The current defaults are 30 seconds and 16 MiB, with hard Agent-side caps of 300 seconds and 64 MiB. The helper writes only into the selected package's AutoCrack workspace and records its pcap path in the session metadata before stop/status operations are allowed to signal the process.

Important constraints:

```text
pcap files must live under the current workspace
capture duration and file size must be bounded
capture should tag the selected package, UID, start time, interface, and filter strategy
cleanup must stop only AutoCrack-owned capture helpers
```

Because normal BPF cannot reliably filter by Android UID, UID scoping should be implemented by correlation:

```text
selected package -> UID
/proc/net and ss snapshots -> sockets/endpoints
pcap packets -> endpoints/time windows
Surfing logs -> proxy decisions
Frida events -> callsite and plaintext metadata when enabled
```

## Rootfs pcap analysis layer

Add a rootfs ARM64 pcap-analysis toolpack later, with typed tools such as:

```text
rootfs.pcap.info
rootfs.pcap.protocol_summary
rootfs.pcap.dns_summary
rootfs.pcap.tls_summary
rootfs.pcap.http_cleartext_summary
rootfs.pcap.top_hosts
rootfs.pcap.top_connections
rootfs.pcap.follow_stream_bounded
```

The Agent must pass a workspace pcap handle, not an arbitrary filesystem path.

## HTTPS strategy without VPN

HTTPS visibility should be layered:

### Layer 1: metadata

```text
pcap + /proc/net + Surfing logs
```

This gives domains, IPs, ports, SNI when visible, ALPN when visible, timing, byte counts, and whether the target appears to use QUIC/HTTP3.

### Layer 2: Java/runtime observation

Use the existing Frida toolpack through fixed, audited templates. The first concrete Agent surface is:

```text
frida_net_detect_stack
frida_tls_trace
```

`frida_net_detect_stack` reports bounded loaded classes associated with OkHttp, `HttpURLConnection`, Conscrypt, `javax.net.ssl`, and Cronet. `frida_tls_trace` installs only a fixed Conscrypt `SSL_read` / `SSL_write` observer, runs for at most 5 seconds, retains at most 128 events, and retains at most 1024 plaintext preview bytes per event. It does not bypass pinning, install a CA, replace return values, expose arbitrary JavaScript, or modify Surfing.

Additional fixed OkHttp/URLConnection/Cronet templates can be added after this Conscrypt path is verified on-device. Frida observation remains the preferred HTTPS-content path because it coexists with the user's existing transparent proxy instead of taking ownership of routing.

### Layer 3: optional local MITM

Only after explicit user authorization, a future rootfs-local mitm tool may run on the device itself:

```text
rootfs.mitm.session_start
rootfs.mitm.session_stop
rootfs.mitm.flow_summary
rootfs.mitm.export_har
```

It must not require a Mac. It must also be coordinated with Surfing, because both systems may want to own proxying or redirection. The first implementation should not modify Surfing rules automatically.

## Recommended implementation order

1. `android.surfing.*` read-only detection and report.
2. `android.net.*` connection snapshots and bounded tcpdump capture.
3. `rootfs.pcap.*` pcap summary from workspace files.
4. `android.frida.net_*` fixed network observation templates.
5. Optional rootfs-local MITM, only with explicit authorization and reversible configuration.
