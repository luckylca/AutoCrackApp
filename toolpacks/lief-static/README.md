# LIEF static analysis

Packages the official PyPI ARM64 LIEF wheel into the shared Debian Python environment. The primary interface is the complete upstream Python API:

```text
python3 -c 'import lief; print(lief.__version__)'
```

`lief-elf-report` remains an optional bounded JSON report for compact UI output. Its item limits do not restrict direct `import lief` use.
