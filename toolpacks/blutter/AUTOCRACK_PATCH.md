# AutoCrack Linux compatibility patch

Upstream revision:

`4a60ac648bf448c5a7596437243bcd0b9376fdf0`

Source archive SHA-256:

`f48e5a0d767dd5bb3dcd999afd45436c6de0f8b981a3cebe689750dc1a2af61f`

The upstream `blutter/CMakeLists.txt` applies a block explicitly commented
"for macOS only" whenever the compiler ID is Clang. On Linux that introduces
the Darwin linker flag `-dead_strip`.

The AutoCrack staging step changes that block to:

- keep the original behavior under `APPLE AND Clang`;
- on Linux + Clang, compile/link with libc++ and
  `-fexperimental-library`;
- make no changes to Blutter analysis source, Dart-version logic, output
  generation or runtime workflow.

This patch exists only to make the upstream-supported Clang >=16 path usable in
the Debian ARM64 rootfs.
