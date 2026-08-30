# rootfs-pcap-analysis

Android/rootfs-only pcap convenience analysis toolpack for AutoCrackApp.

The first command is `pcap-summary`, a pure-Python bounded parser that reads an existing workspace pcap and emits JSON summaries for:

- pcap global info
- protocol counts
- DNS query names
- TLS ClientHello SNI / ALPN when visible
- top TCP/UDP endpoint pairs

This helper's bounded JSON output prevents accidental multi-megabyte UI responses. It does not limit the Mobile Pi Agent's pcap capabilities: the standard `tcpdump` toolpack supports arbitrary valid BPF capture filters and offline `tcpdump -r /workspace/file.pcap` analysis.
