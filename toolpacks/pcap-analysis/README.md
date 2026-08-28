# rootfs-pcap-analysis

Small Android/rootfs-only pcap analysis toolpack for AutoCrackApp.

The first command is `pcap-summary`, a pure-Python bounded parser that reads an existing workspace pcap and emits JSON summaries for:

- pcap global info
- protocol counts
- DNS query names
- TLS ClientHello SNI / ALPN when visible
- top TCP/UDP endpoint pairs

It does not capture live traffic, accept BPF filters, edit Surfing/box settings, install certificates, or perform MITM.
