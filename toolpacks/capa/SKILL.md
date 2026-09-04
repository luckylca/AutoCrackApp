# capa full capability-analysis skill

This Toolpack packages the official `flare-capa 9.4.0` Python distribution
with a complete Linux ARM64/Python 3.11 wheelhouse, the matching official
`capa-rules v9.4.0` corpus, and the matching upstream FLIRT signatures.

Unlike a reduced wrapper, the command is the upstream `capa.main:main`
entrypoint and the complete `capa` Python package remains importable.

## Upstream CLI

```bash
capa --version
capa --help
capa /workspace/sample
capa -j /workspace/sample > /workspace/capa.json
capa -vv /workspace/sample
```

The packaged rules and signatures live where upstream capa itself expects its
embedded defaults, so a normal invocation uses them automatically.

All upstream overrides remain available:

```bash
capa -r /workspace/custom-rules /workspace/sample
capa -s /workspace/custom-sigs /workspace/sample
capa --help
```

AutoCrack does not inject or reinterpret `-r` or `-s`.

## Full Python API

The installed package exposes the normal upstream modules:

```python
import capa
import capa.main
import capa.loader
import capa.rules
import capa.engine
import capa.render.json
```

Use the Python API when an agent needs to compose capa extraction, matching or
rendering with other AutoCrack analysis steps instead of launching a subprocess.

## Inputs and outputs

Use the exact upstream `--help` output as the capability authority. The
shipped build includes the extractors/backends supplied by `flare-capa 9.4.0`
and its pinned dependencies, including ELF analysis support. JSON output remains
available through the upstream `-j/--json` mode.

For Android work, a common path is:

1. extract the APK/DEX/native libraries with the existing static Toolpacks;
2. identify packers/protectors with APKiD;
3. run capa against interesting native ELF files;
4. inspect JSON results alongside JADX/Androguard/LIEF output;
5. move to Frida/jnitrace/runtime-control only when static evidence requires
   runtime confirmation.

Store large result documents under `/workspace`.

## Resource provenance

The Python package and every transitive dependency are pinned by
`WHEELHOUSE.lock.json` for Python 3.11 / Linux ARM64. The rule corpus comes
from the immutable commit behind `capa-rules v9.4.0`; FLIRT signatures come
from the immutable capa v9.4.0 source revision.

The build fails if the pinned source hashes, wheel hashes or expected resource
trees do not match.
