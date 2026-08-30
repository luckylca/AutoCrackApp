# android-pcap-helper toolpack

Packages pinned Android ARM64 tcpdump 4.99.5 and libpcap 1.10.5 sources as a standard `tcpdump` CLI. The Debian launcher forwards every upstream argv element to the bundled binary in the Android host network namespace through `android-shell`.

Normal interfaces, output modes, rotation options, and arbitrary valid BPF filters are available. `/workspace` arguments are mapped to the shared Android-host backing directory, so captures written by host tcpdump are immediately readable in Debian. The older structured capture helper may remain available to the UI as a convenience, but it is not the Mobile Pi Agent's capture boundary.
