# Phase 2: Installed Apps and APK Extraction

## Scope

Phase 2 turns the verified KernelSU runtime into a constrained APK acquisition pipeline.

Implemented:

- Lists packages installed for the current Android user with `pm list packages -f -U --user <id>`.
- Parses package name, UID, primary APK path, and a coarse user/system path classification.
- Searches the list by package name or APK path.
- Resolves all APK paths with `pm path --user <id> <package>`.
- Distinguishes the Base APK from Split APKs.
- Creates an app-private, per-package, per-session workspace.
- Copies each APK through a typed Root tool.
- Changes copied-file ownership to the AutoCrackApp UID/GID and permissions to `0600`.
- Verifies every copy exists, is non-empty, and has a SHA-256 digest.
- Deletes incomplete workspaces if any copy or verification step fails.
- Reads stdout and stderr concurrently to avoid process-pipe deadlocks on devices with many installed packages.

## Security boundary

The model and user interface cannot provide arbitrary Shell strings.

The Root layer exposes only these Phase 2 operations:

1. List packages for a numeric Android user ID.
2. Query APK paths for a validated Android package name.
3. Copy an absolute `.apk` source to an app-controlled absolute `.apk` destination.

Package names reject whitespace and Shell metacharacters. Paths reject control characters and are Shell-quoted. Destination paths are canonicalized and must remain under AutoCrackApp's private workspace. Extracted target code is copied as data and is never loaded or executed.

Phase 2 does not:

- Read another application's private `/data/user/...` data.
- Modify or reinstall a target application.
- Load target DEX files with a class loader.
- Load or call target SO libraries.
- Decode resources or decompile code.
- Hook or inject into a process.
- Send APK contents to an external API.

## Workspace layout

```text
files/workspaces/<package-name>/session-<timestamp>/
├── base.apk
├── split_config.arm64_v8a.apk
├── split_config.xxhdpi.apk
└── ...
```

The exact Split APK filenames are derived from `pm path`, sanitized, and de-duplicated.

## Device test checklist

1. Install the Phase 2 APK over Phase 1 and open it.
2. Confirm Root remains authorized and the app list finishes loading.
3. Search for one normal user application and verify the package name and APK path look correct.
4. Extract an application that has Split APKs and confirm the result shows one Base APK plus one or more Split APKs.
5. Extract a small single-APK application and confirm one non-empty file and a 64-character SHA-256 digest are shown.

Record the answers to the test questions supplied with the APK artifact. Keep the pull request open until the KernelSU device test passes.
