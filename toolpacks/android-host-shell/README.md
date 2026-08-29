# android-host-shell toolpack

Provides the `android-shell` CLI inside the Debian rootfs. The CLI connects to a session-scoped loopback bridge started by AutoCrackApp and forwards argv to the existing Android root shell runtime.

The toolpack intentionally adds no new model-facing tool. Agents continue to use `exec_bash` and invoke `android-shell` as a normal CLI command.
