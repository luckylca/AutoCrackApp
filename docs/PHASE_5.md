# Phase 5: DEX Evidence Agent MVP

## Goal

Phase 5 turns the verified extraction and static inventory pipeline into a first end-to-end question-answering workflow:

1. Select an installed package.
2. Extract Base and Split APKs with typed Root tools.
3. Produce the Phase 4 static report.
4. Build a persistent DEX evidence index.
5. Enter a natural-language question.
6. Search local evidence without network access.
7. Optionally send a bounded evidence context to an OpenAI-compatible model.
8. Copy a structured device-test report.

## DEX index

The index is stored in `dex-index.db` inside the private analysis session. It contains one `evidence` table with:

- `kind`: `CLASS`, `METHOD`, `FIELD`, or `STRING`;
- `dex_entry`: APK and DEX entry source;
- `symbol`: readable symbol or bounded string preview;
- `detail`: descriptor and access metadata;
- `search_text`: normalized local-search text.

The indexer uses `com.android.tools.smali:smali-dexlib2:3.0.9`. It reads DEX structures but does not load classes, invoke methods, or execute bytecode.

To bound storage and memory use, strings are trimmed to 2,048 characters and at most 750,000 string records are indexed per session. All defined classes, methods, and fields are indexed unless the parser reports an error.

## Natural-language search

The local planner keeps direct question tokens and expands common Android-analysis intents such as:

- login, authentication, token, and credentials;
- encryption, signing, hashing, and Keystore;
- HTTP, OkHttp, Retrofit, sockets, SSL, and certificate pinning;
- dynamic DEX/native loading;
- Root, anti-debugging, Frida, and Xposed checks;
- WebView bridges;
- databases and local storage;
- privacy-sensitive identifiers and sensors.

The search engine queries the local SQLite evidence database, de-duplicates results, and ranks methods, classes, fields, and strings. A match proves only that a symbol or string exists in the indexed DEX files. It does not prove runtime execution.

## External model boundary

External model use is disabled until the user saves an HTTPS OpenAI-compatible endpoint, model name, and API key. The API configuration is encrypted using AES-GCM with a key stored in Android Keystore.

An external request contains only:

- the question;
- compact static-analysis statistics;
- DEX index counts;
- the local-search summary;
- at most 60 selected evidence items.

The request does not contain APK, DEX, SO, complete string tables, signing-certificate bytes, target-app private files, or Root command output.

The model prompt requires the provider to distinguish confirmed evidence, inference, and unknowns. Model output is still untrusted text and must not be treated as proof.

## Security boundary

Phase 5 does not:

- accept arbitrary Root Shell commands;
- execute target APK components;
- use `DexClassLoader` or `PathClassLoader` on target code;
- load target native libraries;
- inject into target processes;
- bypass authentication or application protections;
- upload complete application artifacts.

## GitHub Actions acceptance

The Phase 5 pull request is accepted only after:

```bash
gradle --no-daemon --stacktrace clean lintDebug testDebugUnitTest assembleDebug
```

passes and the workflow uploads:

```text
AutoCrackApp-phase5-debug.apk
AutoCrackApp-phase5-debug.apk.sha256
AutoCrackApp-phase5-reports
```

## Device test checklist

Use the in-app copyable test card after testing at least one small app and one large multi-DEX app.

1. Confirm APK extraction and static inventory complete.
2. Record DEX index counts, size, and build duration.
3. Ask a question such as `分析登录请求、Token 保存方式和加密实现` and verify evidence appears.
4. Optionally configure an HTTPS test provider and verify only selected evidence is sent.
5. Mark stability and result accuracy, then copy the full report.

For a large application, pay special attention to memory pressure, index time, database size, UI responsiveness, and whether evidence symbols are legible.
