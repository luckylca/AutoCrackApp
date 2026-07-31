# Phase 3: Local APK Static Inventory

## Scope

Phase 3 turns the verified APK copies from Phase 2 into a deterministic local inventory. The user selects an installed package once; AutoCrackApp extracts its Base and Split APKs, verifies them, and analyzes only the private copies.

Implemented:

- Manifest package name, version, SDK levels, and application flags.
- Current signing-certificate and signing-history SHA-256 fingerprints.
- Requested permissions and app-declared permissions.
- Activity, Service, Receiver, and Provider totals and exported-component names.
- ZIP entry counts, compressed and uncompressed sizes, resources, assets, `META-INF`, signing entries, nested APKs, duplicate names, and path-traversal warnings.
- Root-level multidex discovery with DEX magic and version checks.
- `lib/<abi>/*.so` discovery with ELF magic, 32/64-bit class, and machine architecture checks.
- Structured `analysis-report.json` output in the extraction session directory.

## Security boundary

Phase 3 does not:

- Create a `DexClassLoader` or load code from a target APK.
- Call `System.load`, `System.loadLibrary`, or execute target SO files.
- Run target application components.
- Inject into another process.
- Accept arbitrary Shell commands.
- Upload APK files or analysis results.
- Attempt to bypass application encryption, authentication, anti-tamper controls, or access controls.

Android `PackageManager` parses archive metadata. `java.util.zip.ZipFile` reads file entries. DEX and ELF inspection is limited to fixed header bytes and inventory metadata.

## Report location

```text
files/workspaces/<package-name>/session-<timestamp>/analysis-report.json
```

The report contains:

- Manifest summary.
- Signing fingerprints.
- Permission lists.
- Component and exported-component summaries.
- Per-APK archive, DEX, resource, and native-library inventories.
- Any structural warnings found during ZIP, DEX, or ELF inspection.

## Device test checklist

1. Install the Phase 3 APK over the Phase 2 build.
2. Confirm the installed application list still loads.
3. Search for a small application and tap `提取并静态盘点`.
4. Confirm Manifest package/version/SDK information is shown.
5. Confirm at least one signing-certificate SHA-256 fingerprint is shown.
6. Confirm DEX count and DEX version are shown for a normal code-bearing APK.
7. Select an application containing native libraries and confirm ABI, ELF class, and machine architecture appear.
8. Confirm the operation finishes without a crash, ANR, Root error, or empty-report error.
9. Record any package for which Manifest parsing fails or DEX/SO magic warnings appear.

A package may legitimately have no Split APKs, no native libraries, no resources, or no DEX in a particular split. Those are inventory results rather than failures.
