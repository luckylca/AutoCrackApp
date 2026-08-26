# AutoCrack Frida toolpack

This toolpack packages the official Frida Android ARM64 server and ARM64 Python bindings with a fixed, compiled AutoCrack RPC agent.

The RPC surface is intentionally bounded. It supports module/export observation, loaded Java class and declared-method enumeration, and short native entry tracing by module + offset. It does not expose arbitrary JavaScript evaluation, memory writes, return-value replacement, arbitrary spawn, or an unrestricted Frida CLI to the Agent.

The final toolpack ZIP is produced only by `.github/workflows/frida-toolpack.yml`.
