# android-pcap-helper toolpack

Packages a pinned Android ARM64 tcpdump binary for AutoCrackApp-managed passive capture.

`android_pcap_start` now prefers the app-managed runtime tcpdump location before falling back to system paths. This helper does not install certificates, edit Surfing / box configuration, use VPNService, expose arbitrary filters, or modify iptables/nftables.
