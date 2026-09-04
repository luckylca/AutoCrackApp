# PCAP analysis skill

Use `pcap-summary` for bounded, model-friendly analysis of classic PCAP files in Debian.

## First steps

```bash
pcap-summary --self-test
pcap-summary /workspace/capture.pcap --mode info
```

## Typical workflow

```bash
pcap-summary /workspace/capture.pcap --mode protocols
pcap-summary /workspace/capture.pcap --mode dns --max-records 32
pcap-summary /workspace/capture.pcap --mode tls --max-records 32
pcap-summary /workspace/capture.pcap --mode top-connections --max-records 32
```

The parser intentionally bounds records and packet processing. It summarizes metadata, DNS, TLS ClientHello SNI/ALPN, and connections; it is not a full Wireshark replacement and does not claim arbitrary application-protocol decoding.
