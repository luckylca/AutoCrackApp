# Blutter full Flutter/Dart AOT reverse-engineering skill

This Toolpack packages the complete upstream B(l)utter source tree at immutable
commit `4a60ac648bf448c5a7596437243bcd0b9376fdf0`.

Upstream currently focuses on Android ARM64 `libapp.so` and recent Dart
versions. AutoCrack does not claim unsupported iOS, non-ARM64, old-Dart or
currently-broken Dart releases as working.

## Full upstream workflow

The normal command is the original upstream `blutter.py` interface:

```bash
blutter path/to/lib/arm64-v8a /workspace/blutter-out
blutter path/to/lib/arm64-v8a /workspace/blutter-out --rebuild
blutter path/to/libapp.so /workspace/blutter-out \
  --dart-version 3.11.4_android_arm64
blutter --help
```

The input may be an APK or a directory containing both `libapp.so` and
`libflutter.so`, exactly as supported upstream.

The upstream pipeline remains intact:

1. detect the Dart VM version/snapshot from `libflutter.so`;
2. sparse-clone the corresponding Dart SDK source when needed;
3. generate the Dart VM source list/version;
4. build/install the target Dart VM static library;
5. compile the version-specific Blutter backend;
6. analyze `libapp.so`;
7. generate assemblies/symbols, object-pool dumps and Frida template output.

Expected upstream outputs include `asm/*`, `objs.txt`, `pp.txt`, and
`blutter_frida.js`.

## Persistent build state

Blutter is inherently stateful: upstream creates `dartsdk/`, `build/`,
`packages/` and `bin/` caches. Toolpack payloads should remain immutable, so
the wrapper copies the complete source tree to:

```text
/workspace/.autocrack/blutter/4a60ac648bf448c5a7596437243bcd0b9376fdf0
```

on first use. Override this with `AUTOCRACK_BLUTTER_STATE_DIR`.

The wrapper refuses to overwrite an existing directory it did not create.

## Linux compiler compatibility

Upstream requires a recent C++ compiler and documents Clang >=16 as sufficient.
AutoCrack's Bookworm ARM64 rootfs therefore ships Clang/Clang++ 16, libc++ 16,
CMake, Ninja, pkg-config, ICU, Capstone, pyelftools and requests.

The packaged source contains one AutoCrack Linux compatibility patch to
`blutter/CMakeLists.txt`: the upstream Clang block contains macOS-only
`-dead_strip` handling but is not guarded by `APPLE`. AutoCrack keeps that
upstream behavior on macOS and uses Linux libc++/experimental-library flags on
Linux. No Blutter analysis feature is removed.

See `AUTOCRACK_PATCH.md` for the exact patch.

## Network behavior

When a required Dart version has not been cached, upstream Blutter itself
sparse-clones the matching Dart SDK release from the official Dart repository.
This is the original upstream behavior required to support arbitrary target
Dart versions.

## Known upstream boundaries

Do not convert an upstream crash into a false successful result. If a target
Dart version fails after a successful backend build, report the detected Dart
version, snapshot hash and upstream failure; preserve the generated build state
for diagnosis.
