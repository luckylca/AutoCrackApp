# Phase 5.4 — Root Runtime Foundation

## Direction

AutoCrackApp is moving from Kotlin-only analysis buttons to a tools-first runtime architecture.

Priority order:

1. KernelSU / Root full mode;
2. Shizuku compatibility mode;
3. generic sandbox-only operation is postponed.

The final Agent will primarily use Bash and installed reverse-engineering tools inside a managed Linux runtime. Android-specific privileged operations remain behind a typed `HostBridge`.

## 0.5.4 scope

This build establishes the host-side runtime foundation before installing the Debian rootfs:

- managed runtime and workspace directory layout;
- structured Root Shell execution;
- bounded stdout and stderr capture;
- timeout and manual cancellation;
- command request IDs;
- JSONL audit records;
- workspace-scoped file operations;
- Root/chroot prerequisite inspection;
- machine-readable tool catalog;
- rootfs installer manifest schema;
- manual on-device runtime console and copyable diagnostics.

The current shell is Android's host `/system/bin/sh` executed through KernelSU. It is not yet the final Debian `/bin/bash` environment.

## Managed directories

```text
files/
├── runtime/
│   ├── rootfs/
│   ├── home/
│   ├── bin/
│   ├── toolpacks/
│   ├── sessions/
│   ├── audit/
│   │   └── shell-exec.jsonl
│   ├── tmp/
│   └── runtime-state.json
└── workspaces/
    └── runtime-foundation/
```

## Structured shell result

Every `shell.exec` result records:

- request ID;
- command and working directory;
- execution identity;
- exit code;
- stdout and stderr;
- start, completion and duration;
- timeout/cancellation state;
- output truncation state;
- failure message;
- audit-file path.

Environment variable values are not copied into the audit record; only their names are stored.

## Workspace file primitives

The application now provides workspace-scoped implementations of:

- list;
- stat;
- read text;
- write/append text;
- mkdir;
- copy;
- move;
- delete;
- SHA-256.

Canonical-path checks reject `..` and symlink/path traversal outside the active workspace for these structured file operations.

Manual Root Shell commands are intentionally more powerful. They are currently exposed only for direct user validation. When the Agent is connected, host Root commands will be capability-scoped and confirmation-gated, while unrestricted analysis Bash will run inside the chroot workspace.

## Rootfs installer state

The app initializes `runtime-state.json` with one of:

- `NOT_INSTALLED`;
- `MANIFEST_READY`;
- `INSTALLING`;
- `INSTALLED`;
- `BROKEN`.

`rootfs-manifest-v1.json` defines the pinned artifact fields, required free space, entry shell and bind mounts. The actual signed/checksummed Debian arm64 archive will be published and wired into the next build rather than downloading an unpinned image.

## Root and Shizuku separation

```kotlin
interface RuntimeEngine
interface HostBridge
```

Root mode uses a KernelSU bridge and will receive the full feature set first.

Shizuku support will later implement a second `HostBridge` using Shizuku's permission flow and UserService. It will not weaken or constrain the Root runtime. According to Shizuku's official API documentation, a UserService can run under UID 2000 when Shizuku is started through ADB, or UID 0 in an appropriate root-backed environment; supported operations still depend on its actual UID, capabilities and SELinux context.

## Next build

The next runtime milestone is:

1. publish a pinned Debian arm64 rootfs artifact;
2. download/import and verify SHA-256;
3. safely extract into a staging directory;
4. atomically promote the installation;
5. bind `/dev`, `/proc`, `/sys` and the active workspace;
6. run `/bin/bash -lc` through `chroot`;
7. unmount reliably after failures or cancellation;
8. add a persistent PTY session and foreground process service.

## Device validation

Open **运行时** from the top navigation and verify:

1. `FULL_ROOT` and UID 0 are reported;
2. the default command exits with code 0 and returns `id`, `uname`, `pwd` and `ls` output;
3. `sleep 30` can be cancelled;
4. the file card can write, read, hash and delete `runtime-smoke-test.txt`;
5. the audit card contains JSON records with request IDs and real exit codes;
6. copying the diagnostic report includes stdout, stderr, failure and audit paths.
