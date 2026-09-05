# AutoCrack Python 3.11 and versioned-layout compatibility patch

Upstream package: `frida-il2cpp-bridge 0.13.2`

Official npm package SHA-256:

`298430a57a9d713feedf2b26bd0495becf2823240429e6408545c86381ac8060`

AutoCrack's Debian Bookworm runtime uses Python 3.11. The upstream 0.13.2 CLI
contains Python 3.12-only **typing syntax**, but its runtime behavior does not
depend on Python 3.12 APIs.

The staging patch is deliberately limited to typing constructs:

- PEP 695 `type X = ...` aliases become ordinary aliases;
- PEP 695 generic class parameter lists are removed from runtime classes;
- generic-only base subscriptions are removed;
- `typing.override` imports/decorators are removed.

These constructs are static type metadata only. Frida session handling, target
selection, dump agent JavaScript, dump formatting, CLI options, messages and
all upstream runtime code remain unchanged.

The upstream CLI also discovers its npm module root by requiring a parent
directory whose name is exactly `frida-il2cpp-bridge`. AutoCrack installs
Toolpacks under `packs/<id>/<version>/` and exposes them through an `active`
symlink, so `Path.resolve()` reaches the version directory first and the
upstream name-based search incorrectly walks one level too far. The staging
patch changes only that root-discovery predicate: it walks upward to the first
directory that actually contains `package.json`. This keeps the CLI compatible
with versioned Toolpack installs without hard-coding a specific AutoCrack
version or changing Frida/IL2CPP behavior.

The Toolpack also retains an untouched copy of the official npm package under
`upstream-original/` for direct audit against the patched working copy.

The build fails unless every patched CLI Python file parses using Python 3.11
grammar.
