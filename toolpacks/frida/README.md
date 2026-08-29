# AutoCrack Frida toolpack

This toolpack packages the official Frida Android ARM64 server and ARM64 Python bindings with a fixed, compiled AutoCrack RPC agent.

The RPC surface is intentionally bounded. It supports module/export observation, loaded Java class, method and field enumeration, bounded live-instance previews, scalar writes to selected writable instance fields, short native entry tracing by module + offset, loaded network-stack detection, and a short bounded Conscrypt `SSL_read` / `SSL_write` plaintext observer. Field writes accept only JSON scalar values for Java primitive, boxed primitive, character, and String fields; static, final, array, and arbitrary object writes are rejected. The TLS observer does not bypass certificate pinning or install a CA; it only retains bounded read/write previews from the already authorized target process. The toolpack does not expose arbitrary JavaScript evaluation, native memory writes, return-value replacement, arbitrary spawn, or an unrestricted Frida CLI to the Agent.

Run `frida-autocrack-client --help` to discover the fixed commands. Java inspection and mutation are exposed as `java-classes`, `java-methods`, `java-fields`, `java-instances`, and `java-field-write`; each command loads the bundled `frida-java-bridge`, so callers do not need to create raw JavaScript agents.

The final toolpack ZIP is produced only by `.github/workflows/frida-toolpack.yml`.
