package com.luckylca.autocrack.runtime.shared;

import android.content.Context;
import android.content.res.AssetManager;
import android.content.res.XmlResourceParser;
import android.util.Base64;
import android.util.TypedValue;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

/** /proc, module, dex, runtime asset and bounded memory-dump capabilities. */
public final class MemoryIntrospector {
    private static final int MAX_MAPS = 16_384;
    private static final int MAX_MODULES = 4_096;
    private static final int MAX_DEX = 2_048;
    private static final int MAX_ASSETS = 20_000;
    private static final int MAX_INLINE_BYTES = 4 * 1024 * 1024;
    private static final long MAX_ART_DEX_BYTES = 512L * 1024L * 1024L;
    private static final int MAX_ART_EXPORT_CHUNK_BYTES = 512 * 1024;
    private static final long ART_EXPORT_TTL_MS = 10L * 60L * 1000L;
    private static final int MAX_ART_EXPORT_SESSIONS = 32;
    private static final ConcurrentHashMap<String, ArtDexExportSession> ART_DEX_EXPORTS = new ConcurrentHashMap<>();

    private static final class ArtDexExportSession {
        final long address;
        final long size;
        final String version;
        final String signature;
        final String headerSha256;
        volatile long expiresAtMs;

        ArtDexExportSession(long address, long size, String version, String signature, String headerSha256, long expiresAtMs) {
            this.address = address;
            this.size = size;
            this.version = version;
            this.signature = signature;
            this.headerSha256 = headerSha256;
            this.expiresAtMs = expiresAtMs;
        }
    }
    private MemoryIntrospector() {}

    public static boolean supports(String kind) {
        return Set.of(
                "memory.maps", "memory.modules", "memory.native.modules", "memory.read", "memory.native.probe", "memory.dladdr", "memory.module.dump", "memory.module.file_dump", "memory.elf.info", "memory.elf.symbols", "memory.elf.relocations", "memory.elf.dynamic",
                "memory.dex.list", "memory.dex.art_probe", "memory.dex.art_pointer_probe", "memory.dex.art_dump", "memory.dex.art_export.open", "memory.dex.art_export.chunk", "memory.dex.art_export.close", "memory.dex.info", "memory.dex.apk_index", "memory.dex.strings", "memory.dex.classes", "memory.dex.fields", "memory.dex.methods", "memory.dex.class_data", "memory.dex.scan", "memory.dex.dump", "memory.assets.list", "memory.assets.pull",
                "memory.xml.pull", "memory.xml.block_probe", "memory.xml.binary", "memory.xml.axml_decode", "memory.xml.axml_text", "memory.apk.entries", "memory.apk.pull", "memory.capabilities").contains(kind);
    }

    public static JSONObject execute(Context context, JSONObject request) throws Exception {
        return switch (request.getString("kind")) {
            case "memory.maps" -> maps(request);
            case "memory.modules" -> modules(request);
            case "memory.native.modules" -> nativeModules(context, request);
            case "memory.read" -> memoryRead(context, request);
            case "memory.native.probe" -> NativeBridge.probe(context).put("strategy", "JNI controlled self probe");
            case "memory.dladdr" -> dladdr(context, request);
            case "memory.module.dump" -> moduleDump(context, request);
            case "memory.module.file_dump" -> moduleFileDump(request);
            case "memory.elf.info" -> elfInfo(context, request);
            case "memory.elf.symbols" -> elfSymbols(context, request);
            case "memory.elf.relocations" -> elfRelocations(context, request);
            case "memory.elf.dynamic" -> elfDynamic(context, request);
            case "memory.dex.list" -> dexList(request);
            case "memory.dex.art_probe" -> dexArtProbe(context, request);
            case "memory.dex.art_pointer_probe" -> dexArtPointerProbe(context, request);
            case "memory.dex.art_dump" -> dexArtDump(context, request);
            case "memory.dex.art_export.open" -> dexArtExportOpen(context, request);
            case "memory.dex.art_export.chunk" -> dexArtExportChunk(context, request);
            case "memory.dex.art_export.close" -> dexArtExportClose(request);
            case "memory.dex.info" -> dexInfo(context, request);
            case "memory.dex.apk_index" -> dexApkIndex(context, request);
            case "memory.dex.strings" -> dexStrings(context, request);
            case "memory.dex.classes" -> dexClasses(context, request);
            case "memory.dex.fields" -> dexFields(context, request);
            case "memory.dex.methods" -> dexMethods(context, request);
            case "memory.dex.class_data" -> dexClassData(context, request);
            case "memory.dex.scan" -> dexScan(context, request);
            case "memory.dex.dump" -> dexDump(request);
            case "memory.assets.list" -> assetsList(context, request);
            case "memory.assets.pull" -> assetsPull(context, request);
            case "memory.xml.pull" -> xmlPull(context, request);
            case "memory.xml.block_probe" -> xmlBlockProbe(context, request);
            case "memory.xml.binary" -> xmlBinary(context, request);
            case "memory.xml.axml_decode" -> xmlAxmlDecode(context, request);
            case "memory.xml.axml_text" -> xmlAxmlText(context, request);
            case "memory.apk.entries" -> apkEntries(context, request);
            case "memory.apk.pull" -> apkPull(context, request);
            case "memory.capabilities" -> capabilities(context);
            default -> error("UNSUPPORTED_KIND", request.optString("kind"));
        };
    }

    private static JSONObject maps(JSONObject request) throws Exception {
        int max = clamp(request.optInt("max_maps", 4096), 1, MAX_MAPS);
        String pathContains = request.optString("path_contains", "");
        String permissionsContains = request.optString("permissions_contains", "");
        List<MapEntry> values = readMaps(MAX_MAPS);
        JSONArray out = new JSONArray();
        boolean truncated = false;
        int matched = 0;
        for (MapEntry entry : values) {
            if (!pathContains.isEmpty() && (entry.path == null || !entry.path.contains(pathContains))) continue;
            if (!permissionsContains.isEmpty() && (entry.permissions == null || !entry.permissions.contains(permissionsContains))) continue;
            matched++;
            if (out.length() >= max) { truncated = true; break; }
            out.put(entry.json());
        }
        return ok().put("pid", android.os.Process.myPid()).put("count", out.length()).put("maps", out)
                .put("matched", matched)
                .put("path_contains", pathContains.isEmpty() ? JSONObject.NULL : pathContains)
                .put("permissions_contains", permissionsContains.isEmpty() ? JSONObject.NULL : permissionsContains)
                .put("truncated", truncated);
    }

    private static JSONObject modules(JSONObject request) throws Exception {
        int max = clamp(request.optInt("max_modules", 1024), 1, MAX_MODULES);
        String filter = request.optString("filter", "");
        LinkedHashMap<String, List<MapEntry>> grouped = groupModules(readMaps(MAX_MAPS));
        JSONArray out = new JSONArray();
        for (Map.Entry<String, List<MapEntry>> group : grouped.entrySet()) {
            if (!filter.isEmpty() && !group.getKey().contains(filter)) continue;
            if (out.length() >= max) break;
            out.put(moduleJson(group.getKey(), group.getValue()));
        }
        return ok().put("count", out.length()).put("modules", out)
                .put("truncated", grouped.size() > out.length() && out.length() >= max);
    }

    private static JSONObject nativeModules(Context context, JSONObject request) throws Exception {
        int max = clamp(request.optInt("max_modules", 1024), 1, MAX_MODULES);
        String filter = request.optString("filter", "");
        JSONObject out = NativeBridge.modules(context, max, filter);
        return out.put("strategy", "native dl_iterate_phdr")
                .put("maps_comparison_note", "This is loader PHDR enumeration, not a replacement for /proc/self/maps; anonymous mappings and non-ELF regions remain maps-only.");
    }

    private static JSONObject memoryRead(Context context, JSONObject request) throws Exception {
        long address = parseAddress(request.get("address"));
        int size = clamp(request.getInt("size"), 1, MAX_INLINE_BYTES);
        Throwable nativeError = null;
        if (NativeBridge.ensureLoaded(context)) {
            try {
                byte[] bytes = NativeBridge.readMemory(context, address, size);
                return ok().put("address", hex(address)).put("size", bytes.length)
                        .put("encoding", "base64").put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))
                        .put("strategy", "native process_vm_readv/self-pread");
            } catch (Throwable error) { nativeError = error; }
        }
        try {
            byte[] bytes = readSelfMemory(address, size);
            return ok().put("address", hex(address)).put("size", bytes.length)
                    .put("encoding", "base64").put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))
                    .put("strategy", "/proc/self/mem java fallback")
                    .put("native_error", nativeError == null ? JSONObject.NULL : nativeError.toString());
        } catch (Throwable error) {
            return unsupported("memory.read", "native/java self memory read denied: native=" + nativeError + ", java=" + error,
                    new JSONArray().put("native process_vm_readv").put("native pread /proc/self/mem").put("java /proc/self/mem"));
        }
    }

    private static JSONObject dladdr(Context context, JSONObject request) throws Exception {
        long address = parseAddress(request.get("address"));
        JSONObject result = NativeBridge.dladdr(context, address);
        if (result.optBoolean("ok", false)) {
            return result.put("address", hex(address)).put("strategy", "native dladdr");
        }
        MapEntry containing = findMapContaining(address);
        if (containing != null) {
            return ok().put("address", hex(address))
                    .put("symbol_resolved", false)
                    .put("native_reason", result.optString("reason", "native dladdr failed"))
                    .put("file", containing.path == null || containing.path.isEmpty() ? JSONObject.NULL : containing.path)
                    .put("base", hex(containing.start))
                    .put("offset_in_mapping", address - containing.start)
                    .put("mapping", containing.json())
                    .put("strategy", "native dladdr with /proc/self/maps fallback");
        }
        return result.put("address", hex(address)).put("strategy", "native dladdr");
    }

    private static JSONObject moduleDump(Context context, JSONObject request) throws Exception {
        String path = request.optString("path", "");
        if (path.isBlank()) return error("PATH_REQUIRED", "path is required");
        List<MapEntry> matching = new ArrayList<>();
        for (MapEntry entry : readMaps(MAX_MAPS)) if (path.equals(entry.path)) matching.add(entry);
        if (matching.isEmpty()) return error("MODULE_NOT_FOUND", path);
        matching.sort(Comparator.comparingLong(MapEntry::start));
        int maxBytes = clamp(request.optInt("max_bytes", MAX_INLINE_BYTES), 1, MAX_INLINE_BYTES);
        JSONArray segments = new JSONArray(); int total = 0; boolean truncated = false;
        for (MapEntry entry : matching) {
            int wanted = (int)Math.min(entry.size(), maxBytes - total);
            if (wanted <= 0) { truncated = true; break; }
            try {
                byte[] bytes = readMappedMemory(context, entry.start, wanted);
                segments.put(new JSONObject().put("start", hex(entry.start)).put("end", hex(entry.end))
                        .put("permissions", entry.permissions).put("offset", hex(entry.offset))
                        .put("size", bytes.length).put("encoding", "base64")
                        .put("data", Base64.encodeToString(bytes, Base64.NO_WRAP)));
                total += bytes.length; if (bytes.length < entry.size()) truncated = true;
            } catch (Throwable error) {
                segments.put(new JSONObject().put("start", hex(entry.start)).put("end", hex(entry.end))
                        .put("permissions", entry.permissions).put("error", error.toString()));
            }
        }
        return ok().put("path", path).put("segment_count", segments.length()).put("segments", segments)
                .put("total_bytes", total).put("truncated", truncated)
                .put("note", "Segments remain separate; non-contiguous mappings are never concatenated into one fake address range.");
    }


    private static JSONObject moduleFileDump(JSONObject request) throws Exception {
        String path = request.optString("path", "");
        if (path.isBlank() || !path.startsWith("/")) return error("ABSOLUTE_PATH_REQUIRED", "absolute module path is required");
        File file = new File(path);
        if (!file.isFile() || !file.canRead()) return error("MODULE_FILE_NOT_READABLE", path);
        int max = clamp(request.optInt("max_bytes", MAX_INLINE_BYTES), 1, MAX_INLINE_BYTES);
        if (file.length() > max) return error("MODULE_FILE_TOO_LARGE", "file exceeds inline max_bytes: " + file.length());
        byte[] bytes = readFile(file, max);
        return ok().put("path", path).put("size", bytes.length).put("sha256", sha256(bytes))
                .put("encoding", "base64").put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))
                .put("strategy", "file-backed module copy; not an in-memory relocated image dump");
    }

    private static JSONObject elfInfo(Context context, JSONObject request) throws Exception {
        int max = clamp(request.optInt("max_bytes", MAX_INLINE_BYTES), 4096, MAX_INLINE_BYTES);
        ElfBytes loaded;
        try { loaded = loadElfBytes(context, request, max); }
        catch (IllegalArgumentException error) { return error("ELF_INPUT_ERROR", error.getMessage()); }
        JSONObject parsed = parseElfInfo(loaded.bytes);
        return ok().put("source", loaded.source).put("bytes_read", loaded.bytes.length).put("truncated", loaded.truncated)
                .put("sha256_prefix", sha256(loaded.bytes)).put("elf", parsed)
                .put("strategy", "bounded ELF header/program-header/note parsing; no native code execution");
    }

    private static JSONObject elfSymbols(Context context, JSONObject request) throws Exception {
        int max = clamp(request.optInt("max_bytes", MAX_INLINE_BYTES), 4096, MAX_INLINE_BYTES);
        int maxSymbols = clamp(request.optInt("max_symbols", 1024), 1, 20_000);
        boolean includeSymtab = request.optBoolean("include_symtab", false);
        String filter = request.optString("filter", "");
        ElfBytes loaded = loadElfBytes(context, request, max);
        JSONObject parsed = parseElfSymbols(loaded.bytes, maxSymbols, includeSymtab, filter);
        return ok().put("source", loaded.source).put("bytes_read", loaded.bytes.length).put("truncated", loaded.truncated)
                .put("sha256_prefix", sha256(loaded.bytes)).put("symbols", parsed)
                .put("filter", filter.isEmpty() ? JSONObject.NULL : filter)
                .put("strategy", "bounded ELF dynsym/symtab parsing; no native code execution");
    }

    private static JSONObject elfDynamic(Context context, JSONObject request) throws Exception {
        int max = clamp(request.optInt("max_bytes", MAX_INLINE_BYTES), 4096, MAX_INLINE_BYTES);
        int maxEntries = clamp(request.optInt("max_entries", 512), 1, 10_000);
        ElfBytes loaded;
        try { loaded = loadElfBytes(context, request, max); }
        catch (IllegalArgumentException error) { return error("ELF_INPUT_ERROR", error.getMessage()); }
        JSONObject parsed = parseElfDynamic(loaded.bytes, maxEntries);
        return ok().put("source", loaded.source).put("bytes_read", loaded.bytes.length).put("truncated", loaded.truncated)
                .put("sha256_prefix", sha256(loaded.bytes)).put("dynamic", parsed)
                .put("strategy", "bounded ELF dynamic table parsing; no native code execution");
    }

    private static JSONObject elfRelocations(Context context, JSONObject request) throws Exception {
        int max = clamp(request.optInt("max_bytes", MAX_INLINE_BYTES), 4096, MAX_INLINE_BYTES);
        int maxRelocations = clamp(request.optInt("max_relocations", 1024), 1, 50_000);
        String filter = request.optString("filter", "");
        ElfBytes loaded;
        try { loaded = loadElfBytes(context, request, max); }
        catch (IllegalArgumentException error) { return error("ELF_INPUT_ERROR", error.getMessage()); }
        JSONObject parsed = parseElfRelocations(loaded.bytes, maxRelocations, filter);
        return ok().put("source", loaded.source).put("bytes_read", loaded.bytes.length).put("truncated", loaded.truncated)
                .put("sha256_prefix", sha256(loaded.bytes)).put("relocations", parsed)
                .put("filter", filter.isEmpty() ? JSONObject.NULL : filter)
                .put("strategy", "bounded ELF REL/RELA parsing; no relocation application or native code execution");
    }

    private static ElfBytes loadElfBytes(Context context, JSONObject request, int max) throws Exception {
        String path = request.optString("path", "").trim();
        String entry = request.optString("entry", "").trim();
        byte[] bytes;
        JSONObject source = new JSONObject();
        boolean truncated = false;
        if (!entry.isBlank()) {
            entry = normalizeZipEntry(entry);
            JSONObject pulled = pullApkEntryAnySource(apkContext(context, request), entry, max);
            if (!pulled.optBoolean("ok", false)) throw new IllegalArgumentException(pulled.toString());
            bytes = Base64.decode(pulled.getString("data"), Base64.NO_WRAP);
            source.put("kind", "apk_entry").put("apk_path", pulled.optString("apk_path", "")).put("entry", entry).put("source", pulled.optString("source", ""));
            truncated = pulled.optInt("size", bytes.length) >= max;
        } else if (path.contains("!/")) {
            int bang = path.indexOf("!/");
            String apkPath = path.substring(0, bang);
            String zipEntry = normalizeZipEntry(path.substring(bang + 2));
            bytes = readZipEntry(new File(apkPath), zipEntry, max);
            source.put("kind", "apk_embedded_path").put("apk_path", apkPath).put("entry", zipEntry);
            truncated = bytes.length >= max;
        } else {
            if (path.isBlank() || !path.startsWith("/")) throw new IllegalArgumentException("absolute file path, APK embedded path, or entry is required");
            File file = new File(path);
            if (!file.isFile() || !file.canRead()) throw new IllegalArgumentException("ELF file not readable: " + path);
            bytes = readFile(file, max);
            source.put("kind", "file").put("path", path).put("file_size", file.length());
            truncated = file.length() > bytes.length;
        }
        return new ElfBytes(bytes, source, truncated);
    }

    private static JSONObject dexInfo(Context context, JSONObject request) throws Exception {
        int max = clamp(request.optInt("max_bytes", MAX_INLINE_BYTES), 4096, MAX_INLINE_BYTES);
        DexBytes loaded;
        try { loaded = loadDexBytes(context, request, max); }
        catch (IllegalArgumentException error) { return error("DEX_INPUT_ERROR", error.getMessage()); }
        JSONObject parsed = parseDexInfo(loaded.bytes);
        return ok().put("source", loaded.source).put("bytes_read", loaded.bytes.length).put("truncated", loaded.truncated)
                .put("sha256_prefix", sha256(loaded.bytes)).put("dex", parsed)
                .put("strategy", "bounded file/APK DEX header and map-list parsing; no ART memory reconstruction");
    }

    private static DexBytes loadDexBytes(Context context, JSONObject request, int max) throws Exception {
        String path = request.optString("path", "").trim();
        String entry = request.optString("entry", "").trim();
        byte[] bytes;
        JSONObject source = new JSONObject();
        boolean truncated = false;
        if (!entry.isBlank()) {
            entry = normalizeZipEntry(entry);
            JSONObject pulled = pullApkEntryAnySource(apkContext(context, request), entry, max);
            if (!pulled.optBoolean("ok", false)) throw new IllegalArgumentException(pulled.toString());
            bytes = Base64.decode(pulled.getString("data"), Base64.NO_WRAP);
            source.put("kind", "apk_entry").put("apk_path", pulled.optString("apk_path", "")).put("entry", entry).put("source", pulled.optString("source", ""));
            truncated = pulled.optInt("size", bytes.length) >= max;
        } else if (path.contains("!/")) {
            int bang = path.indexOf("!/");
            String apkPath = path.substring(0, bang);
            String zipEntry = normalizeZipEntry(path.substring(bang + 2));
            bytes = readZipEntry(new File(apkPath), zipEntry, max);
            source.put("kind", "apk_embedded_path").put("apk_path", apkPath).put("entry", zipEntry);
            truncated = bytes.length >= max;
        } else {
            if (path.isBlank() || !path.startsWith("/")) throw new IllegalArgumentException("absolute DEX path, APK embedded path, or entry is required");
            File file = new File(path);
            if (!file.isFile() || !file.canRead()) throw new IllegalArgumentException("DEX file not readable: " + path);
            bytes = readFile(file, max);
            source.put("kind", "file").put("path", path).put("file_size", file.length());
            truncated = file.length() > bytes.length;
        }
        return new DexBytes(bytes, source, truncated);
    }

    private static JSONObject dexApkIndex(Context context, JSONObject request) throws Exception {
        int maxDex = clamp(request.optInt("max_dex", 32), 1, 256);
        int maxBytes = clamp(request.optInt("max_bytes", MAX_INLINE_BYTES), 4096, MAX_INLINE_BYTES);
        Context apkCtx = apkContext(context, request);
        List<ApkSource> sources = apkSources(apkCtx);
        JSONArray dexFiles = new JSONArray();
        boolean dexTruncated = false;
        for (ApkSource src : sources) {
            String label = src.label;
            String path = src.path;
            try (ZipFile zip = new ZipFile(path)) {
                ArrayList<String> names = new ArrayList<>();
                java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (!entry.isDirectory() && name.matches("classes(\\d*)?\\.dex")) names.add(name);
                }
                names.sort(Comparator.comparingInt(MemoryIntrospector::dexEntryOrder));
                for (String name : names) {
                    if (dexFiles.length() >= maxDex) { dexTruncated = true; break; }
                    ZipEntry entry = zip.getEntry(name);
                    if (entry == null || entry.isDirectory()) continue;
                    JSONObject item = new JSONObject().put("source", label).put("apk_path", path).put("entry", name)
                            .put("compressed_size", entry.getCompressedSize()).put("entry_size", entry.getSize());
                    try (InputStream in = zip.getInputStream(entry)) {
                        byte[] bytes = readLimited(in, maxBytes + 1);
                        boolean truncated = bytes.length > maxBytes;
                        if (truncated) bytes = java.util.Arrays.copyOf(bytes, maxBytes);
                        JSONObject info = parseDexInfo(bytes);
                        item.put("bytes_read", bytes.length).put("truncated", truncated).put("sha256_prefix", sha256(bytes)).put("dex", info);
                    } catch (Throwable error) {
                        item.put("ok", false).put("error", error.getClass().getName() + ": " + error.getMessage());
                    }
                    dexFiles.put(item);
                }
            }
            if (dexTruncated) break;
        }
        return ok().put("package", apkCtx.getPackageName()).put("source_count", sources.size())
                .put("dex_count", dexFiles.length()).put("dex_files", dexFiles).put("truncated", dexTruncated)
                .put("strategy", "APK classes*.dex index with bounded header/map parsing; no ART memory reconstruction");
    }

    private static JSONObject dexStrings(Context context, JSONObject request) throws Exception {
        int maxBytes = clamp(request.optInt("max_bytes", MAX_INLINE_BYTES), 4096, MAX_INLINE_BYTES);
        int maxStrings = clamp(request.optInt("max_strings", 1024), 1, 100_000);
        String filter = request.optString("filter", "");
        DexBytes loaded;
        try { loaded = loadDexBytes(context, request, maxBytes); }
        catch (IllegalArgumentException error) { return error("DEX_INPUT_ERROR", error.getMessage()); }
        JSONObject parsed = parseDexStrings(loaded.bytes, maxStrings, filter);
        return ok().put("source", loaded.source).put("bytes_read", loaded.bytes.length).put("truncated", loaded.truncated)
                .put("sha256_prefix", sha256(loaded.bytes)).put("strings", parsed)
                .put("filter", filter.isEmpty() ? JSONObject.NULL : filter)
                .put("strategy", "bounded file/APK DEX string table parsing; no ART memory reconstruction");
    }

    private static JSONObject dexClasses(Context context, JSONObject request) throws Exception {
        int maxBytes = clamp(request.optInt("max_bytes", MAX_INLINE_BYTES), 4096, MAX_INLINE_BYTES);
        int maxClasses = clamp(request.optInt("max_classes", 2048), 1, 100_000);
        String filter = request.optString("filter", "");
        DexBytes loaded;
        try { loaded = loadDexBytes(context, request, maxBytes); }
        catch (IllegalArgumentException error) { return error("DEX_INPUT_ERROR", error.getMessage()); }
        JSONObject parsed = parseDexClasses(loaded.bytes, maxClasses, filter);
        return ok().put("source", loaded.source).put("bytes_read", loaded.bytes.length).put("truncated", loaded.truncated)
                .put("sha256_prefix", sha256(loaded.bytes)).put("classes", parsed)
                .put("filter", filter.isEmpty() ? JSONObject.NULL : filter)
                .put("strategy", "bounded file/APK DEX class_def descriptor parsing; no ART memory reconstruction");
    }

    private static JSONObject dexFields(Context context, JSONObject request) throws Exception {
        int maxBytes = clamp(request.optInt("max_bytes", MAX_INLINE_BYTES), 4096, MAX_INLINE_BYTES);
        int maxFields = clamp(request.optInt("max_fields", 4096), 1, 200_000);
        String filter = request.optString("filter", "");
        DexBytes loaded;
        try { loaded = loadDexBytes(context, request, maxBytes); }
        catch (IllegalArgumentException error) { return error("DEX_INPUT_ERROR", error.getMessage()); }
        JSONObject parsed = parseDexFields(loaded.bytes, maxFields, filter);
        return ok().put("source", loaded.source).put("bytes_read", loaded.bytes.length).put("truncated", loaded.truncated)
                .put("sha256_prefix", sha256(loaded.bytes)).put("fields", parsed)
                .put("filter", filter.isEmpty() ? JSONObject.NULL : filter)
                .put("strategy", "bounded file/APK DEX field_id parsing; no ART memory reconstruction");
    }

    private static JSONObject dexMethods(Context context, JSONObject request) throws Exception {
        int maxBytes = clamp(request.optInt("max_bytes", MAX_INLINE_BYTES), 4096, MAX_INLINE_BYTES);
        int maxMethods = clamp(request.optInt("max_methods", 4096), 1, 300_000);
        String filter = request.optString("filter", "");
        DexBytes loaded;
        try { loaded = loadDexBytes(context, request, maxBytes); }
        catch (IllegalArgumentException error) { return error("DEX_INPUT_ERROR", error.getMessage()); }
        JSONObject parsed = parseDexMethods(loaded.bytes, maxMethods, filter);
        return ok().put("source", loaded.source).put("bytes_read", loaded.bytes.length).put("truncated", loaded.truncated)
                .put("sha256_prefix", sha256(loaded.bytes)).put("methods", parsed)
                .put("filter", filter.isEmpty() ? JSONObject.NULL : filter)
                .put("strategy", "bounded file/APK DEX method_id/proto parsing; no ART memory reconstruction");
    }

    private static JSONObject dexClassData(Context context, JSONObject request) throws Exception {
        int maxBytes = clamp(request.optInt("max_bytes", MAX_INLINE_BYTES), 4096, MAX_INLINE_BYTES);
        int maxClasses = clamp(request.optInt("max_classes", 256), 1, 20_000);
        int maxMembers = clamp(request.optInt("max_members", 256), 1, 50_000);
        String filter = request.optString("filter", "");
        DexBytes loaded;
        try { loaded = loadDexBytes(context, request, maxBytes); }
        catch (IllegalArgumentException error) { return error("DEX_INPUT_ERROR", error.getMessage()); }
        JSONObject parsed = parseDexClassData(loaded.bytes, maxClasses, maxMembers, filter);
        return ok().put("source", loaded.source).put("bytes_read", loaded.bytes.length).put("truncated", loaded.truncated)
                .put("sha256_prefix", sha256(loaded.bytes)).put("class_data", parsed)
                .put("filter", filter.isEmpty() ? JSONObject.NULL : filter)
                .put("strategy", "bounded file/APK DEX class_data and code_item metadata parsing; no bytecode disassembly and no ART memory reconstruction");
    }

    private static JSONObject dexList(JSONObject request) throws Exception {
        JSONArray loaders = new JSONArray(); int dexCount = 0;
        String selected = request.optString("loader", null);
        for (ClassLoader loader : ClassLoaderRegistry.get().snapshot()) {
            String handle = ObjectRegistry.get().put(loader, false, "classloader");
            if (selected != null && !selected.isBlank() && !selected.equals(handle)) continue;
            JSONArray dex = RuntimeIntrospector.dexElements(loader, MAX_DEX, request.optBoolean("include_class_count", false));
            for (int i=0;i<dex.length();i++) {
                JSONObject item=dex.getJSONObject(i); String dexHandle=item.optString("dex_handle", null);
                if (dexHandle != null) {
                    Object dexObject=ObjectRegistry.get().get(dexHandle);
                    JSONObject cookie=cookieInfo(dexObject); if(cookie!=null)item.put("cookie",cookie);
                }
            }
            dexCount += dex.length();
            loaders.put(new JSONObject().put("loader_handle", handle).put("loader_class", loader.getClass().getName()).put("dex", dex));
        }
        return ok().put("loader_count", loaders.length()).put("dex_count", dexCount).put("loaders", loaders)
                .put("memory_dump_strategy", "file-backed dex can be copied; ART cookie is exposed for research but native pointer reconstruction is intentionally version-gated");
    }

    private static JSONObject dexArtProbe(Context context, JSONObject request) throws Exception {
        int max = clamp(request.optInt("max_dex", 256), 1, MAX_DEX);
        boolean includeClassCount = request.optBoolean("include_class_count", false);
        String selected = request.optString("loader", "");
        JSONArray records = new JSONArray();
        int loaderCount = 0;
        int dexCount = 0;
        int fileBacked = 0;
        int cookieFieldCount = 0;
        int cookieValueCount = 0;
        boolean truncated = false;
        ArrayList<ClassLoader> probeLoaders = new ArrayList<>(ClassLoaderRegistry.get().snapshot());
        boolean includeContextLoader = request.optBoolean("include_context_loader", true);
        if (includeContextLoader && context != null && context.getClassLoader() != null && !probeLoaders.contains(context.getClassLoader())) {
            probeLoaders.add(context.getClassLoader());
        }
        for (ClassLoader loader : probeLoaders) {
            String loaderHandle = ObjectRegistry.get().put(loader, false, "classloader");
            if (!selected.isBlank() && !selected.equals(loaderHandle)) continue;
            loaderCount++;
            JSONArray dex = RuntimeIntrospector.dexElements(loader, max, includeClassCount);
            for (int i = 0; i < dex.length(); i++) {
                if (records.length() >= max) { truncated = true; break; }
                JSONObject item = dex.getJSONObject(i);
                JSONObject out = new JSONObject()
                        .put("loader_handle", loaderHandle)
                        .put("loader_class", loader.getClass().getName())
                        .put("dex", item);
                String name = item.optString("name", "");
                if (!name.isBlank() && new File(name).isFile()) {
                    fileBacked++;
                    out.put("file_backed", true).put("file_length", new File(name).length());
                } else {
                    out.put("file_backed", false);
                }
                String dexHandle = item.optString("dex_handle", "");
                Object dexObject = dexHandle.isBlank() ? null : ObjectRegistry.get().get(dexHandle);
                JSONObject cookie = cookieInfo(dexObject);
                if (cookie != null) {
                    out.put("cookie", cookie);
                    JSONObject analysis = analyzeCookie(cookie);
                    out.put("cookie_analysis", analysis);
                    cookieFieldCount += analysis.optInt("field_count", 0);
                    cookieValueCount += analysis.optInt("value_count", 0);
                } else {
                    out.put("cookie", JSONObject.NULL)
                            .put("cookie_analysis", new JSONObject().put("field_count", 0).put("value_count", 0));
                }
                records.put(out);
                dexCount++;
            }
            if (truncated) break;
        }
        return ok().put("api_level", android.os.Build.VERSION.SDK_INT)
                .put("loader_count", loaderCount)
                .put("dex_count", dexCount)
                .put("file_backed_count", fileBacked)
                .put("cookie_field_count", cookieFieldCount)
                .put("cookie_value_count", cookieValueCount)
                .put("records", records)
                .put("truncated", truncated)
                .put("include_context_loader", includeContextLoader)
                .put("strategy", "Java DexPathList/DexFile reflection + ART cookie shape probe")
                .put("art_memory_reconstruction", false)
                .put("warning", "This exposes ART cookie shape for research. It does not reconstruct DexFile memory by native ART offsets.");
    }


    private static JSONObject dexArtPointerProbe(Context context, JSONObject request) throws Exception {
        int maxDex = clamp(request.optInt("max_dex", 256), 1, MAX_DEX);
        int maxPointers = clamp(request.optInt("max_pointers", 64), 1, 1024);
        int windowBytes = clamp(request.optInt("window_bytes", 65536), 0x70, MAX_INLINE_BYTES);
        int scanBackBytes = clamp(request.optInt("scan_back_bytes", 4096), 0, MAX_INLINE_BYTES);
        boolean includeContextLoader = request.optBoolean("include_context_loader", true);
        boolean includeClassCount = request.optBoolean("include_class_count", false);
        boolean includeWords = request.optBoolean("include_words", false);
        boolean tryLayoutDexTables = request.optBoolean("try_layout_dex_tables", false);
        boolean tryLayoutDexHeader = request.optBoolean("try_layout_dex_header", false) || tryLayoutDexTables;
        int wordCount = clamp(request.optInt("word_count", 32), 1, 256);
        int layoutTableLimit = clamp(request.optInt("layout_table_limit", 32), 1, 256);
        int layoutMemberLimit = clamp(request.optInt("layout_member_limit", 16), 1, 256);
        String layoutFilter = request.optString("layout_filter", "");
        String selected = request.optString("loader", "");
        ArrayList<ClassLoader> probeLoaders = new ArrayList<>(ClassLoaderRegistry.get().snapshot());
        if (includeContextLoader && context != null && context.getClassLoader() != null && !probeLoaders.contains(context.getClassLoader())) {
            probeLoaders.add(context.getClassLoader());
        }
        LinkedHashMap<Long, JSONArray> pointers = new LinkedHashMap<>();
        JSONArray dexRecords = new JSONArray();
        int loaderCount = 0;
        int dexCount = 0;
        boolean pointerTruncated = false;
        for (ClassLoader loader : probeLoaders) {
            String loaderHandle = ObjectRegistry.get().put(loader, false, "classloader");
            if (!selected.isBlank() && !selected.equals(loaderHandle)) continue;
            loaderCount++;
            JSONArray dex = RuntimeIntrospector.dexElements(loader, maxDex, includeClassCount);
            for (int i = 0; i < dex.length(); i++) {
                if (dexCount >= maxDex) break;
                JSONObject item = dex.getJSONObject(i);
                dexRecords.put(new JSONObject().put("loader_handle", loaderHandle).put("loader_class", loader.getClass().getName()).put("dex", item));
                String dexHandle = item.optString("dex_handle", "");
                Object dexObject = dexHandle.isBlank() ? null : ObjectRegistry.get().get(dexHandle);
                if (dexObject != null) {
                    addCookiePointerField(pointers, field(dexObject, "mCookie"), loaderHandle, item.optString("name", ""), i, "mCookie", maxPointers);
                    addCookiePointerField(pointers, field(dexObject, "mInternalCookie"), loaderHandle, item.optString("name", ""), i, "mInternalCookie", maxPointers);
                    if (pointers.size() >= maxPointers) pointerTruncated = true;
                }
                dexCount++;
            }
        }
        JSONArray pointerRecords = new JSONArray();
        int readablePointers = 0;
        int dexCandidateCount = 0;
        int layoutDexCandidateCount = 0;
        int headerReconstructionPointers = 0;
        int tableReconstructionPointers = 0;
        for (Map.Entry<Long, JSONArray> e : pointers.entrySet()) {
            if (pointerRecords.length() >= maxPointers) { pointerTruncated = true; break; }
            JSONObject record = probeArtCookiePointer(context, e.getKey(), e.getValue(), windowBytes, scanBackBytes, includeWords, wordCount,
                    tryLayoutDexHeader, tryLayoutDexTables, layoutTableLimit, layoutMemberLimit, layoutFilter);
            if (record.optBoolean("readable", false)) readablePointers++;
            dexCandidateCount += record.optJSONArray("dex_candidates") == null ? 0 : record.optJSONArray("dex_candidates").length();
            JSONArray layoutCandidates = record.optJSONArray("layout_dex_candidates");
            layoutDexCandidateCount += layoutCandidates == null ? 0 : layoutCandidates.length();
            if (record.optBoolean("art_memory_header_reconstruction", false)) headerReconstructionPointers++;
            if (record.optBoolean("art_memory_table_reconstruction", false)) tableReconstructionPointers++;
            pointerRecords.put(record);
        }
        return ok().put("api_level", android.os.Build.VERSION.SDK_INT)
                .put("pid", android.os.Process.myPid())
                .put("loader_count", loaderCount)
                .put("dex_count", dexCount)
                .put("dex_records", dexRecords)
                .put("pointer_count", pointerRecords.length())
                .put("readable_pointer_count", readablePointers)
                .put("dex_candidate_count", dexCandidateCount)
                .put("layout_dex_candidate_count", layoutDexCandidateCount)
                .put("header_reconstruction_pointer_count", headerReconstructionPointers)
                .put("art_memory_header_reconstruction", headerReconstructionPointers > 0)
                .put("table_reconstruction_pointer_count", tableReconstructionPointers)
                .put("art_memory_table_reconstruction", tableReconstructionPointers > 0)
                .put("pointers", pointerRecords)
                .put("window_bytes", windowBytes)
                .put("scan_back_bytes", scanBackBytes)
                .put("include_words", includeWords)
                .put("word_count", includeWords ? wordCount : 0)
                .put("try_layout_dex_header", tryLayoutDexHeader)
                .put("try_layout_dex_tables", tryLayoutDexTables)
                .put("layout_table_limit", tryLayoutDexTables ? layoutTableLimit : 0)
                .put("layout_member_limit", tryLayoutDexTables ? layoutMemberLimit : 0)
                .put("layout_filter", layoutFilter.isBlank() ? JSONObject.NULL : layoutFilter)
                .put("truncated", pointerTruncated)
                .put("include_context_loader", includeContextLoader)
                .put("strategy", "ART DexFile cookie pointer collection + /proc/self/maps resolution + bounded DEX magic/header neighborhood scan")
                .put("art_memory_reconstruction", false)
                .put("warning", "Default mode is a pointer-shape/neighborhood probe. try_layout_dex_header may reconstruct validated DEX header/map metadata; try_layout_dex_tables additionally parses bounded strings/classes/fields/methods/class_data directly from that validated in-memory candidate. Full raw DEX byte export/reconstruction remains unsupported.");
    }


    private static JSONObject dexArtExportOpen(Context context, JSONObject request) throws Exception {
        pruneArtDexExports();
        if (ART_DEX_EXPORTS.size() >= MAX_ART_EXPORT_SESSIONS) {
            return error("ART_EXPORT_SESSION_LIMIT", "too many active ART DEX export sessions");
        }
        JSONObject probeRequest = new JSONObject()
                .put("kind", "memory.dex.art_pointer_probe")
                .put("loader", request.optString("loader", ""))
                .put("max_dex", clamp(request.optInt("max_dex", 256), 1, MAX_DEX))
                .put("max_pointers", clamp(request.optInt("max_pointers", 64), 1, 1024))
                .put("window_bytes", clamp(request.optInt("window_bytes", 4096), 0x70, MAX_INLINE_BYTES))
                .put("scan_back_bytes", clamp(request.optInt("scan_back_bytes", 512), 0, MAX_INLINE_BYTES))
                .put("include_context_loader", request.optBoolean("include_context_loader", true))
                .put("include_class_count", false)
                .put("include_words", true)
                .put("word_count", clamp(request.optInt("word_count", 16), 1, 256))
                .put("try_layout_dex_header", false)
                .put("try_layout_dex_tables", false);
        JSONObject probe = dexArtPointerProbe(context, probeRequest);
        JSONArray candidates = artExportCandidates(context, probe);
        if (candidates.length() == 0) {
            return unsupported("memory.dex.art_export.open", "no validated ART DEX export candidate was found",
                    new JSONArray().put("candidate discovery requires a readable ART data_begin pointer and matching file_size word")
                            .put("maximum export candidate size is " + MAX_ART_DEX_BYTES + " bytes"));
        }
        int selected = request.optInt("candidate_index", 0);
        if (selected < 0 || selected >= candidates.length()) {
            return error("ART_DEX_CANDIDATE_INDEX", "candidate_index must be between 0 and " + (candidates.length() - 1));
        }
        JSONObject candidate = candidates.getJSONObject(selected);
        long address = parseAddress(candidate.getString("data_address"));
        long size = candidate.getLong("size");
        JSONObject dex = candidate.getJSONObject("dex_header");
        String token = "adex_" + UUID.randomUUID().toString().replace("-", "");
        long expiresAt = System.currentTimeMillis() + ART_EXPORT_TTL_MS;
        ART_DEX_EXPORTS.put(token, new ArtDexExportSession(address, size, dex.optString("version", ""),
                dex.optString("signature", ""), candidate.optString("header_sha256", ""), expiresAt));
        return ok().put("token", token)
                .put("candidate_index", selected)
                .put("candidate_count", candidates.length())
                .put("data_address", hex(address))
                .put("size", size)
                .put("dex_header", dex)
                .put("header_sha256", candidate.optString("header_sha256", ""))
                .put("chunk_max_bytes", MAX_ART_EXPORT_CHUNK_BYTES)
                .put("expires_at_ms", expiresAt)
                .put("candidate", candidate)
                .put("chunked_raw_reconstruction", true)
                .put("strategy", "validated ART DEX export session bound to data_begin/file_size/header invariants");
    }

    private static JSONObject dexArtExportChunk(Context context, JSONObject request) throws Exception {
        pruneArtDexExports();
        String token = request.optString("token", "");
        ArtDexExportSession session = ART_DEX_EXPORTS.get(token);
        if (session == null) return error("ART_EXPORT_SESSION_MISSING", token);
        long now = System.currentTimeMillis();
        if (session.expiresAtMs < now) {
            ART_DEX_EXPORTS.remove(token);
            return error("ART_EXPORT_SESSION_EXPIRED", token);
        }
        byte[] header = readMappedMemory(context, session.address, 0x70);
        if (header.length < 0x70 || !looksLikeDexHeader(header, 0)) {
            ART_DEX_EXPORTS.remove(token);
            return error("ART_EXPORT_REVALIDATION_FAILED", "DEX header is no longer readable");
        }
        JSONObject dex = parseDexInfo(header);
        if (dex.optLong("file_size", -1L) != session.size
                || !session.version.equals(dex.optString("version", ""))
                || !session.signature.equals(dex.optString("signature", ""))
                || !session.headerSha256.equals(sha256(header))) {
            ART_DEX_EXPORTS.remove(token);
            return error("ART_EXPORT_REVALIDATION_FAILED", "DEX header/signature changed after export session open");
        }
        long offset = request.optLong("offset", 0L);
        if (offset < 0L || offset > session.size) return error("ART_EXPORT_OFFSET", "offset is outside candidate range");
        int requested = clamp(request.optInt("size", MAX_ART_EXPORT_CHUNK_BYTES), 1, MAX_ART_EXPORT_CHUNK_BYTES);
        if (offset == session.size) {
            session.expiresAtMs = now + ART_EXPORT_TTL_MS;
            return ok().put("token", token).put("offset", offset).put("size", 0)
                    .put("total_size", session.size).put("eof", true).put("encoding", "base64").put("data", "");
        }
        int wanted = (int)Math.min((long)requested, session.size - offset);
        byte[] bytes = readMappedMemory(context, session.address + offset, wanted);
        if (bytes.length != wanted) return error("ART_EXPORT_SHORT_READ", "expected " + wanted + " bytes, got " + bytes.length);
        session.expiresAtMs = now + ART_EXPORT_TTL_MS;
        return ok().put("token", token)
                .put("offset", offset)
                .put("size", bytes.length)
                .put("total_size", session.size)
                .put("eof", offset + bytes.length >= session.size)
                .put("chunk_sha256", sha256(bytes))
                .put("encoding", "base64")
                .put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))
                .put("expires_at_ms", session.expiresAtMs);
    }

    private static JSONObject dexArtExportClose(JSONObject request) throws Exception {
        pruneArtDexExports();
        String token = request.optString("token", "");
        ArtDexExportSession removed = ART_DEX_EXPORTS.remove(token);
        return ok().put("token", token).put("closed", removed != null).put("active_sessions", ART_DEX_EXPORTS.size());
    }

    private static JSONArray artExportCandidates(Context context, JSONObject probe) throws Exception {
        LinkedHashMap<String, JSONObject> unique = new LinkedHashMap<>();
        JSONArray pointers = probe.optJSONArray("pointers");
        if (pointers == null) return new JSONArray();
        List<MapEntry> maps = readMaps(MAX_MAPS);
        for (int p = 0; p < pointers.length() && unique.size() < 64; p++) {
            JSONObject pointer = pointers.optJSONObject(p);
            if (pointer == null) continue;
            JSONArray words = pointer.optJSONArray("words");
            if (words == null) continue;
            ArrayList<JSONObject> pointerWords = new ArrayList<>();
            ArrayList<JSONObject> sizeWords = new ArrayList<>();
            for (int i = 0; i < words.length(); i++) {
                JSONObject word = words.optJSONObject(i);
                if (word == null) continue;
                JSONObject pointsTo = word.optJSONObject("points_to");
                Long numeric = cookiePointerValue(word.optString("value", ""));
                if (pointsTo != null && numeric != null && pointsTo.optString("permissions", "").startsWith("r")) pointerWords.add(word);
                if (numeric != null && numeric >= 0x70L && numeric <= MAX_ART_DEX_BYTES) sizeWords.add(word);
            }
            for (JSONObject ptrWord : pointerWords) {
                Long rawAddress = cookiePointerValue(ptrWord.optString("value", ""));
                if (rawAddress == null || rawAddress == 0L) continue;
                long address = rawAddress;
                if (findMapContaining(maps, address) == null) {
                    long untagged = address & 0x00ffffffffffffffL;
                    if (untagged != address && findMapContaining(maps, untagged) != null) address = untagged;
                }
                MapEntry startMap = findMapContaining(maps, address);
                if (startMap == null || startMap.permissions == null || !startMap.permissions.startsWith("r")) continue;
                for (JSONObject sizeWord : sizeWords) {
                    Long sizeValue = cookiePointerValue(sizeWord.optString("value", ""));
                    if (sizeValue == null || sizeValue < 0x70L || sizeValue > MAX_ART_DEX_BYTES) continue;
                    if (!artReadableRange(maps, address, sizeValue)) continue;
                    byte[] header;
                    try { header = readMappedMemory(context, address, 0x70); }
                    catch (Throwable ignored) { continue; }
                    if (header.length < 0x70 || !looksLikeDexHeader(header, 0)) continue;
                    JSONObject dex = parseDexInfo(header);
                    boolean valid = dex.optLong("file_size", -1L) == sizeValue
                            && dex.optLong("header_size", -1L) == 0x70L
                            && "0x12345678".equals(dex.optString("endian_tag", ""));
                    if (!valid) continue;
                    String key = hex(address) + ":" + sizeValue;
                    JSONObject existing = unique.get(key);
                    if (existing != null) {
                        existing.getJSONArray("data_word_indices").put(ptrWord.optInt("index"));
                        continue;
                    }
                    unique.put(key, new JSONObject()
                            .put("pointer_record_index", p)
                            .put("data_word_indices", new JSONArray().put(ptrWord.optInt("index")))
                            .put("size_word_index", sizeWord.optInt("index"))
                            .put("data_address", hex(address))
                            .put("size", sizeValue)
                            .put("confidence", "high")
                            .put("validation_scope", "header_plus_readable_range")
                            .put("header_sha256", sha256(header))
                            .put("dex_header", dex)
                            .put("map", startMap.json())
                            .put("origins", pointer.optJSONArray("origins") == null ? new JSONArray() : pointer.optJSONArray("origins")));
                    break;
                }
            }
        }
        JSONArray out = new JSONArray();
        for (JSONObject candidate : unique.values()) out.put(candidate);
        return out;
    }

    private static boolean artReadableRange(List<MapEntry> maps, long start, long size) {
        if (size < 0L || start < 0L || start + size < start) return false;
        long end = start + size;
        long cursor = start;
        for (MapEntry map : maps) {
            if (map.permissions == null || !map.permissions.startsWith("r")) continue;
            if (map.end <= cursor) continue;
            if (map.start > cursor) return false;
            if (map.start <= cursor && map.end > cursor) cursor = map.end;
            if (cursor >= end) return true;
        }
        return false;
    }

    private static void pruneArtDexExports() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, ArtDexExportSession> entry : ART_DEX_EXPORTS.entrySet()) {
            ArtDexExportSession value = entry.getValue();
            if (value == null || value.expiresAtMs < now) ART_DEX_EXPORTS.remove(entry.getKey(), value);
        }
    }

    private static JSONObject dexArtDump(Context context, JSONObject request) throws Exception {
        int maxBytes = clamp(request.optInt("max_bytes", MAX_INLINE_BYTES), 1, MAX_INLINE_BYTES);
        JSONObject probeRequest = new JSONObject()
                .put("kind", "memory.dex.art_pointer_probe")
                .put("loader", request.optString("loader", ""))
                .put("max_dex", clamp(request.optInt("max_dex", 256), 1, MAX_DEX))
                .put("max_pointers", clamp(request.optInt("max_pointers", 64), 1, 1024))
                .put("window_bytes", clamp(request.optInt("window_bytes", 4096), 0x70, MAX_INLINE_BYTES))
                .put("scan_back_bytes", clamp(request.optInt("scan_back_bytes", 512), 0, MAX_INLINE_BYTES))
                .put("include_context_loader", request.optBoolean("include_context_loader", true))
                .put("include_class_count", false)
                .put("include_words", true)
                .put("word_count", clamp(request.optInt("word_count", 16), 1, 256))
                .put("try_layout_dex_header", true)
                .put("try_layout_dex_tables", false);
        JSONObject probe = dexArtPointerProbe(context, probeRequest);
        JSONArray flattened = new JSONArray();
        JSONArray pointers = probe.optJSONArray("pointers");
        if (pointers != null) {
            for (int i = 0; i < pointers.length(); i++) {
                JSONObject pointer = pointers.optJSONObject(i);
                if (pointer == null) continue;
                JSONArray candidates = pointer.optJSONArray("layout_dex_candidates");
                if (candidates == null) continue;
                for (int j = 0; j < candidates.length(); j++) {
                    JSONObject candidate = candidates.optJSONObject(j);
                    if (candidate == null || !"high".equals(candidate.optString("confidence", ""))) continue;
                    flattened.put(new JSONObject()
                            .put("pointer_record_index", i)
                            .put("candidate_index_in_pointer", j)
                            .put("candidate", candidate));
                }
            }
        }
        if (flattened.length() == 0) {
            return unsupported("memory.dex.art_dump", "no high-confidence ART DEX memory candidate was found",
                    new JSONArray().put("run dex-art-pointer-probe --try-layout-dex-header to inspect candidate layout")
                            .put("candidate discovery remains Android-version/layout dependent"));
        }
        int selected = request.optInt("candidate_index", 0);
        if (selected < 0 || selected >= flattened.length()) {
            return error("ART_DEX_CANDIDATE_INDEX", "candidate_index must be between 0 and " + (flattened.length() - 1));
        }
        JSONObject selectedRecord = flattened.getJSONObject(selected);
        JSONObject candidateMeta = selectedRecord.getJSONObject("candidate");
        long address = parseAddress(candidateMeta.getString("data_address"));
        long declaredSize = candidateMeta.optLong("size", -1L);
        if (declaredSize < 0x70L || declaredSize > MAX_INLINE_BYTES) {
            return error("ART_DEX_INVALID_SIZE", "validated candidate size is outside bounded dump range: " + declaredSize);
        }
        if (declaredSize > maxBytes) {
            return error("ART_DEX_TOO_LARGE", "validated candidate size " + declaredSize + " exceeds max_bytes " + maxBytes);
        }
        byte[] bytes = readMappedMemory(context, address, (int) declaredSize);
        if (bytes.length != declaredSize || !looksLikeDexHeader(bytes, 0)) {
            return error("ART_DEX_REVALIDATION_FAILED", "candidate changed or could not be read at validated size");
        }
        JSONObject dex = parseDexInfo(bytes);
        boolean invariantOk = dex.optLong("file_size", -1L) == declaredSize
                && dex.optLong("header_size", -1L) == 0x70L
                && "0x12345678".equals(dex.optString("endian_tag", ""));
        if (!invariantOk) return error("ART_DEX_REVALIDATION_FAILED", "DEX header invariants changed before dump");
        String sha = sha256(bytes);
        JSONObject fingerprint = candidateMeta.optJSONObject("content_fingerprint");
        if (fingerprint != null) {
            String expected = fingerprint.optString("memory_sha256", "");
            if (!expected.isBlank() && !expected.equals(sha)) {
                return error("ART_DEX_FINGERPRINT_CHANGED", "memory SHA-256 changed between candidate validation and dump");
            }
        }
        boolean includeData = request.optBoolean("include_data", true);
        JSONObject out = ok().put("candidate_index", selected)
                .put("candidate_count", flattened.length())
                .put("pointer_record_index", selectedRecord.optInt("pointer_record_index"))
                .put("candidate_index_in_pointer", selectedRecord.optInt("candidate_index_in_pointer"))
                .put("data_address", hex(address))
                .put("size", bytes.length)
                .put("max_bytes", maxBytes)
                .put("sha256", sha)
                .put("dex", dex)
                .put("candidate", candidateMeta)
                .put("raw_byte_reconstruction", true)
                .put("bounded", true)
                .put("data_included", includeData)
                .put("strategy", "validated ART data_begin + file_size candidate re-read with DEX header/endian/size/SHA-256 revalidation")
                .put("warning", "Raw export is limited to high-confidence validated ART candidates not exceeding the runtime inline byte cap; unbounded/chunked ART DEX streaming is not implemented.");
        if (includeData) out.put("encoding", "base64").put("data", Base64.encodeToString(bytes, Base64.NO_WRAP));
        return out;
    }

    private static JSONArray artPointerWords(byte[] bytes, long probeStart, int pointerOffset, int wordCount) throws Exception {
        JSONArray out = new JSONArray();
        int base = Math.max(0, pointerOffset);
        for (int i = 0; i < wordCount; i++) {
            int off = base + i * 8;
            if (off + 8 > bytes.length) break;
            long value = u64le(bytes, off);
            JSONObject item = new JSONObject()
                    .put("index", i)
                    .put("address", hex(probeStart + off))
                    .put("value", hex(value))
                    .put("value_signed", Long.toString(value));
            MapEntry rawMap = findMapContaining(value);
            long untagged = value & 0x00ffffffffffffffL;
            MapEntry untaggedMap = rawMap == null && untagged != value ? findMapContaining(untagged) : null;
            if (rawMap != null) {
                item.put("points_to", rawMap.json()).put("pointer_transform", "raw");
            } else if (untaggedMap != null) {
                item.put("resolved_value", hex(untagged)).put("points_to", untaggedMap.json()).put("pointer_transform", "aarch64_tbi_untagged_low56");
            } else {
                item.put("points_to", JSONObject.NULL).put("pointer_transform", JSONObject.NULL);
            }
            out.put(item);
        }
        return out;
    }



    private static JSONArray artPointerLayoutDexCandidates(Context context, JSONArray words, JSONArray origins, boolean includeTables,
            int tableLimit, int memberLimit, String filter) throws Exception {
        LinkedHashMap<String, JSONObject> unique = new LinkedHashMap<>();
        ArrayList<JSONObject> pointerWords = new ArrayList<>();
        ArrayList<JSONObject> sizeWords = new ArrayList<>();
        for (int i = 0; i < words.length(); i++) {
            JSONObject word = words.getJSONObject(i);
            JSONObject pointsTo = word.optJSONObject("points_to");
            Long numeric = cookiePointerValue(word.optString("value", ""));
            if (pointsTo != null && numeric != null && pointsTo.optString("permissions", "").startsWith("r")) pointerWords.add(word);
            if (numeric != null && numeric >= 0x70L && numeric <= MAX_INLINE_BYTES) sizeWords.add(word);
        }
        for (JSONObject ptrWord : pointerWords) {
            if (unique.size() >= 8) break;
            Long rawAddress = cookiePointerValue(ptrWord.optString("value", ""));
            if (rawAddress == null || rawAddress == 0L) continue;
            long address = rawAddress;
            if (findMapContaining(address) == null) {
                long untagged = address & 0x00ffffffffffffffL;
                if (untagged != address && findMapContaining(untagged) != null) address = untagged;
            }
            MapEntry map = findMapContaining(address);
            if (map == null || map.permissions == null || !map.permissions.startsWith("r")) continue;
            for (JSONObject sizeWord : sizeWords) {
                Long sizeValue = cookiePointerValue(sizeWord.optString("value", ""));
                if (sizeValue == null || sizeValue < 0x70L || sizeValue > MAX_INLINE_BYTES) continue;
                if (Long.compareUnsigned(address + sizeValue, map.end) > 0) continue;
                int readSize = (int)Math.min(sizeValue, (long)MAX_INLINE_BYTES);
                byte[] candidate;
                try { candidate = readMappedMemory(context, address, readSize); }
                catch (Throwable ignored) { continue; }
                if (candidate.length < 0x70 || !looksLikeDexHeader(candidate, 0)) continue;
                JSONObject dex = parseDexInfo(candidate);
                boolean sizeConsistent = dex.optLong("file_size", -1L) == sizeValue;
                boolean headerConsistent = dex.optLong("header_size", -1L) == 0x70L
                        && "0x12345678".equals(dex.optString("endian_tag", ""));
                if (!sizeConsistent || !headerConsistent) continue;
                String key = hex(address) + ":" + sizeValue;
                JSONObject existing = unique.get(key);
                if (existing != null) {
                    existing.getJSONArray("data_word_indices").put(ptrWord.optInt("index"));
                    continue;
                }
                JSONObject record = new JSONObject()
                        .put("data_word_indices", new JSONArray().put(ptrWord.optInt("index")))
                        .put("size_word_index", sizeWord.optInt("index"))
                        .put("data_address", hex(address))
                        .put("size", sizeValue)
                        .put("size_consistent", true)
                        .put("header_consistent", true)
                        .put("confidence", "high")
                        .put("map", map.json())
                        .put("dex", dex)
                        .put("content_fingerprint", artMemoryDexContentFingerprint(origins, candidate, dex))
                        .put("art_memory_table_reconstruction", false)
                        .put("strategy", "heuristic ART DexFile data_begin pointer + file_size word; validated DEX metadata parsed from memory without exporting raw bytes");
                if (includeTables) {
                    JSONObject tables = artMemoryDexTables(candidate, tableLimit, memberLimit, filter);
                    record.put("tables", tables)
                            .put("art_memory_table_reconstruction", tables.optBoolean("ok", false));
                }
                unique.put(key, record);
                break;
            }
        }
        JSONArray out = new JSONArray();
        for (JSONObject value : unique.values()) out.put(value);
        return out;
    }

    private static JSONObject artMemoryDexTables(byte[] bytes, int tableLimit, int memberLimit, String filter) throws Exception {
        return ok().put("source", "validated_art_memory_candidate")
                .put("bounded", true)
                .put("raw_bytes_included", false)
                .put("table_limit", tableLimit)
                .put("member_limit", memberLimit)
                .put("filter", filter == null || filter.isBlank() ? JSONObject.NULL : filter)
                .put("strings", parseDexStrings(bytes, tableLimit, filter))
                .put("classes", parseDexClasses(bytes, tableLimit, filter))
                .put("fields", parseDexFields(bytes, tableLimit, filter))
                .put("methods", parseDexMethods(bytes, tableLimit, filter))
                .put("class_data", parseDexClassData(bytes, tableLimit, memberLimit, filter));
    }

    private static JSONObject artMemoryDexContentFingerprint(JSONArray origins, byte[] bytes, JSONObject dex) throws Exception {
        String memorySha256 = sha256(bytes);
        String memorySignature = dex.optString("signature", "");
        JSONArray matches = new JSONArray();
        boolean exactContentMatch = false;
        LinkedHashSet<String> apkPaths = new LinkedHashSet<>();
        if (origins != null) {
            for (int i = 0; i < origins.length(); i++) {
                JSONObject origin = origins.optJSONObject(i);
                if (origin == null) continue;
                String dexName = jsonString(origin, "dex_name");
                String apkPath = dexName;
                int bang = apkPath.indexOf("!/");
                if (bang >= 0) apkPath = apkPath.substring(0, bang);
                if (apkPath.endsWith(".apk")) apkPaths.add(apkPath);
            }
        }
        for (String apkPath : apkPaths) {
            File apk = new File(apkPath);
            if (!apk.isFile() || !apk.canRead()) continue;
            try (ZipFile zip = new ZipFile(apk)) {
                java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (entry.isDirectory() || !entry.getName().matches("classes(\\d*)?\\.dex") || entry.getSize() != bytes.length) continue;
                    byte[] apkBytes;
                    try (InputStream in = zip.getInputStream(entry)) { apkBytes = readLimited(in, bytes.length); }
                    if (apkBytes.length != bytes.length) continue;
                    String apkSha256 = sha256(apkBytes);
                    JSONObject apkDex = parseDexInfo(apkBytes);
                    boolean sha256Match = memorySha256.equals(apkSha256);
                    boolean signatureMatch = memorySignature.equals(apkDex.optString("signature", ""));
                    exactContentMatch |= sha256Match;
                    matches.put(new JSONObject()
                            .put("apk_path", apkPath)
                            .put("entry", entry.getName())
                            .put("entry_size", entry.getSize())
                            .put("compressed_size", entry.getCompressedSize())
                            .put("apk_sha256", apkSha256)
                            .put("sha256_match", sha256Match)
                            .put("dex_signature_match", signatureMatch)
                            .put("apk_dex_version", apkDex.optString("version", "")));
                }
            } catch (Throwable error) {
                matches.put(new JSONObject().put("apk_path", apkPath).put("error", error.toString()));
            }
        }
        return ok().put("source", "validated_art_memory_candidate")
                .put("memory_size", bytes.length)
                .put("memory_sha256", memorySha256)
                .put("memory_dex_signature", memorySignature)
                .put("apk_candidate_count", matches.length())
                .put("exact_apk_content_match", exactContentMatch)
                .put("apk_candidates", matches)
                .put("raw_bytes_included", false);
    }


    private static JSONArray artPointerApkDexSizeMatches(JSONArray origins, JSONObject hints) throws Exception {
        JSONArray matches = new JSONArray();
        JSONArray sizes = hints.optJSONArray("candidate_size_words");
        if (sizes == null || sizes.length() == 0 || origins == null) return matches;
        LinkedHashSet<String> apkPaths = new LinkedHashSet<>();
        for (int i = 0; i < origins.length(); i++) {
            JSONObject origin = origins.optJSONObject(i);
            if (origin == null) continue;
            String dexName = jsonString(origin, "dex_name");
            String apkPath = dexName;
            int bang = apkPath.indexOf("!/");
            if (bang >= 0) apkPath = apkPath.substring(0, bang);
            if (apkPath.endsWith(".apk")) apkPaths.add(apkPath);
        }
        for (String apkPath : apkPaths) {
            File apk = new File(apkPath);
            if (!apk.isFile() || !apk.canRead()) continue;
            try (ZipFile zip = new ZipFile(apk)) {
                java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (entry.isDirectory()) continue;
                    String name = entry.getName();
                    if (!name.matches("classes(\\d*)?\\.dex")) continue;
                    long entrySize = entry.getSize();
                    for (int i = 0; i < sizes.length(); i++) {
                        JSONObject sizeWord = sizes.optJSONObject(i);
                        if (sizeWord == null) continue;
                        long candidateSize = sizeWord.optLong("decimal", -1L);
                        if (candidateSize == entrySize) {
                            matches.put(new JSONObject()
                                    .put("apk_path", apkPath)
                                    .put("entry", name)
                                    .put("entry_size", entrySize)
                                    .put("compressed_size", entry.getCompressedSize())
                                    .put("size_word_index", sizeWord.optInt("word_index"))
                                    .put("size_word_value", sizeWord.optString("value"))
                                    .put("match", "entry_size"));
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }
        return matches;
    }

    private static JSONObject artPointerLayoutHints(JSONArray words) throws Exception {
        JSONArray likelyPointers = new JSONArray();
        JSONArray likelySizes = new JSONArray();
        JSONObject out = new JSONObject().put("likely_libdexfile_vtable", false);
        for (int i = 0; i < words.length(); i++) {
            JSONObject word = words.getJSONObject(i);
            JSONObject pointsTo = word.optJSONObject("points_to");
            if (pointsTo != null) {
                String path = jsonString(pointsTo, "pathname");
                likelyPointers.put(new JSONObject().put("word_index", word.optInt("index"))
                        .put("value", word.optString("value"))
                        .put("pathname", path.isBlank() ? JSONObject.NULL : path)
                        .put("permissions", pointsTo.optString("permissions", "")));
                if (word.optInt("index") == 0 && path.contains("libdexfile.so")) {
                    out.put("likely_libdexfile_vtable", true)
                            .put("vtable_word", word.optInt("index"))
                            .put("vtable_value", word.optString("value"))
                            .put("vtable_path", path);
                }
            }
            Long numeric = cookiePointerValue(word.optString("value", ""));
            if (numeric != null && numeric >= 0x70L && numeric <= MAX_INLINE_BYTES) {
                likelySizes.put(new JSONObject().put("word_index", word.optInt("index"))
                        .put("value", word.optString("value"))
                        .put("decimal", numeric)
                        .put("reason", "small positive value within configured inline DEX size cap"));
            }
        }
        return out.put("pointer_word_count", likelyPointers.length())
                .put("pointer_words", likelyPointers)
                .put("candidate_size_words", likelySizes)
                .put("confidence", out.optBoolean("likely_libdexfile_vtable", false) && likelySizes.length() > 0 ? "medium" : "low")
                .put("note", "Heuristic only: useful for ART layout research, not a stable Android-version-independent DexFile parser.");
    }

    private static void addCookiePointerField(LinkedHashMap<Long, JSONArray> pointers, Object value, String loaderHandle, String dexName, int dexIndex, String fieldName, int maxPointers) throws Exception {
        if (value == null || pointers.size() >= maxPointers) return;
        Class<?> c = value.getClass();
        if (c.isArray()) {
            int n = Math.min(java.lang.reflect.Array.getLength(value), 64);
            for (int i = 0; i < n && pointers.size() < maxPointers; i++) {
                addCookiePointer(pointers, java.lang.reflect.Array.get(value, i), loaderHandle, dexName, dexIndex, fieldName, i);
            }
        } else {
            addCookiePointer(pointers, value, loaderHandle, dexName, dexIndex, fieldName, -1);
        }
    }

    private static void addCookiePointer(LinkedHashMap<Long, JSONArray> pointers, Object raw, String loaderHandle, String dexName, int dexIndex, String fieldName, int elementIndex) throws Exception {
        Long pointer = cookiePointerValue(raw);
        if (pointer == null || pointer == 0L) return;
        JSONArray origins = pointers.computeIfAbsent(pointer, ignored -> new JSONArray());
        origins.put(new JSONObject()
                .put("loader_handle", loaderHandle)
                .put("dex_name", dexName.isBlank() ? JSONObject.NULL : dexName)
                .put("dex_index", dexIndex)
                .put("field", fieldName)
                .put("element_index", elementIndex < 0 ? JSONObject.NULL : elementIndex)
                .put("raw", String.valueOf(raw)));
    }


    private static String jsonString(JSONObject object, String key) {
        if (object == null || !object.has(key) || object.isNull(key)) return "";
        Object value = object.opt(key);
        if (value == null || value == JSONObject.NULL) return "";
        String text = String.valueOf(value);
        return "null".equals(text) ? "" : text;
    }

    private static Long cookiePointerValue(Object raw) {
        if (raw == null) return null;
        if (raw instanceof Number) return ((Number) raw).longValue();
        String text = String.valueOf(raw).trim();
        if (text.isEmpty()) return null;
        try {
            if (text.startsWith("0x") || text.startsWith("0X")) return Long.parseUnsignedLong(text.substring(2), 16);
            return Long.parseLong(text);
        } catch (Throwable ignored) {
            try { return Long.parseUnsignedLong(text); } catch (Throwable ignoredAgain) { return null; }
        }
    }

    private static JSONObject probeArtCookiePointer(Context context, long pointer, JSONArray origins, int windowBytes, int scanBackBytes,
            boolean includeWords, int wordCount, boolean tryLayoutDexHeader, boolean tryLayoutDexTables,
            int layoutTableLimit, int layoutMemberLimit, String layoutFilter) throws Exception {
        JSONObject out = new JSONObject()
                .put("pointer", hex(pointer))
                .put("pointer_signed", Long.toString(pointer))
                .put("origins", origins)
                .put("readable", false)
                .put("art_memory_header_reconstruction", false)
                .put("art_memory_table_reconstruction", false)
                .put("dex_candidates", new JSONArray());
        long resolvedPointer = pointer;
        String pointer_transform = "raw";
        MapEntry map = findMapContaining(pointer);
        if (map == null && android.os.Build.SUPPORTED_64_BIT_ABIS.length > 0) {
            long untagged = pointer & 0x00ffffffffffffffL;
            if (untagged != pointer) {
                MapEntry untaggedMap = findMapContaining(untagged);
                if (untaggedMap != null) {
                    resolvedPointer = untagged;
                    map = untaggedMap;
                    pointer_transform = "aarch64_tbi_untagged_low56";
                }
            }
        }
        out.put("resolved_pointer", hex(resolvedPointer)).put("pointer_transform", pointer_transform);
        if (map == null) return out.put("map", JSONObject.NULL).put("reason", "pointer not found in /proc/self/maps, including AArch64 TBI low-56 untag attempt");
        out.put("map", map.json());
        if (map.permissions == null || !map.permissions.startsWith("r")) return out.put("reason", "mapped region is not readable");
        long before = Math.max(0, Math.min((long)scanBackBytes, resolvedPointer - map.start));
        long after = Math.max(0, Math.min((long)windowBytes, map.end - resolvedPointer));
        long total = Math.max(0x70L, Math.min((long)MAX_INLINE_BYTES, before + after));
        long start = resolvedPointer - before;
        if (Long.compareUnsigned(start, map.start) < 0) start = map.start;
        if (Long.compareUnsigned(start + total, map.end) > 0) total = Math.max(0, map.end - start);
        int size = (int)Math.min(MAX_INLINE_BYTES, total);
        if (size < 0x70) return out.put("reason", "read window too small");
        byte[] bytes;
        try { bytes = readMappedMemory(context, start, size); }
        catch (Throwable error) { return out.put("reason", error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage())); }
        out.put("readable", true).put("probe_start", hex(start)).put("bytes_read", bytes.length).put("pointer_offset", resolvedPointer - start);
        if (includeWords) {
            JSONArray words = artPointerWords(bytes, start, (int)Math.max(0, resolvedPointer - start), wordCount);
            JSONObject hints = artPointerLayoutHints(words);
            out.put("words", words).put("layout_hints", hints)
                    .put("apk_dex_size_matches", artPointerApkDexSizeMatches(origins, hints));
            if (tryLayoutDexHeader) {
                JSONArray layoutCandidates = artPointerLayoutDexCandidates(context, words, origins, tryLayoutDexTables,
                        layoutTableLimit, layoutMemberLimit, layoutFilter);
                boolean tableReconstruction = false;
                for (int i = 0; i < layoutCandidates.length(); i++) {
                    JSONObject candidate = layoutCandidates.optJSONObject(i);
                    if (candidate != null && candidate.optBoolean("art_memory_table_reconstruction", false)) {
                        tableReconstruction = true;
                        break;
                    }
                }
                out.put("layout_dex_candidates", layoutCandidates)
                        .put("art_memory_header_reconstruction", layoutCandidates.length() > 0)
                        .put("art_memory_table_reconstruction", tableReconstruction)
                        .put("layout_dex_candidate_count", layoutCandidates.length());
            }
        } else if (tryLayoutDexHeader) {
            out.put("layout_dex_candidates", new JSONArray()).put("layout_dex_note", "try_layout_dex_header requires include_words=true");
        }
        JSONArray candidates = new JSONArray();
        for (int off = 0; off + 0x70 <= bytes.length && candidates.length() < 16; off++) {
            if (!looksLikeDexHeader(bytes, off)) continue;
            long address = start + off;
            long fileSize = u32le(bytes, off + 0x20);
            JSONObject header = new JSONObject()
                    .put("address", hex(address))
                    .put("offset_in_probe", off)
                    .put("relative_to_pointer", off - (resolvedPointer - start))
                    .put("version", new String(bytes, off + 4, 3, java.nio.charset.StandardCharsets.US_ASCII))
                    .put("file_size", fileSize)
                    .put("header_size", u32le(bytes, off + 0x24))
                    .put("endian_tag", hex(u32le(bytes, off + 0x28)))
                    .put("string_ids_size", u32le(bytes, off + 0x38))
                    .put("type_ids_size", u32le(bytes, off + 0x40))
                    .put("method_ids_size", u32le(bytes, off + 0x58))
                    .put("class_defs_size", u32le(bytes, off + 0x60));
            candidates.put(header);
        }
        return out.put("dex_candidates", candidates)
                .put("dex_candidate_count", candidates.length())
                .put("reason", candidates.length() == 0 ? "no DEX magic/header found in bounded pointer neighborhood" : JSONObject.NULL);
    }

    private static JSONObject dexScan(Context context, JSONObject request) throws Exception {
        int maxMaps = clamp(request.optInt("max_maps", 256), 1, MAX_MAPS);
        int maxCandidates = clamp(request.optInt("max_candidates", 64), 1, 1024);
        int maxScanBytes = clamp(request.optInt("max_scan_bytes_per_map", 2 * 1024 * 1024), 4096, MAX_INLINE_BYTES);
        int dumpBytes = clamp(request.optInt("dump_bytes", 0), 0, MAX_INLINE_BYTES);
        String pathContains = request.optString("path_contains", "");
        boolean includeAnonymous = request.optBoolean("include_anonymous", false);
        boolean includeData = request.optBoolean("include_data", false) && dumpBytes > 0;
        JSONArray candidates = new JSONArray();
        int scannedMaps = 0;
        int skippedMaps = 0;
        int truncatedMaps = 0;
        for (MapEntry entry : readMaps(MAX_MAPS)) {
            if (!entry.permissions.startsWith("r")) continue;
            if (!includeAnonymous && (entry.path == null || entry.path.isBlank() || entry.path.startsWith("["))) { skippedMaps++; continue; }
            if (!pathContains.isEmpty() && (entry.path == null || !entry.path.contains(pathContains))) continue;
            if (scannedMaps >= maxMaps || candidates.length() >= maxCandidates) break;
            int wanted = (int)Math.min(entry.size(), maxScanBytes);
            if (wanted < 0x70) { skippedMaps++; continue; }
            byte[] bytes;
            try { bytes = readMappedMemory(context, entry.start, wanted); }
            catch (Throwable error) { skippedMaps++; continue; }
            scannedMaps++;
            if (bytes.length < wanted) truncatedMaps++;
            for (int off = 0; off + 0x70 <= bytes.length && candidates.length() < maxCandidates; off++) {
                if (!looksLikeDexHeader(bytes, off)) continue;
                long absolute = entry.start + off;
                long fileSize = u32le(bytes, off + 0x20);
                JSONObject item = new JSONObject()
                        .put("address", hex(absolute))
                        .put("map_start", hex(entry.start))
                        .put("map_end", hex(entry.end))
                        .put("map_offset", hex(entry.offset))
                        .put("map_permissions", entry.permissions)
                        .put("pathname", entry.path == null || entry.path.isBlank() ? JSONObject.NULL : entry.path)
                        .put("dex_version", new String(bytes, off + 4, 3, java.nio.charset.StandardCharsets.US_ASCII))
                        .put("file_size", fileSize)
                        .put("header_size", u32le(bytes, off + 0x24))
                        .put("endian_tag", hex(u32le(bytes, off + 0x28)))
                        .put("map_backed", entry.path != null && !entry.path.isBlank() && !entry.path.startsWith("["));
                if (includeData) {
                    int n = (int)Math.min(Math.min(fileSize, dumpBytes), bytes.length - off);
                    item.put("size", n).put("encoding", "base64")
                            .put("data", Base64.encodeToString(java.util.Arrays.copyOfRange(bytes, off, off + n), Base64.NO_WRAP));
                }
                candidates.put(item);
                off += 0x6f;
            }
        }
        return ok().put("api_level", android.os.Build.VERSION.SDK_INT)
                .put("count", candidates.length())
                .put("candidates", candidates)
                .put("scanned_maps", scannedMaps)
                .put("skipped_maps", skippedMaps)
                .put("truncated_maps", truncatedMaps)
                .put("max_scan_bytes_per_map", maxScanBytes)
                .put("path_contains", pathContains.isEmpty() ? JSONObject.NULL : pathContains)
                .put("include_anonymous", includeAnonymous)
                .put("include_data", includeData)
                .put("art_memory_reconstruction", false)
                .put("strategy", "bounded readable-map DEX magic/header scan; does not follow ART mCookie/DexCaches");
    }

    private static JSONObject dexDump(JSONObject request) throws Exception {
        String handle = request.optString("dex", request.optString("dex_handle", "")); Object dex = ObjectRegistry.get().get(handle);
        if (dex == null) return error("STALE_HANDLE", handle);
        String name = null;
        try { name = String.valueOf(dex.getClass().getMethod("getName").invoke(dex)); } catch (Throwable ignored) {}
        if (name != null && !name.isBlank()) {
            File file = new File(name);
            if (file.isFile() && file.canRead()) {
                long length = file.length(); int max = clamp(request.optInt("max_bytes", MAX_INLINE_BYTES), 1, MAX_INLINE_BYTES);
                if (length > max) return unsupported("memory.dex.dump", "file-backed dex exceeds inline limit: " + length,
                        new JSONArray().put("file-backed copy available with a host/root streaming strategy").put("ART memory reconstruction not implemented for this runtime"));
                byte[] bytes = readFile(file, max);
                return ok().put("dex_handle", handle).put("name", name).put("size", bytes.length)
                        .put("encoding", "base64").put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))
                        .put("strategy", "file_backed").put("memory_reconstruction", false)
                        .put("warning", "This is not claimed as an in-memory ART DexFile reconstruction.");
            }
        }
        JSONObject cookie = cookieInfo(dex);
        return unsupported("memory.dex.dump", "DexFile has no readable backing file and native ART DexFile reconstruction is not implemented for API " + android.os.Build.VERSION.SDK_INT,
                new JSONArray().put("mCookie/mInternalCookie exposed: " + (cookie == null ? "unavailable" : cookie.toString()))
                        .put("Layout Inspect uses native ART offsets/cookies; strategy remains version-gated"));
    }

    private static JSONObject assetsList(Context context, JSONObject request) throws Exception {
        String prefix = normalizeAsset(request.optString("path", "")); int max=clamp(request.optInt("max_assets",5000),1,MAX_ASSETS);
        JSONArray out=new JSONArray(); listAssets(context.getAssets(),prefix,out,max);
        return ok().put("path",prefix).put("count",out.length()).put("assets",out).put("truncated",out.length()>=max)
                .put("strategy","runtime AssetManager.list");
    }

    private static JSONObject assetsPull(Context context, JSONObject request) throws Exception {
        String path=normalizeAsset(request.getString("path")); int max=clamp(request.optInt("max_bytes",MAX_INLINE_BYTES),1,MAX_INLINE_BYTES);
        try(InputStream in=context.getAssets().open(path,AssetManager.ACCESS_STREAMING)) {
            byte[] bytes=readLimited(in,max+1); if(bytes.length>max)return error("ASSET_TOO_LARGE","asset exceeds inline limit");
            return ok().put("path",path).put("size",bytes.length).put("encoding","base64")
                    .put("data",Base64.encodeToString(bytes,Base64.NO_WRAP)).put("strategy","runtime AssetManager.open");
        }
    }

    private static JSONObject xmlPull(Context context, JSONObject request) throws Exception {
        int id=request.optInt("resource_id",0);
        if(id==0)return unsupported("memory.xml.pull","resource_id is required for the stable Resources.getXml strategy",
                new JSONArray().put("logical runtime XML serialization").put("native XmlBlock/ResXMLTree binary recovery is version-gated"));
        StringBuilder out=new StringBuilder();
        try(XmlResourceParser parser=context.getResources().getXml(id)) {
            int event=parser.getEventType();
            while(event!=XmlPullParser.END_DOCUMENT && out.length()<1_000_000) {
                if(event==XmlPullParser.START_TAG) {
                    out.append('<').append(parser.getName());
                    for(int i=0;i<parser.getAttributeCount();i++) out.append(' ').append(parser.getAttributeName(i)).append("=\"").append(escapeXml(parser.getAttributeValue(i))).append('"');
                    out.append('>');
                } else if(event==XmlPullParser.END_TAG) out.append("</").append(parser.getName()).append('>');
                else if(event==XmlPullParser.TEXT) out.append(escapeXml(parser.getText()));
                event=parser.next();
            }
        }
        return ok().put("resource_id",id).put("xml",out.toString()).put("strategy","Resources.getXml logical serialization")
                .put("binary_axml",false).put("warning","Native XmlBlock/ResXMLTree byte recovery remains capability-gated.");
    }

    private static JSONObject xmlBlockProbe(Context context, JSONObject request) throws Exception {
        int id = request.optInt("resource_id", 0);
        int maxEvents = clamp(request.optInt("max_events", 64), 1, 2048);
        int maxAttributes = clamp(request.optInt("max_attributes", 64), 0, 512);
        int maxSourceBytes = clamp(request.optInt("max_source_bytes", MAX_INLINE_BYTES), 1, MAX_INLINE_BYTES);
        if (id == 0) return unsupported("memory.xml.block_probe", "resource_id is required", new JSONArray()
                .put("Resources.getXml(resourceId) XmlResourceParser reflection")
                .put("file-backed memory.xml.binary / memory.xml.axml_decode remain available for raw APK AXML"));
        Context apkCtx = apkContext(context, request);
        JSONObject out = ok()
                .put("resource_id", id)
                .put("resource_name", safeResourceName(apkCtx, id))
                .put("api_level", android.os.Build.VERSION.SDK_INT)
                .put("memory_reconstruction", false)
                .put("binary_axml", false)
                .put("strategy", "Resources.getXml XmlResourceParser/XmlBlock reflection + bounded pull-parser event preview")
                .put("warning", "This probes XmlBlock/Parser object shape and events. It does not export native ResXMLTree/XmlBlock bytes.");
        TypedValue value = new TypedValue();
        try {
            apkCtx.getResources().getValue(id, value, true);
            out.put("typed_value", new JSONObject()
                    .put("asset_cookie", value.assetCookie)
                    .put("type", value.type)
                    .put("data", value.data)
                    .put("string", value.string == null ? JSONObject.NULL : value.string.toString()));
            if (value.string != null) {
                String entry = normalizeZipEntry(value.string.toString());
                out.put("source_entry", entry);
                if (entry.endsWith(".xml")) {
                    JSONObject sourceMeta = apkEntryMetadataAnySource(apkCtx, entry, maxSourceBytes);
                    if (sourceMeta.optBoolean("ok", false)) out.put("file_backed_axml", sourceMeta);
                    else out.put("file_backed_axml_error", sourceMeta);
                }
            }
        } catch (Throwable error) {
            out.put("typed_value_error", error.getClass().getName() + ": " + String.valueOf(error.getMessage()));
        }
        try (XmlResourceParser parser = apkCtx.getResources().getXml(id)) {
            out.put("parser_class", parser.getClass().getName())
                    .put("parser_fields", reflectFieldShape(parser, 96));
            Object block = firstFieldByNameOrClass(parser, "mBlock", "XmlBlock");
            if (block != null) {
                out.put("xml_block_class", block.getClass().getName())
                        .put("xml_block_fields", reflectFieldShape(block, 96));
            } else {
                out.put("xml_block_class", JSONObject.NULL)
                        .put("xml_block_fields", new JSONArray());
            }
            JSONArray events = new JSONArray();
            boolean truncated = false;
            int event = parser.getEventType();
            while (event != XmlPullParser.END_DOCUMENT) {
                if (events.length() >= maxEvents) { truncated = true; break; }
                JSONObject item = new JSONObject()
                        .put("event", event)
                        .put("event_name", xmlEventName(event))
                        .put("depth", parser.getDepth())
                        .put("name", parser.getName() == null ? JSONObject.NULL : parser.getName());
                if (event == XmlPullParser.TEXT) item.put("text", preview(parser.getText(), 256));
                if (event == XmlPullParser.START_TAG) {
                    JSONArray attrs = new JSONArray();
                    int attrCount = parser.getAttributeCount();
                    for (int i = 0; i < attrCount && i < maxAttributes; i++) {
                        attrs.put(new JSONObject()
                                .put("index", i)
                                .put("namespace", parser.getAttributeNamespace(i) == null ? JSONObject.NULL : parser.getAttributeNamespace(i))
                                .put("name", parser.getAttributeName(i))
                                .put("value", preview(parser.getAttributeValue(i), 256))
                                .put("resource_value", parser.getAttributeResourceValue(i, 0))
                                .put("type", reflectIntMethod(parser, "getAttributeValueType", i))
                                .put("data", reflectIntMethod(parser, "getAttributeValueData", i)));
                    }
                    item.put("attribute_count", attrCount)
                            .put("attributes_truncated", attrCount > maxAttributes)
                            .put("attributes", attrs);
                }
                events.put(item);
                event = parser.next();
            }
            out.put("event_count", events.length()).put("events", events).put("events_truncated", truncated);
        }
        return out;
    }

    private static JSONArray reflectFieldShape(Object object, int maxFields) throws Exception {
        JSONArray out = new JSONArray();
        if (object == null) return out;
        Class<?> c = object.getClass();
        while (c != null && out.length() < maxFields) {
            for (Field f : c.getDeclaredFields()) {
                if (out.length() >= maxFields) break;
                try {
                    f.setAccessible(true);
                    Object value = f.get(object);
                    JSONObject item = new JSONObject()
                            .put("declaring_class", c.getName())
                            .put("name", f.getName())
                            .put("type", f.getType().getName())
                            .put("value_kind", value == null ? "null" : value.getClass().getName())
                            .put("value", summarizeReflectValue(value));
                    if (value instanceof Number && (f.getName().toLowerCase().contains("native") || f.getName().toLowerCase().contains("state") || f.getName().toLowerCase().contains("ptr"))) {
                        long n = ((Number)value).longValue();
                        item.put("hex", hex(n)).put("native_pointer_like", n != 0L);
                    }
                    out.put(item);
                } catch (Throwable error) {
                    out.put(new JSONObject().put("declaring_class", c.getName()).put("name", f.getName())
                            .put("type", f.getType().getName()).put("error", error.getClass().getSimpleName()));
                }
            }
            c = c.getSuperclass();
        }
        return out;
    }

    private static Object firstFieldByNameOrClass(Object object, String name, String classNamePart) {
        if (object == null) return null;
        Class<?> c = object.getClass();
        while (c != null) {
            for (Field f : c.getDeclaredFields()) {
                try {
                    f.setAccessible(true);
                    Object value = f.get(object);
                    if (value == null) continue;
                    if (f.getName().equals(name) || value.getClass().getName().contains(classNamePart)) return value;
                } catch (Throwable ignored) {}
            }
            c = c.getSuperclass();
        }
        return null;
    }

    private static Object reflectIntMethod(Object object, String method, int arg) {
        if (object == null) return JSONObject.NULL;
        Class<?> c = object.getClass();
        while (c != null) {
            try {
                java.lang.reflect.Method m = c.getDeclaredMethod(method, int.class);
                m.setAccessible(true);
                Object value = m.invoke(object, arg);
                return value == null ? JSONObject.NULL : value;
            } catch (NoSuchMethodException error) {
                c = c.getSuperclass();
            } catch (Throwable error) {
                return JSONObject.NULL;
            }
        }
        return JSONObject.NULL;
    }

    private static Object summarizeReflectValue(Object value) {
        if (value == null) return JSONObject.NULL;
        Class<?> c = value.getClass();
        if (value instanceof Number || value instanceof Boolean || value instanceof String) return String.valueOf(value);
        if (c.isArray()) return "array(len=" + java.lang.reflect.Array.getLength(value) + ", type=" + c.getComponentType().getName() + ")";
        return value.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(value));
    }

    private static String preview(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }

    private static String xmlEventName(int event) {
        return switch (event) {
            case XmlPullParser.START_DOCUMENT -> "START_DOCUMENT";
            case XmlPullParser.END_DOCUMENT -> "END_DOCUMENT";
            case XmlPullParser.START_TAG -> "START_TAG";
            case XmlPullParser.END_TAG -> "END_TAG";
            case XmlPullParser.TEXT -> "TEXT";
            default -> "XML_EVENT_" + event;
        };
    }

    private static JSONObject xmlBinary(Context context, JSONObject request) throws Exception {
        int max = clamp(request.optInt("max_bytes", MAX_INLINE_BYTES), 1, MAX_INLINE_BYTES);
        Context apkContext = apkContext(context, request);
        String requestedEntry = request.optString("entry", "");
        String entry;
        JSONObject resource = new JSONObject();
        if (!requestedEntry.isBlank()) {
            entry = normalizeZipEntry(requestedEntry);
        } else {
            int id = request.optInt("resource_id", 0);
            if (id == 0) return unsupported("memory.xml.binary", "resource_id or entry is required", new JSONArray().put("APK file-backed binary XML by resource path").put("Resources.getValue(resourceId) path discovery"));
            TypedValue value = new TypedValue();
            apkContext.getResources().getValue(id, value, true);
            CharSequence text = value.string;
            if (text == null) return unsupported("memory.xml.binary", "resource does not expose a string path: " + id, new JSONArray().put("Resources.getValue(resourceId).string").put("native XmlBlock/ResXMLTree memory recovery not implemented"));
            entry = normalizeZipEntry(text.toString());
            resource.put("resource_id", id)
                    .put("resource_name", safeResourceName(apkContext, id))
                    .put("typed_value_string", text.toString())
                    .put("asset_cookie", value.assetCookie)
                    .put("type", value.type);
        }
        if (!entry.endsWith(".xml")) return error("NOT_XML_ENTRY", entry);
        JSONObject pulled = pullApkEntryAnySource(apkContext, entry, max);
        if (!pulled.optBoolean("ok", false)) return pulled;
        return pulled.put("resource", resource)
                .put("binary_axml", true)
                .put("memory_reconstruction", false)
                .put("strategy", requestedEntry.isBlank()
                        ? "Resources.getValue(resourceId) -> APK ZipFile raw binary XML"
                        : "APK ZipFile raw binary XML by entry")
                .put("warning", "This returns file-backed binary AXML bytes from the APK, not a native XmlBlock/ResXMLTree memory reconstruction.");
    }

    private static JSONObject capabilities(Context context) throws Exception {
        boolean bridgeLoaded = context != null && NativeBridge.ensureLoaded(context);
        return ok().put("api_level",android.os.Build.VERSION.SDK_INT)
                .put("maps",status(true,"/proc/self/maps"))
                .put("modules",status(true,"maps grouping; split mappings preserved"))
                .put("module_file_dump",status(true,"readable file-backed module copy with sha256"))
                .put("native_bridge",status(bridgeLoaded, bridgeLoaded?"JNI bridge loaded: process_vm_readv, pread, dlopen, dladdr, dl_iterate_phdr, self probe":"JNI bridge unavailable: "+NativeBridge.loadError()))
                .put("native_modules",status(bridgeLoaded,"native dl_iterate_phdr loader PHDR enumeration"))
                .put("memory_read",status(bridgeLoaded || canReadSelfMem(),"native process_vm_readv/pread with java /proc/self/mem fallback"))
                .put("elf_info",status(true,"bounded ELF header/program header/GNU build-id parsing from file or APK entry"))
                .put("dex_file_backed",status(true,"runtime DexFile enumeration + readable backing file copy"))
                .put("dex_art_probe",status(true,"DexPathList/DexFile reflected ART cookie shape probe with optional validated header reconstruction"))
                .put("dex_file_info",status(true,"bounded file/APK DEX header and map-list parsing"))
                .put("dex_strings",status(true,"bounded file/APK DEX string table parsing"))
                .put("dex_classes",status(true,"bounded file/APK DEX class_def descriptor parsing"))
                .put("dex_memory_scan",status(bridgeLoaded,"bounded readable-map DEX magic/header candidate scan; not mCookie reconstruction"))
                .put("dex_art_memory_header",status(bridgeLoaded || canReadSelfMem(),"opt-in version-gated ART data_begin/file_size heuristic with DEX file_size/header/endian/map validation"))
                .put("dex_art_memory_tables",status(bridgeLoaded || canReadSelfMem(),"opt-in bounded strings/classes/fields/methods/class_data parsing from a validated ART memory DEX candidate"))
                .put("dex_art_memory_fingerprint",status(bridgeLoaded || canReadSelfMem(),"SHA-256 and DEX-signature fingerprinting of validated ART memory candidates with same-size originating APK classes*.dex correlation"))
                .put("dex_art_memory_dump",status(bridgeLoaded || canReadSelfMem(),"bounded raw byte reconstruction for high-confidence validated ART DEX candidates up to the 4 MiB inline cap with header/endian/size/SHA-256 revalidation"))
                .put("dex_art_memory_stream",status(bridgeLoaded || canReadSelfMem(),"token-bound chunked ART DEX export for validated readable candidates up to 512 MiB; each chunk is limited to 512 KiB and the DEX header is revalidated before every read"))
                .put("dex_art_memory",status(false,"arbitrary ART layouts, candidates above 512 MiB, and non-contiguous unreadable mappings remain unsupported; validated bounded and chunked raw export paths are exposed separately"))
                .put("assets",status(true,"runtime AssetManager list/open"))
                .put("xml_logical",status(true,"Resources.getXml"))
                .put("xml_block_probe",status(true,"XmlResourceParser/XmlBlock reflective field and event probe; no native byte export"))
                .put("xml_binary_apk",status(true,"file-backed APK binary XML via Resources.getValue or entry path"))
                .put("xml_binary_memory",status(false,"native XmlBlock/ResXMLTree recovery not implemented for this API"))
                .put("xml_axml_decode",status(true,"file-backed Android binary XML chunk/string-pool decode"))
                .put("xml_axml_text",status(true,"file-backed Android binary XML readable text rendering"))
                .put("apk_entries",status(true,"base/split APK ZipFile entry enumeration and bounded entry pull"));
    }


    private static JSONObject xmlAxmlDecode(Context context, JSONObject request) throws Exception {
        JSONObject binary = xmlBinary(context, request);
        if (!binary.optBoolean("ok", false)) return binary;
        byte[] bytes = Base64.decode(binary.getString("data"), Base64.NO_WRAP);
        int maxNodes = clamp(request.optInt("max_nodes", 1024), 1, 20_000);
        int maxAttributes = clamp(request.optInt("max_attributes", 256), 0, 4096);
        JSONObject decoded = decodeAxml(bytes, maxNodes, maxAttributes);
        return ok().put("source", new JSONObject()
                        .put("entry", binary.optString("entry", ""))
                        .put("apk_path", binary.optString("apk_path", ""))
                        .put("source", binary.optString("source", "")))
                .put("size", bytes.length)
                .put("sha256", sha256(bytes))
                .put("decoded", decoded)
                .put("binary_axml", true)
                .put("memory_reconstruction", false)
                .put("strategy", "file-backed Android binary XML chunk/string-pool decode; not native XmlBlock memory recovery");
    }

    private static JSONObject xmlAxmlText(Context context, JSONObject request) throws Exception {
        JSONObject decodedResult = xmlAxmlDecode(context, request);
        if (!decodedResult.optBoolean("ok", false)) return decodedResult;
        JSONObject decoded = decodedResult.getJSONObject("decoded");
        boolean includeDeclaration = request.optBoolean("include_declaration", true);
        String xml = renderAxmlText(decoded, includeDeclaration);
        return ok().put("source", decodedResult.optJSONObject("source"))
                .put("xml", xml)
                .put("decoded", decoded)
                .put("binary_axml", true)
                .put("memory_reconstruction", false)
                .put("strategy", "rendered text from file-backed Android binary XML decode; not native XmlBlock memory recovery");
    }

    private static JSONObject apkEntries(Context context, JSONObject request) throws Exception {
        Context apkContext = apkContext(context, request);
        String prefix = normalizeZipEntry(request.optString("prefix", ""));
        int max = clamp(request.optInt("max_entries", 5000), 1, 50000);
        JSONArray sources = new JSONArray();
        JSONArray entries = new JSONArray();
        boolean truncated = false;
        for (ApkSource source : apkSources(apkContext, request)) {
            sources.put(source.json());
            try (ZipFile zip = new ZipFile(source.path)) {
                java.util.Enumeration<? extends ZipEntry> all = zip.entries();
                while (all.hasMoreElements()) {
                    ZipEntry entry = all.nextElement();
                    if (entry.isDirectory()) continue;
                    String name = entry.getName();
                    if (!prefix.isEmpty() && !name.startsWith(prefix)) continue;
                    if (entries.length() >= max) { truncated = true; break; }
                    entries.put(new JSONObject()
                            .put("source", source.label)
                            .put("apk_path", source.path)
                            .put("name", name)
                            .put("size", entry.getSize())
                            .put("compressed_size", entry.getCompressedSize())
                            .put("crc", entry.getCrc())
                            .put("method", entry.getMethod()));
                }
            }
            if (truncated) break;
        }
        return ok().put("sources", sources).put("prefix", prefix).put("count", entries.length())
                .put("entries", entries).put("truncated", truncated)
                .put("strategy", "runtime ApplicationInfo sourceDir/splitSourceDirs + ZipFile");
    }

    private static JSONObject apkPull(Context context, JSONObject request) throws Exception {
        Context apkContext = apkContext(context, request);
        String sourceLabel = request.optString("source", "base");
        String name = normalizeZipEntry(request.getString("entry"));
        if (name.isBlank()) return error("ENTRY_REQUIRED", "entry is required");
        int max = clamp(request.optInt("max_bytes", MAX_INLINE_BYTES), 1, MAX_INLINE_BYTES);
        for (ApkSource source : apkSources(apkContext, request)) {
            if (!source.label.equals(sourceLabel) && !source.path.equals(sourceLabel)) continue;
            try (ZipFile zip = new ZipFile(source.path)) {
                ZipEntry entry = zip.getEntry(name);
                if (entry == null || entry.isDirectory()) return error("ENTRY_NOT_FOUND", sourceLabel + ":" + name);
                if (entry.getSize() > max) return error("ENTRY_TOO_LARGE", "entry exceeds inline max_bytes: " + entry.getSize());
                try (InputStream in = zip.getInputStream(entry)) {
                    byte[] bytes = readLimited(in, max + 1);
                    if (bytes.length > max) return error("ENTRY_TOO_LARGE", "entry exceeds inline max_bytes");
                    return ok().put("source", source.label).put("apk_path", source.path).put("entry", name)
                            .put("size", bytes.length).put("encoding", "base64")
                            .put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))
                            .put("strategy", "ZipFile entry pull from target APK file");
                }
            }
        }
        return error("APK_SOURCE_NOT_FOUND", sourceLabel);
    }

    private static Context apkContext(Context context, JSONObject request) throws Exception {
        String packageName = request.optString("apk_package", "").trim();
        if (packageName.isEmpty() || packageName.equals(context.getPackageName())) return context;
        return context.createPackageContext(packageName, Context.CONTEXT_IGNORE_SECURITY);
    }

    private static JSONObject pullApkEntryAnySource(Context context, String name, int max) throws Exception {
        for (ApkSource source : apkSources(context, null)) {
            try (ZipFile zip = new ZipFile(source.path)) {
                ZipEntry entry = zip.getEntry(name);
                if (entry == null || entry.isDirectory()) continue;
                if (entry.getSize() > max) return error("ENTRY_TOO_LARGE", "entry exceeds inline max_bytes: " + entry.getSize());
                try (InputStream in = zip.getInputStream(entry)) {
                    byte[] bytes = readLimited(in, max + 1);
                    if (bytes.length > max) return error("ENTRY_TOO_LARGE", "entry exceeds inline max_bytes");
                    return ok().put("source", source.label).put("apk_path", source.path).put("entry", name)
                            .put("size", bytes.length).put("sha256", sha256(bytes))
                            .put("encoding", "base64").put("data", Base64.encodeToString(bytes, Base64.NO_WRAP));
                }
            }
        }
        return error("ENTRY_NOT_FOUND", name);
    }

    private static JSONObject apkEntryMetadataAnySource(Context context, String name, int max) throws Exception {
        for (ApkSource source : apkSources(context, null)) {
            try (ZipFile zip = new ZipFile(source.path)) {
                ZipEntry entry = zip.getEntry(name);
                if (entry == null || entry.isDirectory()) continue;
                if (entry.getSize() > max) return error("ENTRY_TOO_LARGE", "entry exceeds metadata max_source_bytes: " + entry.getSize());
                try (InputStream in = zip.getInputStream(entry)) {
                    byte[] bytes = readLimited(in, max + 1);
                    if (bytes.length > max) return error("ENTRY_TOO_LARGE", "entry exceeds metadata max_source_bytes");
                    return ok().put("source", source.label).put("apk_path", source.path).put("entry", name)
                            .put("size", bytes.length).put("compressed_size", entry.getCompressedSize())
                            .put("crc", entry.getCrc()).put("sha256", sha256(bytes))
                            .put("encoding", "none").put("data_included", false)
                            .put("binary_axml_header", bytes.length >= 4 && (bytes[0] & 0xff) == 0x03 && (bytes[1] & 0xff) == 0x00 && (bytes[2] & 0xff) == 0x08 && (bytes[3] & 0xff) == 0x00);
                }
            }
        }
        return error("ENTRY_NOT_FOUND", name);
    }

    private static String safeResourceName(Context context, int id) {
        try { return context.getResources().getResourceName(id); } catch (Throwable ignored) { return ""; }
    }

    private static List<ApkSource> apkSources(Context context) {
        return apkSources(context, null);
    }

    private static List<ApkSource> apkSources(Context context, JSONObject request) {
        ArrayList<ApkSource> out = new ArrayList<>();
        if (request != null) {
            String explicit = request.optString("apk_path", "").trim();
            if (!explicit.isEmpty()) { out.add(new ApkSource("explicit", explicit)); return out; }
        }
        if (context.getApplicationInfo().sourceDir != null) out.add(new ApkSource("base", context.getApplicationInfo().sourceDir));
        String[] splits = context.getApplicationInfo().splitSourceDirs;
        if (splits != null) for (int i = 0; i < splits.length; i++) if (splits[i] != null) out.add(new ApkSource("split_" + i, splits[i]));
        return out;
    }

    private static String sha256(byte[] bytes) throws Exception {MessageDigest d=MessageDigest.getInstance("SHA-256");byte[] h=d.digest(bytes);StringBuilder out=new StringBuilder();for(byte b:h)out.append(String.format("%02x",b&0xff));return out.toString();}

    private static String normalizeZipEntry(String entry) {
        String v = entry == null ? "" : entry.trim();
        while (v.startsWith("/")) v = v.substring(1);
        if (v.contains("..") || v.contains("\\")) throw new IllegalArgumentException("zip entry may not contain .. or backslash");
        return v;
    }

    private static List<MapEntry> readMaps(int max) throws Exception {
        List<MapEntry> out=new ArrayList<>();
        try(BufferedReader reader=new BufferedReader(new FileReader("/proc/self/maps"))) {
            String line; while((line=reader.readLine())!=null && out.size()<max) { MapEntry e=parseMap(line); if(e!=null)out.add(e); }
        }
        return out;
    }

    private static MapEntry parseMap(String line) {
        try {
            String[] parts=line.trim().split("\\s+",6); if(parts.length<5)return null;
            String[] range=parts[0].split("-",2); long start=Long.parseUnsignedLong(range[0],16),end=Long.parseUnsignedLong(range[1],16);
            String[] device=parts[3].split(":",2); String path=parts.length>=6?parts[5]:"";
            return new MapEntry(start,end,parts[1],Long.parseUnsignedLong(parts[2],16),parts[3],Long.parseLong(parts[4]),path,line);
        } catch(Throwable ignored){return null;}
    }
    private static MapEntry findMapContaining(long address) throws Exception { return findMapContaining(readMaps(MAX_MAPS), address); }
    private static MapEntry findMapContaining(List<MapEntry> maps, long address) { for (MapEntry entry : maps) if (Long.compareUnsigned(address, entry.start) >= 0 && Long.compareUnsigned(address, entry.end) < 0) return entry; return null; }

    private static LinkedHashMap<String,List<MapEntry>> groupModules(List<MapEntry> maps) {
        LinkedHashMap<String,List<MapEntry>> out=new LinkedHashMap<>();
        for(MapEntry e:maps){if(e.path==null||e.path.isBlank()||e.path.startsWith("["))continue;out.computeIfAbsent(e.path,k->new ArrayList<>()).add(e);}return out;
    }
    private static JSONObject moduleJson(String path,List<MapEntry> maps)throws Exception{
        maps.sort(Comparator.comparingLong(MapEntry::start));JSONArray segments=new JSONArray();long mapped=0;for(MapEntry e:maps){segments.put(e.json());mapped+=e.size();}
        return new JSONObject().put("path",path).put("segment_count",segments.length()).put("mapped_bytes",mapped)
                .put("base",hex(maps.get(0).start)).put("segments",segments)
                .put("contiguous",isContiguous(maps));
    }
    private static boolean isContiguous(List<MapEntry> maps){for(int i=1;i<maps.size();i++)if(maps.get(i-1).end!=maps.get(i).start)return false;return true;}
    private static byte[] readMappedMemory(Context context,long address,int size)throws Exception{if(context!=null&&NativeBridge.ensureLoaded(context))try{return NativeBridge.readMemory(context,address,size);}catch(Throwable ignored){}return readSelfMemory(address,size);}
    private static byte[] readSelfMemory(long address,int size)throws Exception{try(RandomAccessFile f=new RandomAccessFile("/proc/self/mem","r")){f.seek(address);byte[] out=new byte[size];int off=0;while(off<size){int n=f.read(out,off,size-off);if(n<0)break;off+=n;}if(off==size)return out;byte[] shortOut=new byte[off];System.arraycopy(out,0,shortOut,0,off);return shortOut;}}
    private static boolean canReadSelfMem(){try{readSelfMemory(0,1);return true;}catch(Throwable ignored){return new File("/proc/self/mem").canRead();}}
    private static byte[] readZipEntry(File apk, String entryName, int max) throws Exception {
        if (!apk.isFile() || !apk.canRead()) throw new java.io.FileNotFoundException("APK not readable: " + apk);
        try (ZipFile zip = new ZipFile(apk)) {
            ZipEntry entry = zip.getEntry(entryName);
            if (entry == null || entry.isDirectory()) throw new java.io.FileNotFoundException("entry not found: " + entryName);
            if (entry.getSize() > max) throw new IllegalArgumentException("entry exceeds inline max_bytes: " + entry.getSize());
            try (InputStream in = zip.getInputStream(entry)) {
                byte[] bytes = readLimited(in, max + 1);
                if (bytes.length > max) throw new IllegalArgumentException("entry exceeds inline max_bytes");
                return bytes;
            }
        }
    }

    private static JSONObject parseElfInfo(byte[] bytes) throws Exception {
        if (bytes.length < 16 || bytes[0] != 0x7f || bytes[1] != 'E' || bytes[2] != 'L' || bytes[3] != 'F') return error("NOT_ELF", "missing ELF magic");
        int elfClass = bytes[4] & 0xff;
        int data = bytes[5] & 0xff;
        ByteOrder order = data == 2 ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN;
        ByteBuffer b = ByteBuffer.wrap(bytes).order(order);
        boolean is64 = elfClass == 2;
        if (elfClass != 1 && elfClass != 2) return error("UNSUPPORTED_ELF_CLASS", String.valueOf(elfClass));
        int minHeader = is64 ? 64 : 52;
        if (bytes.length < minHeader) return error("ELF_HEADER_TRUNCATED", String.valueOf(bytes.length));
        long entry = is64 ? u64(b,24) : u32(b,24);
        long phoff = is64 ? u64(b,32) : u32(b,28);
        long shoff = is64 ? u64(b,40) : u32(b,32);
        long flags = is64 ? u32(b,48) : u32(b,36);
        int ehsize = u16(b, is64 ? 52 : 40);
        int phentsize = u16(b, is64 ? 54 : 42);
        int phnum = u16(b, is64 ? 56 : 44);
        int shentsize = u16(b, is64 ? 58 : 46);
        int shnum = u16(b, is64 ? 60 : 48);
        int shstrndx = u16(b, is64 ? 62 : 50);
        int machine = u16(b,18);
        JSONObject out = new JSONObject()
                .put("class", is64 ? "ELF64" : "ELF32")
                .put("bits", is64 ? 64 : 32)
                .put("endian", data == 2 ? "big" : "little")
                .put("type", u16(b,16))
                .put("machine", machine)
                .put("machine_name", machineName(machine))
                .put("version", u32(b,20))
                .put("entry", hex(entry))
                .put("program_header_offset", phoff)
                .put("section_header_offset", shoff)
                .put("flags", hex(flags))
                .put("ehsize", ehsize)
                .put("phentsize", phentsize)
                .put("phnum", phnum)
                .put("shentsize", shentsize)
                .put("shnum", shnum)
                .put("shstrndx", shstrndx);
        JSONArray programHeaders = new JSONArray();
        JSONArray loads = new JSONArray();
        JSONArray notes = new JSONArray();
        String buildId = null;
        boolean phTruncated = false;
        long phEnd = phoff + (long) phentsize * phnum;
        if (phoff > 0 && phentsize > 0 && phnum > 0 && phEnd <= bytes.length) {
            for (int i = 0; i < phnum; i++) {
                int off = (int) (phoff + (long)i * phentsize);
                long type = u32(b, off);
                long pOffset, vaddr, fileSize, memSize, pFlags, align;
                if (is64) {
                    pFlags = u32(b, off + 4); pOffset = u64(b, off + 8); vaddr = u64(b, off + 16); fileSize = u64(b, off + 32); memSize = u64(b, off + 40); align = u64(b, off + 48);
                } else {
                    pOffset = u32(b, off + 4); vaddr = u32(b, off + 8); fileSize = u32(b, off + 16); memSize = u32(b, off + 20); pFlags = u32(b, off + 24); align = u32(b, off + 28);
                }
                JSONObject ph = new JSONObject().put("index", i).put("type", type).put("type_name", phdrTypeName(type))
                        .put("offset", pOffset).put("vaddr", hex(vaddr)).put("filesz", fileSize).put("memsz", memSize)
                        .put("flags", hex(pFlags)).put("flags_rwx", phdrFlags(pFlags)).put("align", align);
                programHeaders.put(ph);
                if (type == 1) loads.put(ph);
                if (type == 4) {
                    JSONObject note = new JSONObject().put("index", i).put("offset", pOffset).put("filesz", fileSize);
                    String found = parseBuildIdNote(bytes, (int)pOffset, (int)Math.min(fileSize, Integer.MAX_VALUE), order);
                    if (found != null) { note.put("gnu_build_id", found); if (buildId == null) buildId = found; }
                    notes.put(note);
                }
            }
        } else if (phnum > 0) phTruncated = true;
        return out.put("program_headers", programHeaders)
                .put("load_segments", loads).put("note_segments", notes)
                .put("gnu_build_id", buildId == null ? JSONObject.NULL : buildId)
                .put("program_headers_truncated", phTruncated);
    }

    private static JSONObject parseElfSymbols(byte[] bytes, int maxSymbols, boolean includeSymtab, String filter) throws Exception {
        if (bytes.length < 64 || bytes[0] != 0x7f || bytes[1] != 'E' || bytes[2] != 'L' || bytes[3] != 'F') return error("NOT_ELF", "missing ELF magic");
        int elfClass = bytes[4] & 0xff;
        int data = bytes[5] & 0xff;
        ByteOrder order = data == 2 ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN;
        ByteBuffer b = ByteBuffer.wrap(bytes).order(order);
        boolean is64 = elfClass == 2;
        long shoff = is64 ? u64(b,40) : u32(b,32);
        int shentsize = u16(b, is64 ? 58 : 46);
        int shnum = u16(b, is64 ? 60 : 48);
        JSONArray symbols = new JSONArray();
        JSONArray sections = new JSONArray();
        boolean truncated = false;
        if (shoff <= 0 || shentsize <= 0 || shnum <= 0 || shoff + (long)shentsize * shnum > bytes.length) {
            return ok().put("count", 0).put("symbols", symbols).put("sections", sections).put("section_headers_available", false);
        }
        for (int i = 0; i < shnum; i++) {
            int sh = (int)(shoff + (long)i * shentsize);
            long type = u32(b, sh + 4);
            if (type != 11 && !(includeSymtab && type == 2)) continue;
            long sectionOffset, sectionSize, entsize;
            int link;
            if (is64) { sectionOffset = u64(b, sh + 24); sectionSize = u64(b, sh + 32); link = (int)u32(b, sh + 40); entsize = u64(b, sh + 56); }
            else { sectionOffset = u32(b, sh + 16); sectionSize = u32(b, sh + 20); link = (int)u32(b, sh + 24); entsize = u32(b, sh + 36); }
            if (entsize <= 0) entsize = is64 ? 24 : 16;
            if (sectionOffset < 0 || sectionSize < 0 || sectionOffset + sectionSize > bytes.length) continue;
            long strOffset = 0, strSize = 0;
            if (link >= 0 && link < shnum) {
                int st = (int)(shoff + (long)link * shentsize);
                if (is64) { strOffset = u64(b, st + 24); strSize = u64(b, st + 32); }
                else { strOffset = u32(b, st + 16); strSize = u32(b, st + 20); }
            }
            sections.put(new JSONObject().put("index", i).put("type", type).put("type_name", type == 11 ? "SHT_DYNSYM" : "SHT_SYMTAB")
                    .put("offset", sectionOffset).put("size", sectionSize).put("entry_size", entsize).put("string_section", link));
            int count = (int)Math.min(sectionSize / entsize, 100000);
            for (int n = 0; n < count; n++) {
                if (symbols.length() >= maxSymbols) { truncated = true; break; }
                int so = (int)(sectionOffset + (long)n * entsize);
                int nameOff = (int)u32(b, so);
                long value, size; int info, other, shndx;
                if (is64) { info = bytes[so+4] & 0xff; other = bytes[so+5] & 0xff; shndx = u16(b, so+6); value = u64(b, so+8); size = u64(b, so+16); }
                else { value = u32(b, so+4); size = u32(b, so+8); info = bytes[so+12] & 0xff; other = bytes[so+13] & 0xff; shndx = u16(b, so+14); }
                String name = strz(bytes, (int)strOffset, (int)Math.min(strSize, Integer.MAX_VALUE), nameOff);
                if (name.isEmpty()) continue;
                if (filter != null && !filter.isEmpty() && !name.contains(filter)) continue;
                symbols.put(new JSONObject().put("section", i).put("index", n).put("name", name)
                        .put("value", hex(value)).put("size", size).put("bind", symBind(info)).put("type", symType(info))
                        .put("other", other).put("shndx", shndx).put("table", type == 11 ? "dynsym" : "symtab"));
            }
            if (truncated) break;
        }
        return ok().put("count", symbols.length()).put("symbols", symbols).put("sections", sections)
                .put("truncated", truncated).put("include_symtab", includeSymtab).put("section_headers_available", true);
    }

    private static JSONObject parseElfDynamic(byte[] bytes, int maxEntries) throws Exception {
        if (bytes.length < 64 || bytes[0] != 0x7f || bytes[1] != 'E' || bytes[2] != 'L' || bytes[3] != 'F') return error("NOT_ELF", "missing ELF magic");
        int elfClass = bytes[4] & 0xff;
        int data = bytes[5] & 0xff;
        ByteOrder order = data == 2 ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN;
        ByteBuffer b = ByteBuffer.wrap(bytes).order(order);
        boolean is64 = elfClass == 2;
        long shoff = is64 ? u64(b,40) : u32(b,32);
        int shentsize = u16(b, is64 ? 58 : 46);
        int shnum = u16(b, is64 ? 60 : 48);
        JSONArray entries = new JSONArray();
        JSONArray needed = new JSONArray();
        JSONArray sections = new JSONArray();
        String soname = null, rpath = null, runpath = null;
        boolean truncated = false;
        if (shoff <= 0 || shentsize <= 0 || shnum <= 0 || shoff + (long)shentsize * shnum > bytes.length) {
            return ok().put("entries", entries).put("needed", needed).put("section_headers_available", false).put("count", 0);
        }
        for (int i = 0; i < shnum; i++) {
            int sh = (int)(shoff + (long)i * shentsize);
            long type = u32(b, sh + 4);
            if (type != 6) continue; // SHT_DYNAMIC
            long dynOffset, dynSize, dynEntSize; int link;
            if (is64) { dynOffset = u64(b, sh + 24); dynSize = u64(b, sh + 32); link = (int)u32(b, sh + 40); dynEntSize = u64(b, sh + 56); }
            else { dynOffset = u32(b, sh + 16); dynSize = u32(b, sh + 20); link = (int)u32(b, sh + 24); dynEntSize = u32(b, sh + 36); }
            if (dynEntSize <= 0) dynEntSize = is64 ? 16 : 8;
            if (dynOffset < 0 || dynSize < 0 || dynOffset + dynSize > bytes.length) continue;
            long strOffset = 0, strSize = 0;
            if (link >= 0 && link < shnum) {
                int st = (int)(shoff + (long)link * shentsize);
                if (is64) { strOffset = u64(b, st + 24); strSize = u64(b, st + 32); }
                else { strOffset = u32(b, st + 16); strSize = u32(b, st + 20); }
            }
            sections.put(new JSONObject().put("index", i).put("offset", dynOffset).put("size", dynSize).put("entry_size", dynEntSize).put("string_section", link));
            int count = (int)Math.min(dynSize / dynEntSize, 100_000);
            for (int n = 0; n < count; n++) {
                if (entries.length() >= maxEntries) { truncated = true; break; }
                int off = (int)(dynOffset + (long)n * dynEntSize);
                long tag = is64 ? b.getLong(off) : b.getInt(off);
                long value = is64 ? u64(b, off + 8) : u32(b, off + 4);
                String name = dynamicTagName(tag);
                JSONObject item = new JSONObject().put("section", i).put("index", n).put("tag", tag).put("tag_name", name).put("value", hex(value));
                if (tag == 0) { entries.put(item); break; }
                if ((tag == 1 || tag == 14 || tag == 15 || tag == 29) && strSize > 0) {
                    String s = strz(bytes, (int)strOffset, (int)Math.min(strSize, Integer.MAX_VALUE), (int)value);
                    item.put("string", s);
                    if (tag == 1) needed.put(s);
                    else if (tag == 14) soname = s;
                    else if (tag == 15) rpath = s;
                    else if (tag == 29) runpath = s;
                }
                entries.put(item);
            }
            if (truncated) break;
        }
        return ok().put("count", entries.length()).put("entries", entries).put("sections", sections)
                .put("needed", needed).put("soname", soname == null ? JSONObject.NULL : soname)
                .put("rpath", rpath == null ? JSONObject.NULL : rpath).put("runpath", runpath == null ? JSONObject.NULL : runpath)
                .put("truncated", truncated).put("section_headers_available", true);
    }

    private static JSONObject parseElfRelocations(byte[] bytes, int maxRelocations, String filter) throws Exception {
        if (bytes.length < 64 || bytes[0] != 0x7f || bytes[1] != 'E' || bytes[2] != 'L' || bytes[3] != 'F') return error("NOT_ELF", "missing ELF magic");
        int elfClass = bytes[4] & 0xff;
        int data = bytes[5] & 0xff;
        ByteOrder order = data == 2 ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN;
        ByteBuffer b = ByteBuffer.wrap(bytes).order(order);
        boolean is64 = elfClass == 2;
        long shoff = is64 ? u64(b,40) : u32(b,32);
        int shentsize = u16(b, is64 ? 58 : 46);
        int shnum = u16(b, is64 ? 60 : 48);
        JSONArray relocs = new JSONArray();
        JSONArray sections = new JSONArray();
        boolean truncated = false;
        if (shoff <= 0 || shentsize <= 0 || shnum <= 0 || shoff + (long)shentsize * shnum > bytes.length) {
            return ok().put("count", 0).put("relocations", relocs).put("sections", sections).put("section_headers_available", false);
        }
        for (int i = 0; i < shnum; i++) {
            int sh = (int)(shoff + (long)i * shentsize);
            long type = u32(b, sh + 4);
            if (type != 4 && type != 9) continue; // SHT_RELA / SHT_REL
            long sectionOffset, sectionSize, entsize;
            int link;
            if (is64) { sectionOffset = u64(b, sh + 24); sectionSize = u64(b, sh + 32); link = (int)u32(b, sh + 40); entsize = u64(b, sh + 56); }
            else { sectionOffset = u32(b, sh + 16); sectionSize = u32(b, sh + 20); link = (int)u32(b, sh + 24); entsize = u32(b, sh + 36); }
            if (entsize <= 0) entsize = is64 ? (type == 4 ? 24 : 16) : (type == 4 ? 12 : 8);
            if (sectionOffset < 0 || sectionSize < 0 || sectionOffset + sectionSize > bytes.length) continue;
            sections.put(new JSONObject().put("index", i).put("type", type).put("type_name", type == 4 ? "SHT_RELA" : "SHT_REL")
                    .put("offset", sectionOffset).put("size", sectionSize).put("entry_size", entsize).put("symbol_section", link));
            int count = (int)Math.min(sectionSize / entsize, 200_000);
            for (int n = 0; n < count; n++) {
                if (relocs.length() >= maxRelocations) { truncated = true; break; }
                int ro = (int)(sectionOffset + (long)n * entsize);
                long rOffset, rInfo, addend = 0;
                if (is64) { rOffset = u64(b, ro); rInfo = u64(b, ro + 8); if (type == 4) addend = b.getLong(ro + 16); }
                else { rOffset = u32(b, ro); rInfo = u32(b, ro + 4); if (type == 4) addend = b.getInt(ro + 8); }
                long symIndex = is64 ? (rInfo >>> 32) : (rInfo >>> 8);
                long relType = is64 ? (rInfo & 0xffffffffL) : (rInfo & 0xffL);
                String symName = elfSymbolName(bytes, b, is64, shoff, shentsize, shnum, link, (int)symIndex);
                if (filter != null && !filter.isEmpty() && (symName == null || !symName.contains(filter))) continue;
                JSONObject item = new JSONObject().put("section", i).put("index", n).put("kind", type == 4 ? "RELA" : "REL")
                        .put("offset", hex(rOffset)).put("info", hex(rInfo)).put("symbol_index", symIndex)
                        .put("symbol", symName == null || symName.isEmpty() ? JSONObject.NULL : symName)
                        .put("type", relType).put("type_name", relocationTypeName(relType, u16(b,18)));
                if (type == 4) item.put("addend", addend);
                relocs.put(item);
            }
            if (truncated) break;
        }
        return ok().put("count", relocs.length()).put("relocations", relocs).put("sections", sections)
                .put("truncated", truncated).put("section_headers_available", true);
    }

    private static String parseBuildIdNote(byte[] bytes, int offset, int size, ByteOrder order) {
        try {
            if (offset < 0 || size <= 0 || offset >= bytes.length) return null;
            int end = Math.min(bytes.length, offset + size);
            ByteBuffer b = ByteBuffer.wrap(bytes).order(order);
            int pos = offset;
            while (pos + 12 <= end) {
                int namesz = (int)u32(b, pos); int descsz = (int)u32(b, pos + 4); int type = (int)u32(b, pos + 8); pos += 12;
                if (namesz < 0 || descsz < 0 || pos + align4(namesz) + align4(descsz) > end) return null;
                String name = namesz > 0 ? new String(bytes, pos, Math.max(0, namesz - 1), java.nio.charset.StandardCharsets.US_ASCII) : "";
                pos += align4(namesz);
                if (type == 3 && "GNU".equals(name) && descsz > 0) return hexBytes(bytes, pos, descsz);
                pos += align4(descsz);
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static int align4(int v) { return (v + 3) & ~3; }
    private static int u16(ByteBuffer b, int off) { return b.getShort(off) & 0xffff; }
    private static long u32(ByteBuffer b, int off) { return b.getInt(off) & 0xffffffffL; }
    private static long u64(ByteBuffer b, int off) { return b.getLong(off); }
    private static String hexBytes(byte[] bytes, int off, int len) { StringBuilder out = new StringBuilder(len * 2); for (int i=0;i<len && off+i<bytes.length;i++) out.append(String.format("%02x", bytes[off+i] & 0xff)); return out.toString(); }
    private static String elfSymbolName(byte[] bytes, ByteBuffer b, boolean is64, long shoff, int shentsize, int shnum, int symSection, int symIndex) {
        try {
            if (symSection < 0 || symSection >= shnum || symIndex < 0) return "";
            int sh = (int)(shoff + (long)symSection * shentsize);
            long type = u32(b, sh + 4);
            if (type != 11 && type != 2) return "";
            long sectionOffset, sectionSize, entsize; int link;
            if (is64) { sectionOffset = u64(b, sh + 24); sectionSize = u64(b, sh + 32); link = (int)u32(b, sh + 40); entsize = u64(b, sh + 56); }
            else { sectionOffset = u32(b, sh + 16); sectionSize = u32(b, sh + 20); link = (int)u32(b, sh + 24); entsize = u32(b, sh + 36); }
            if (entsize <= 0) entsize = is64 ? 24 : 16;
            long symOff = sectionOffset + (long)symIndex * entsize;
            if (symOff < 0 || symOff + 4 > bytes.length || symOff >= sectionOffset + sectionSize) return "";
            int nameOff = (int)u32(b, (int)symOff);
            if (link < 0 || link >= shnum) return "";
            int st = (int)(shoff + (long)link * shentsize);
            long strOffset, strSize;
            if (is64) { strOffset = u64(b, st + 24); strSize = u64(b, st + 32); }
            else { strOffset = u32(b, st + 16); strSize = u32(b, st + 20); }
            return strz(bytes, (int)strOffset, (int)Math.min(strSize, Integer.MAX_VALUE), nameOff);
        } catch (Throwable ignored) { return ""; }
    }
    private static String dynamicTagName(long tag) { return switch ((int)tag) { case 0 -> "DT_NULL"; case 1 -> "DT_NEEDED"; case 2 -> "DT_PLTRELSZ"; case 3 -> "DT_PLTGOT"; case 4 -> "DT_HASH"; case 5 -> "DT_STRTAB"; case 6 -> "DT_SYMTAB"; case 7 -> "DT_RELA"; case 8 -> "DT_RELASZ"; case 9 -> "DT_RELAENT"; case 10 -> "DT_STRSZ"; case 11 -> "DT_SYMENT"; case 12 -> "DT_INIT"; case 13 -> "DT_FINI"; case 14 -> "DT_SONAME"; case 15 -> "DT_RPATH"; case 16 -> "DT_SYMBOLIC"; case 17 -> "DT_REL"; case 18 -> "DT_RELSZ"; case 19 -> "DT_RELENT"; case 20 -> "DT_PLTREL"; case 21 -> "DT_DEBUG"; case 22 -> "DT_TEXTREL"; case 23 -> "DT_JMPREL"; case 24 -> "DT_BIND_NOW"; case 25 -> "DT_INIT_ARRAY"; case 26 -> "DT_FINI_ARRAY"; case 27 -> "DT_INIT_ARRAYSZ"; case 28 -> "DT_FINI_ARRAYSZ"; case 29 -> "DT_RUNPATH"; case 30 -> "DT_FLAGS"; case 32 -> "DT_PREINIT_ARRAY"; case 33 -> "DT_PREINIT_ARRAYSZ"; case 0x6ffffef5 -> "DT_GNU_HASH"; case 0x6ffffff0 -> "DT_VERSYM"; case 0x6ffffffe -> "DT_VERNEED"; case 0x6fffffff -> "DT_VERNEEDNUM"; case 0x6ffffffb -> "DT_FLAGS_1"; default -> "DT_" + tag; }; }

    private static String relocationTypeName(long type, int machine) {
        if (machine == 183) return switch ((int)type) { case 0 -> "R_AARCH64_NONE"; case 257 -> "R_AARCH64_ABS64"; case 1025 -> "R_AARCH64_GLOB_DAT"; case 1026 -> "R_AARCH64_JUMP_SLOT"; case 1027 -> "R_AARCH64_RELATIVE"; case 1032 -> "R_AARCH64_TLSDESC"; case 1037 -> "R_AARCH64_IRELATIVE"; default -> "R_AARCH64_" + type; };
        if (machine == 62) return switch ((int)type) { case 0 -> "R_X86_64_NONE"; case 1 -> "R_X86_64_64"; case 6 -> "R_X86_64_GLOB_DAT"; case 7 -> "R_X86_64_JUMP_SLOT"; case 8 -> "R_X86_64_RELATIVE"; default -> "R_X86_64_" + type; };
        if (machine == 40) return switch ((int)type) { case 0 -> "R_ARM_NONE"; case 2 -> "R_ARM_ABS32"; case 21 -> "R_ARM_GLOB_DAT"; case 22 -> "R_ARM_JUMP_SLOT"; case 23 -> "R_ARM_RELATIVE"; default -> "R_ARM_" + type; };
        return "R_" + machine + "_" + type;
    }

    private static String strz(byte[] bytes, int start, int size, int off) { if (off < 0 || start < 0 || size <= 0 || start + off >= bytes.length) return ""; int pos=start+off, end=Math.min(bytes.length, start+size); while(pos<end && bytes[pos]!=0) pos++; return new String(bytes, start+off, Math.max(0,pos-(start+off)), java.nio.charset.StandardCharsets.UTF_8); }
    private static String symBind(int info) { return switch ((info >>> 4) & 0xf) { case 0 -> "LOCAL"; case 1 -> "GLOBAL"; case 2 -> "WEAK"; case 10 -> "GNU_UNIQUE"; default -> "BIND_" + ((info >>> 4) & 0xf); }; }
    private static String symType(int info) { return switch (info & 0xf) { case 0 -> "NOTYPE"; case 1 -> "OBJECT"; case 2 -> "FUNC"; case 3 -> "SECTION"; case 4 -> "FILE"; case 5 -> "COMMON"; case 6 -> "TLS"; case 10 -> "GNU_IFUNC"; default -> "TYPE_" + (info & 0xf); }; }

    private static JSONObject dexFieldId(byte[] bytes, int fieldIndex) throws Exception {
        long total = u32le(bytes, 0x50); long base = u32le(bytes, 0x54);
        if (fieldIndex < 0 || fieldIndex >= total) return new JSONObject().put("index", fieldIndex).put("descriptor", "#field" + fieldIndex);
        int off = (int)(base + (long)fieldIndex * 8);
        int classIdx = u16le(bytes, off); int typeIdx = u16le(bytes, off + 2); int nameIdx = (int)u32le(bytes, off + 4);
        String owner = dexTypeDescriptor(bytes, classIdx); String type = dexTypeDescriptor(bytes, typeIdx); String name = dexStringByIndex(bytes, nameIdx);
        return new JSONObject().put("index", fieldIndex).put("class_idx", classIdx).put("class", owner)
                .put("type_idx", typeIdx).put("type", type).put("name_idx", nameIdx).put("name", name)
                .put("descriptor", owner + "->" + name + ":" + type);
    }
    private static JSONObject dexMethodId(byte[] bytes, int methodIndex) throws Exception {
        long total = u32le(bytes, 0x58); long base = u32le(bytes, 0x5c);
        if (methodIndex < 0 || methodIndex >= total) return new JSONObject().put("index", methodIndex).put("descriptor", "#method" + methodIndex);
        int off = (int)(base + (long)methodIndex * 8);
        int classIdx = u16le(bytes, off); int protoIdx = u16le(bytes, off + 2); int nameIdx = (int)u32le(bytes, off + 4);
        JSONObject proto = dexProto(bytes, protoIdx); String owner = dexTypeDescriptor(bytes, classIdx); String name = dexStringByIndex(bytes, nameIdx);
        return new JSONObject().put("index", methodIndex).put("class_idx", classIdx).put("class", owner)
                .put("proto_idx", protoIdx).put("proto", proto).put("name_idx", nameIdx).put("name", name)
                .put("descriptor", owner + "->" + name + proto.optString("descriptor", ""));
    }
    private static JSONObject dexCodeItem(byte[] bytes, int off) throws Exception {
        if (off <= 0 || off + 16 > bytes.length) return new JSONObject().put("truncated", true);
        return new JSONObject().put("registers_size", u16le(bytes, off)).put("ins_size", u16le(bytes, off + 2))
                .put("outs_size", u16le(bytes, off + 4)).put("tries_size", u16le(bytes, off + 6))
                .put("debug_info_off", u32le(bytes, off + 8)).put("insns_size", u32le(bytes, off + 12));
    }
    private static int[] uleb128(byte[] bytes, int pos) {
        int result = 0; int shift = 0; int p = pos;
        while (p < bytes.length && shift < 35) { int b = bytes[p++] & 0xff; result |= (b & 0x7f) << shift; if ((b & 0x80) == 0) break; shift += 7; }
        return new int[]{result, p};
    }

    private static JSONObject dexProto(byte[] bytes, int protoIndex) throws Exception {
        long total = u32le(bytes, 0x48);
        long base = u32le(bytes, 0x4c);
        if (protoIndex < 0 || protoIndex >= total) return new JSONObject().put("index", protoIndex).put("descriptor", "#proto" + protoIndex);
        int off = (int)(base + (long)protoIndex * 12);
        if (off < 0 || off + 12 > bytes.length) return new JSONObject().put("index", protoIndex).put("descriptor", "#proto" + protoIndex).put("truncated", true);
        int shortyIdx = (int)u32le(bytes, off);
        int returnTypeIdx = (int)u32le(bytes, off + 4);
        long parametersOff = u32le(bytes, off + 8);
        JSONArray params = dexProtoParameters(bytes, parametersOff);
        StringBuilder desc = new StringBuilder("(");
        for (int i=0; i<params.length(); i++) desc.append(params.getString(i));
        desc.append(')').append(dexTypeDescriptor(bytes, returnTypeIdx));
        return new JSONObject().put("index", protoIndex)
                .put("shorty_idx", shortyIdx).put("shorty", dexStringByIndex(bytes, shortyIdx))
                .put("return_type_idx", returnTypeIdx).put("return_type", dexTypeDescriptor(bytes, returnTypeIdx))
                .put("parameters_off", parametersOff).put("parameters", params)
                .put("descriptor", desc.toString());
    }
    private static JSONArray dexProtoParameters(byte[] bytes, long parametersOff) {
        JSONArray out = new JSONArray();
        try {
            if (parametersOff == 0 || parametersOff + 4 > bytes.length) return out;
            int count = (int)Math.min(u32le(bytes, (int)parametersOff), 10_000);
            int base = (int)parametersOff + 4;
            for (int i=0; i<count; i++) {
                int off = base + i * 2;
                if (off + 2 > bytes.length) break;
                out.put(dexTypeDescriptor(bytes, u16le(bytes, off)));
            }
        } catch (Throwable ignored) {}
        return out;
    }

    private static String machineName(int machine) { return switch (machine) { case 3 -> "EM_386"; case 40 -> "EM_ARM"; case 62 -> "EM_X86_64"; case 183 -> "EM_AARCH64"; default -> "EM_" + machine; }; }
    private static String phdrTypeName(long type) { return switch ((int)type) { case 0 -> "PT_NULL"; case 1 -> "PT_LOAD"; case 2 -> "PT_DYNAMIC"; case 3 -> "PT_INTERP"; case 4 -> "PT_NOTE"; case 5 -> "PT_SHLIB"; case 6 -> "PT_PHDR"; case 7 -> "PT_TLS"; case 0x6474e550 -> "PT_GNU_EH_FRAME"; case 0x6474e551 -> "PT_GNU_STACK"; case 0x6474e552 -> "PT_GNU_RELRO"; case 0x6474e553 -> "PT_GNU_PROPERTY"; default -> "PT_" + type; }; }
    private static String phdrFlags(long flags) { StringBuilder out = new StringBuilder(3); out.append((flags & 4) != 0 ? 'r' : '-'); out.append((flags & 2) != 0 ? 'w' : '-'); out.append((flags & 1) != 0 ? 'x' : '-'); return out.toString(); }

    private static JSONObject decodeAxml(byte[] bytes, int maxNodes, int maxAttributes) throws Exception {
        if (bytes.length < 8) return error("AXML_TOO_SHORT", String.valueOf(bytes.length));
        JSONArray chunks = new JSONArray();
        JSONArray nodes = new JSONArray();
        List<String> strings = new ArrayList<>();
        int[] resourceMap = new int[0];
        int rootType = u16le(bytes, 0);
        int rootSize = u32leInt(bytes, 4);
        int pos = rootType == 0x0003 ? 8 : 0;
        int depth = 0;
        boolean truncated = false;
        while (pos + 8 <= bytes.length) {
            int type = u16le(bytes, pos);
            int headerSize = u16le(bytes, pos + 2);
            int size = u32leInt(bytes, pos + 4);
            if (size < 8 || pos + size > bytes.length) break;
            chunks.put(new JSONObject().put("offset", pos).put("type", hex(type)).put("type_name", axmlChunkName(type)).put("size", size));
            if (type == 0x0001) strings = parseAxmlStringPool(bytes, pos, size);
            else if (type == 0x0180) {
                int count = Math.max(0, (size - headerSize) / 4);
                resourceMap = new int[count];
                for (int i=0;i<count;i++) resourceMap[i] = u32leInt(bytes, pos + headerSize + i*4);
            } else if (type == 0x0100 && nodes.length() < maxNodes) {
                nodes.put(new JSONObject().put("event", "start_namespace").put("depth", depth).put("line", u32leInt(bytes, pos + 8))
                        .put("prefix", axmlStringOrNull(strings, u32leInt(bytes, pos + 16)))
                        .put("uri", axmlStringOrNull(strings, u32leInt(bytes, pos + 20))));
            } else if (type == 0x0101 && nodes.length() < maxNodes) {
                nodes.put(new JSONObject().put("event", "end_namespace").put("depth", depth).put("line", u32leInt(bytes, pos + 8))
                        .put("prefix", axmlStringOrNull(strings, u32leInt(bytes, pos + 16)))
                        .put("uri", axmlStringOrNull(strings, u32leInt(bytes, pos + 20))));
            } else if (type == 0x0102 && nodes.length() < maxNodes) {
                int line = u32leInt(bytes, pos + 8);
                int ns = u32leInt(bytes, pos + 16);
                int nameIdx = u32leInt(bytes, pos + 20);
                int attrStart = u16le(bytes, pos + 24);
                int attrSize = u16le(bytes, pos + 26);
                int attrCount = u16le(bytes, pos + 28);
                JSONArray attrs = new JSONArray();
                int attrBase = pos + 16 + attrStart;
                for (int i=0; i<attrCount && i<maxAttributes; i++) {
                    int ao = attrBase + i * attrSize;
                    if (ao + 20 > pos + size || ao + 20 > bytes.length) break;
                    int attrNs = u32leInt(bytes, ao);
                    int attrName = u32leInt(bytes, ao + 4);
                    int rawValue = u32leInt(bytes, ao + 8);
                    int dataType = bytes[ao + 15] & 0xff;
                    int data = u32leInt(bytes, ao + 16);
                    JSONObject attr = new JSONObject().put("name", axmlString(strings, attrName)).put("namespace", axmlStringOrNull(strings, attrNs))
                            .put("resource_id", attrName >= 0 && attrName < resourceMap.length ? hex(resourceMap[attrName]) : JSONObject.NULL)
                            .put("raw", axmlStringOrNull(strings, rawValue)).put("data_type", dataType)
                            .put("data", hex(data)).put("value", axmlValue(strings, rawValue, dataType, data));
                    attrs.put(attr);
                }
                nodes.put(new JSONObject().put("event", "start_tag").put("depth", depth).put("line", line)
                        .put("name", axmlString(strings, nameIdx)).put("namespace", axmlStringOrNull(strings, ns))
                        .put("attribute_count", attrCount).put("attributes", attrs)
                        .put("attributes_truncated", attrCount > maxAttributes));
                depth++;
            } else if (type == 0x0103 && nodes.length() < maxNodes) {
                depth = Math.max(0, depth - 1);
                nodes.put(new JSONObject().put("event", "end_tag").put("depth", depth).put("line", u32leInt(bytes, pos + 8))
                        .put("name", axmlString(strings, u32leInt(bytes, pos + 20))));
            } else if (type == 0x0104 && nodes.length() < maxNodes) {
                nodes.put(new JSONObject().put("event", "text").put("depth", depth).put("line", u32leInt(bytes, pos + 8))
                        .put("text", axmlString(strings, u32leInt(bytes, pos + 16))));
            } else if ((type == 0x0100 || type == 0x0101 || type == 0x0102 || type == 0x0103 || type == 0x0104) && nodes.length() >= maxNodes) truncated = true;
            pos += size;
        }
        return ok().put("root_type", hex(rootType)).put("root_size", rootSize)
                .put("string_count", strings.size()).put("strings_preview", axmlStringPreview(strings, 32)).put("resource_count", resourceMap.length)
                .put("chunk_count", chunks.length()).put("chunks", chunks)
                .put("node_count", nodes.length()).put("nodes", nodes).put("nodes_truncated", truncated);
    }

    private static List<String> parseAxmlStringPool(byte[] bytes, int offset, int size) {
        ArrayList<String> out = new ArrayList<>();
        try {
            int stringCount = u32leInt(bytes, offset + 8);
            int flags = u32leInt(bytes, offset + 16);
            int stringsStart = u32leInt(bytes, offset + 20);
            boolean utf8 = (flags & 0x00000100) != 0;
            int offsetsBase = offset + 28;
            int stringsBase = offset + stringsStart;
            for (int i=0; i<stringCount && offsetsBase + i*4 + 4 <= offset + size; i++) {
                int strOff = u32leInt(bytes, offsetsBase + i*4);
                out.add(utf8 ? decodeAxmlUtf8(bytes, stringsBase + strOff, offset + size) : decodeAxmlUtf16(bytes, stringsBase + strOff, offset + size));
            }
        } catch (Throwable ignored) {}
        return out;
    }
    private static String decodeAxmlUtf8(byte[] bytes, int pos, int end) {
        int[] a = axmlLen8(bytes, pos, end); int p = a[1]; int[] b = axmlLen8(bytes, p, end); p = b[1]; int len = b[0];
        if (p < 0 || len < 0 || p + len > end || p + len > bytes.length) return "";
        return new String(bytes, p, len, java.nio.charset.StandardCharsets.UTF_8);
    }
    private static int[] axmlLen8(byte[] bytes, int pos, int end) { if (pos >= end) return new int[]{0,-1}; int v = bytes[pos++] & 0xff; if ((v & 0x80) != 0 && pos < end) v = ((v & 0x7f) << 8) | (bytes[pos++] & 0xff); return new int[]{v,pos}; }
    private static String decodeAxmlUtf16(byte[] bytes, int pos, int end) {
        if (pos + 2 > end) return ""; int len = u16le(bytes,pos); pos += 2; if ((len & 0x8000) != 0 && pos + 2 <= end) { len = ((len & 0x7fff) << 16) | u16le(bytes,pos); pos += 2; }
        int n = Math.max(0, Math.min(len * 2, end - pos));
        return new String(bytes, pos, n, java.nio.charset.StandardCharsets.UTF_16LE);
    }
    private static JSONArray axmlStringPreview(List<String> strings, int max) { JSONArray out = new JSONArray(); for (int i=0;i<strings.size() && i<max;i++) out.put(strings.get(i)); return out; }
    private static String axmlString(List<String> strings, int idx) { return idx >= 0 && idx < strings.size() ? strings.get(idx) : "#" + idx; }
    private static Object axmlStringOrNull(List<String> strings, int idx) { return idx >= 0 && idx < strings.size() ? strings.get(idx) : JSONObject.NULL; }
    private static Object axmlValue(List<String> strings, int raw, int type, int data) {
        if (raw >= 0 && raw < strings.size()) return strings.get(raw);
        return switch (type) { case 0x03 -> axmlString(strings, data); case 0x10 -> data; case 0x12 -> data != 0; default -> hex(data); };
    }
    private static int u16le(byte[] bytes, int off) { return ((bytes[off] & 0xff) | ((bytes[off+1] & 0xff) << 8)); }
    private static int u32leInt(byte[] bytes, int off) { return (bytes[off] & 0xff) | ((bytes[off+1] & 0xff) << 8) | ((bytes[off+2] & 0xff) << 16) | ((bytes[off+3] & 0xff) << 24); }
    private static String axmlChunkName(int type) { return switch (type) { case 0x0001 -> "RES_STRING_POOL_TYPE"; case 0x0003 -> "RES_XML_TYPE"; case 0x0180 -> "RES_XML_RESOURCE_MAP_TYPE"; case 0x0100 -> "RES_XML_START_NAMESPACE_TYPE"; case 0x0101 -> "RES_XML_END_NAMESPACE_TYPE"; case 0x0102 -> "RES_XML_START_ELEMENT_TYPE"; case 0x0103 -> "RES_XML_END_ELEMENT_TYPE"; case 0x0104 -> "RES_XML_CDATA_TYPE"; default -> "AXML_CHUNK_" + type; }; }

    private static JSONObject parseDexStrings(byte[] bytes, int maxStrings, String filter) throws Exception {
        JSONObject header = parseDexInfo(bytes);
        if (!header.optBoolean("ok", false)) return header;
        long total = u32le(bytes, 0x38);
        long idsOff = u32le(bytes, 0x3c);
        JSONArray values = new JSONArray();
        boolean truncated = false;
        for (int i=0; i<total; i++) {
            if (values.length() >= maxStrings) { truncated = true; break; }
            long entryOff = idsOff + (long)i * 4;
            if (entryOff < 0 || entryOff + 4 > bytes.length) { truncated = true; break; }
            long dataOff = u32le(bytes, (int)entryOff);
            String value = dexStringData(bytes, (int)dataOff);
            if (filter != null && !filter.isEmpty() && !value.contains(filter)) continue;
            values.put(new JSONObject().put("index", i).put("offset", dataOff).put("value", value));
        }
        return ok().put("total", total).put("count", values.length()).put("strings", values)
                .put("truncated", truncated).put("filter", filter == null || filter.isEmpty() ? JSONObject.NULL : filter);
    }

    private static JSONObject parseDexClasses(byte[] bytes, int maxClasses, String filter) throws Exception {
        JSONObject header = parseDexInfo(bytes);
        if (!header.optBoolean("ok", false)) return header;
        long classTotal = u32le(bytes, 0x60);
        long classOff = u32le(bytes, 0x64);
        JSONArray values = new JSONArray();
        boolean truncated = false;
        for (int i=0; i<classTotal; i++) {
            if (values.length() >= maxClasses) { truncated = true; break; }
            long off = classOff + (long)i * 32;
            if (off < 0 || off + 32 > bytes.length) { truncated = true; break; }
            int classIdx = (int)u32le(bytes, (int)off);
            int accessFlags = (int)u32le(bytes, (int)off + 4);
            int superIdx = (int)u32le(bytes, (int)off + 8);
            int sourceFileIdx = (int)u32le(bytes, (int)off + 16);
            String descriptor = dexTypeDescriptor(bytes, classIdx);
            if (filter != null && !filter.isEmpty() && !descriptor.contains(filter)) continue;
            values.put(new JSONObject().put("index", i).put("class_idx", classIdx).put("descriptor", descriptor)
                    .put("access_flags", hex(accessFlags))
                    .put("superclass_idx", superIdx == -1 ? JSONObject.NULL : superIdx)
                    .put("superclass", superIdx == -1 ? JSONObject.NULL : dexTypeDescriptor(bytes, superIdx))
                    .put("interfaces_off", u32le(bytes, (int)off + 12))
                    .put("source_file_idx", sourceFileIdx == -1 ? JSONObject.NULL : sourceFileIdx)
                    .put("source_file", sourceFileIdx == -1 ? JSONObject.NULL : dexStringByIndex(bytes, sourceFileIdx))
                    .put("annotations_off", u32le(bytes, (int)off + 20))
                    .put("class_data_off", u32le(bytes, (int)off + 24))
                    .put("static_values_off", u32le(bytes, (int)off + 28)));
        }
        return ok().put("total", classTotal).put("count", values.length()).put("classes", values)
                .put("truncated", truncated).put("filter", filter == null || filter.isEmpty() ? JSONObject.NULL : filter);
    }

    private static int dexEntryOrder(String name) {
        if ("classes.dex".equals(name)) return 1;
        try {
            String n = name.substring("classes".length(), name.length() - ".dex".length());
            return n.isEmpty() ? 1 : Integer.parseInt(n);
        } catch (Throwable ignored) { return Integer.MAX_VALUE; }
    }

    private static JSONObject parseDexFields(byte[] bytes, int maxFields, String filter) throws Exception {
        JSONObject header = parseDexInfo(bytes);
        if (!header.optBoolean("ok", false)) return header;
        long total = u32le(bytes, 0x50);
        long offBase = u32le(bytes, 0x54);
        JSONArray values = new JSONArray();
        boolean truncated = false;
        for (int i=0; i<total; i++) {
            if (values.length() >= maxFields) { truncated = true; break; }
            long off = offBase + (long)i * 8;
            if (off < 0 || off + 8 > bytes.length) { truncated = true; break; }
            int classIdx = u16le(bytes, (int)off);
            int typeIdx = u16le(bytes, (int)off + 2);
            int nameIdx = (int)u32le(bytes, (int)off + 4);
            String owner = dexTypeDescriptor(bytes, classIdx);
            String type = dexTypeDescriptor(bytes, typeIdx);
            String name = dexStringByIndex(bytes, nameIdx);
            String descriptor = owner + "->" + name + ":" + type;
            if (filter != null && !filter.isEmpty() && !descriptor.contains(filter)) continue;
            values.put(new JSONObject().put("index", i).put("class_idx", classIdx).put("class", owner)
                    .put("type_idx", typeIdx).put("type", type)
                    .put("name_idx", nameIdx).put("name", name)
                    .put("descriptor", descriptor));
        }
        return ok().put("total", total).put("count", values.length()).put("fields", values)
                .put("truncated", truncated).put("filter", filter == null || filter.isEmpty() ? JSONObject.NULL : filter);
    }

    private static JSONObject parseDexMethods(byte[] bytes, int maxMethods, String filter) throws Exception {
        JSONObject header = parseDexInfo(bytes);
        if (!header.optBoolean("ok", false)) return header;
        long total = u32le(bytes, 0x58);
        long offBase = u32le(bytes, 0x5c);
        JSONArray values = new JSONArray();
        boolean truncated = false;
        for (int i=0; i<total; i++) {
            if (values.length() >= maxMethods) { truncated = true; break; }
            long off = offBase + (long)i * 8;
            if (off < 0 || off + 8 > bytes.length) { truncated = true; break; }
            int classIdx = u16le(bytes, (int)off);
            int protoIdx = u16le(bytes, (int)off + 2);
            int nameIdx = (int)u32le(bytes, (int)off + 4);
            JSONObject proto = dexProto(bytes, protoIdx);
            String owner = dexTypeDescriptor(bytes, classIdx);
            String name = dexStringByIndex(bytes, nameIdx);
            String signature = owner + "->" + name + proto.optString("descriptor", "");
            if (filter != null && !filter.isEmpty() && !signature.contains(filter)) continue;
            values.put(new JSONObject().put("index", i).put("class_idx", classIdx).put("class", owner)
                    .put("proto_idx", protoIdx).put("proto", proto)
                    .put("name_idx", nameIdx).put("name", name)
                    .put("descriptor", signature));
        }
        return ok().put("total", total).put("count", values.length()).put("methods", values)
                .put("truncated", truncated).put("filter", filter == null || filter.isEmpty() ? JSONObject.NULL : filter);
    }

    private static JSONObject parseDexClassData(byte[] bytes, int maxClasses, int maxMembers, String filter) throws Exception {
        JSONObject header = parseDexInfo(bytes);
        if (!header.optBoolean("ok", false)) return header;
        long classTotal = u32le(bytes, 0x60);
        long classOff = u32le(bytes, 0x64);
        JSONArray values = new JSONArray();
        boolean truncated = false;
        for (int i=0; i<classTotal; i++) {
            if (values.length() >= maxClasses) { truncated = true; break; }
            long off = classOff + (long)i * 32;
            if (off < 0 || off + 32 > bytes.length) { truncated = true; break; }
            int classIdx = (int)u32le(bytes, (int)off);
            String descriptor = dexTypeDescriptor(bytes, classIdx);
            if (filter != null && !filter.isEmpty() && !descriptor.contains(filter)) continue;
            long classDataOff = u32le(bytes, (int)off + 24);
            JSONObject rec = new JSONObject().put("index", i).put("class_idx", classIdx).put("descriptor", descriptor)
                    .put("class_data_off", classDataOff);
            if (classDataOff == 0) {
                rec.put("has_class_data", false);
            } else if (classDataOff < 0 || classDataOff >= bytes.length) {
                rec.put("has_class_data", true).put("truncated", true).put("error", "class_data_off outside buffer");
            } else {
                rec.put("has_class_data", true).put("data", parseDexClassDataItem(bytes, (int)classDataOff, maxMembers));
            }
            values.put(rec);
        }
        return ok().put("total", classTotal).put("count", values.length()).put("classes", values)
                .put("truncated", truncated).put("filter", filter == null || filter.isEmpty() ? JSONObject.NULL : filter)
                .put("member_limit", maxMembers);
    }

    private static JSONObject parseDexClassDataItem(byte[] bytes, int pos, int maxMembers) throws Exception {
        int[] r = uleb128(bytes, pos); int staticFieldsSize = r[0]; pos = r[1];
        r = uleb128(bytes, pos); int instanceFieldsSize = r[0]; pos = r[1];
        r = uleb128(bytes, pos); int directMethodsSize = r[0]; pos = r[1];
        r = uleb128(bytes, pos); int virtualMethodsSize = r[0]; pos = r[1];
        JSONObject out = new JSONObject()
                .put("static_fields_size", staticFieldsSize).put("instance_fields_size", instanceFieldsSize)
                .put("direct_methods_size", directMethodsSize).put("virtual_methods_size", virtualMethodsSize);
        JSONArray staticFields = new JSONArray();
        JSONArray instanceFields = new JSONArray();
        JSONArray directMethods = new JSONArray();
        JSONArray virtualMethods = new JSONArray();
        int fieldIndex = 0;
        for (int i=0; i<staticFieldsSize; i++) { r=uleb128(bytes,pos); fieldIndex += r[0]; pos=r[1]; r=uleb128(bytes,pos); int flags=r[0]; pos=r[1]; if (staticFields.length()<maxMembers) staticFields.put(dexFieldId(bytes, fieldIndex).put("access_flags", hex(flags)).put("order", i)); }
        fieldIndex = 0;
        for (int i=0; i<instanceFieldsSize; i++) { r=uleb128(bytes,pos); fieldIndex += r[0]; pos=r[1]; r=uleb128(bytes,pos); int flags=r[0]; pos=r[1]; if (instanceFields.length()<maxMembers) instanceFields.put(dexFieldId(bytes, fieldIndex).put("access_flags", hex(flags)).put("order", i)); }
        int methodIndex = 0;
        for (int i=0; i<directMethodsSize; i++) { r=uleb128(bytes,pos); methodIndex += r[0]; pos=r[1]; r=uleb128(bytes,pos); int flags=r[0]; pos=r[1]; r=uleb128(bytes,pos); int codeOff=r[0]; pos=r[1]; if (directMethods.length()<maxMembers) directMethods.put(dexMethodId(bytes, methodIndex).put("access_flags", hex(flags)).put("code_off", codeOff).put("code", codeOff == 0 ? JSONObject.NULL : dexCodeItem(bytes, codeOff)).put("order", i)); }
        methodIndex = 0;
        for (int i=0; i<virtualMethodsSize; i++) { r=uleb128(bytes,pos); methodIndex += r[0]; pos=r[1]; r=uleb128(bytes,pos); int flags=r[0]; pos=r[1]; r=uleb128(bytes,pos); int codeOff=r[0]; pos=r[1]; if (virtualMethods.length()<maxMembers) virtualMethods.put(dexMethodId(bytes, methodIndex).put("access_flags", hex(flags)).put("code_off", codeOff).put("code", codeOff == 0 ? JSONObject.NULL : dexCodeItem(bytes, codeOff)).put("order", i)); }
        return out.put("static_fields", staticFields).put("instance_fields", instanceFields)
                .put("direct_methods", directMethods).put("virtual_methods", virtualMethods)
                .put("static_fields_truncated", staticFieldsSize > maxMembers)
                .put("instance_fields_truncated", instanceFieldsSize > maxMembers)
                .put("direct_methods_truncated", directMethodsSize > maxMembers)
                .put("virtual_methods_truncated", virtualMethodsSize > maxMembers);
    }

    private static JSONObject parseDexInfo(byte[] bytes) throws Exception {
        if (bytes.length < 0x70) return error("DEX_TOO_SHORT", String.valueOf(bytes.length));
        if (bytes[0] != 'd' || bytes[1] != 'e' || bytes[2] != 'x' || bytes[3] != '\n' || bytes[7] != 0) return error("NOT_DEX", "missing dex magic");
        String version = new String(bytes, 4, 3, java.nio.charset.StandardCharsets.US_ASCII);
        JSONObject out = ok().put("version", version)
                .put("checksum", hex(u32le(bytes, 8)))
                .put("signature", hexBytes(bytes, 12, 20))
                .put("file_size", u32le(bytes, 0x20))
                .put("header_size", u32le(bytes, 0x24))
                .put("endian_tag", hex(u32le(bytes, 0x28)))
                .put("link_size", u32le(bytes, 0x2c)).put("link_off", u32le(bytes, 0x30))
                .put("map_off", u32le(bytes, 0x34))
                .put("string_ids_size", u32le(bytes, 0x38)).put("string_ids_off", u32le(bytes, 0x3c))
                .put("type_ids_size", u32le(bytes, 0x40)).put("type_ids_off", u32le(bytes, 0x44))
                .put("proto_ids_size", u32le(bytes, 0x48)).put("proto_ids_off", u32le(bytes, 0x4c))
                .put("field_ids_size", u32le(bytes, 0x50)).put("field_ids_off", u32le(bytes, 0x54))
                .put("method_ids_size", u32le(bytes, 0x58)).put("method_ids_off", u32le(bytes, 0x5c))
                .put("class_defs_size", u32le(bytes, 0x60)).put("class_defs_off", u32le(bytes, 0x64))
                .put("data_size", u32le(bytes, 0x68)).put("data_off", u32le(bytes, 0x6c))
                .put("art_memory_reconstruction", false);
        long mapOff = u32le(bytes, 0x34);
        JSONArray map = new JSONArray();
        boolean mapTruncated = false;
        if (mapOff > 0 && mapOff + 4 <= bytes.length) {
            int count = (int)Math.min(u32le(bytes, (int)mapOff), 100_000);
            int base = (int)mapOff + 4;
            for (int i=0; i<count; i++) {
                int off = base + i * 12;
                if (off + 12 > bytes.length) { mapTruncated = true; break; }
                int type = u16le(bytes, off);
                int unused = u16le(bytes, off + 2);
                long size = u32le(bytes, off + 4);
                long itemOff = u32le(bytes, off + 8);
                map.put(new JSONObject().put("index", i).put("type", hex(type)).put("type_name", dexMapTypeName(type))
                        .put("unused", unused).put("size", size).put("offset", itemOff));
                if (map.length() >= 4096) { mapTruncated = count > map.length(); break; }
            }
        }
        return out.put("map_items", map).put("map_items_count", map.length()).put("map_items_truncated", mapTruncated);
    }

    private static String dexTypeDescriptor(byte[] bytes, int typeIndex) {
        try {
            long typeTotal = u32le(bytes, 0x40);
            long typeOff = u32le(bytes, 0x44);
            if (typeIndex < 0 || typeIndex >= typeTotal) return "#type" + typeIndex;
            int stringIndex = (int)u32le(bytes, (int)(typeOff + (long)typeIndex * 4));
            return dexStringByIndex(bytes, stringIndex);
        } catch (Throwable error) { return "#type" + typeIndex; }
    }
    private static String dexStringByIndex(byte[] bytes, int stringIndex) {
        try {
            long total = u32le(bytes, 0x38);
            long idsOff = u32le(bytes, 0x3c);
            if (stringIndex < 0 || stringIndex >= total) return "#string" + stringIndex;
            long dataOff = u32le(bytes, (int)(idsOff + (long)stringIndex * 4));
            return dexStringData(bytes, (int)dataOff);
        } catch (Throwable error) { return "#string" + stringIndex; }
    }
    private static String dexStringData(byte[] bytes, int off) {
        if (off < 0 || off >= bytes.length) return "";
        int p = off;
        while (p < bytes.length) { int b = bytes[p++] & 0xff; if ((b & 0x80) == 0) break; }
        int start = p;
        while (p < bytes.length && bytes[p] != 0) p++;
        return new String(bytes, start, Math.max(0, p - start), java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String dexMapTypeName(int type) {
        return switch (type) {
            case 0x0000 -> "TYPE_HEADER_ITEM"; case 0x0001 -> "TYPE_STRING_ID_ITEM"; case 0x0002 -> "TYPE_TYPE_ID_ITEM";
            case 0x0003 -> "TYPE_PROTO_ID_ITEM"; case 0x0004 -> "TYPE_FIELD_ID_ITEM"; case 0x0005 -> "TYPE_METHOD_ID_ITEM";
            case 0x0006 -> "TYPE_CLASS_DEF_ITEM"; case 0x1000 -> "TYPE_MAP_LIST"; case 0x1001 -> "TYPE_TYPE_LIST";
            case 0x1002 -> "TYPE_ANNOTATION_SET_REF_LIST"; case 0x1003 -> "TYPE_ANNOTATION_SET_ITEM"; case 0x2000 -> "TYPE_CLASS_DATA_ITEM";
            case 0x2001 -> "TYPE_CODE_ITEM"; case 0x2002 -> "TYPE_STRING_DATA_ITEM"; case 0x2003 -> "TYPE_DEBUG_INFO_ITEM";
            case 0x2004 -> "TYPE_ANNOTATION_ITEM"; case 0x2005 -> "TYPE_ENCODED_ARRAY_ITEM"; case 0x2006 -> "TYPE_ANNOTATIONS_DIRECTORY_ITEM";
            case 0x2007 -> "TYPE_HIDDENAPI_CLASS_DATA_ITEM"; default -> "TYPE_" + type;
        };
    }

    private static String renderAxmlText(JSONObject decoded, boolean includeDeclaration) throws Exception {
        JSONArray nodes = decoded.optJSONArray("nodes");
        if (nodes == null) return "";
        StringBuilder out = new StringBuilder();
        if (includeDeclaration) out.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n");
        java.util.LinkedHashMap<String, String> uriToPrefix = new java.util.LinkedHashMap<>();
        java.util.LinkedHashMap<String, String> pendingNamespaces = new java.util.LinkedHashMap<>();
        for (int i=0; i<nodes.length(); i++) {
            JSONObject node = nodes.getJSONObject(i);
            String event = node.optString("event", "");
            if ("start_namespace".equals(event)) {
                String prefix = jsonString(node.opt("prefix"));
                String uri = jsonString(node.opt("uri"));
                if (uri != null && !uri.isEmpty()) {
                    uriToPrefix.put(uri, prefix == null ? "" : prefix);
                    pendingNamespaces.put(prefix == null ? "" : prefix, uri);
                }
            } else if ("end_namespace".equals(event)) {
                String uri = jsonString(node.opt("uri"));
                if (uri != null) uriToPrefix.remove(uri);
            } else if ("start_tag".equals(event)) {
                int depth = Math.max(0, node.optInt("depth", 0));
                indent(out, depth);
                out.append('<').append(qName(jsonString(node.opt("namespace")), node.optString("name", ""), uriToPrefix));
                JSONArray attrs = node.optJSONArray("attributes");
                for (Map.Entry<String,String> ns : pendingNamespaces.entrySet()) {
                    out.append(' ');
                    if (ns.getKey() == null || ns.getKey().isEmpty()) out.append("xmlns");
                    else out.append("xmlns:").append(ns.getKey());
                    out.append("=\"").append(escapeXml(ns.getValue())).append('"');
                }
                pendingNamespaces.clear();
                if (attrs != null) for (int a=0; a<attrs.length(); a++) {
                    JSONObject attr = attrs.getJSONObject(a);
                    out.append(' ').append(qName(jsonString(attr.opt("namespace")), attr.optString("name", ""), uriToPrefix))
                            .append("=\"").append(escapeXml(String.valueOf(attr.opt("value")))).append('"');
                }
                out.append(">\n");
            } else if ("end_tag".equals(event)) {
                int depth = Math.max(0, node.optInt("depth", 0));
                indent(out, depth);
                out.append("</").append(qName(jsonString(node.opt("namespace")), node.optString("name", ""), uriToPrefix)).append(">\n");
            } else if ("text".equals(event)) {
                indent(out, Math.max(0, node.optInt("depth", 0)));
                out.append(escapeXml(node.optString("text", ""))).append('\n');
            }
        }
        return out.toString();
    }
    private static String jsonString(Object value) { return value == null || value == JSONObject.NULL ? null : String.valueOf(value); }
    private static String qName(String uri, String name, Map<String,String> uriToPrefix) {
        if (uri == null || uri.isEmpty()) return name;
        String prefix = uriToPrefix.get(uri);
        return prefix == null || prefix.isEmpty() ? name : prefix + ":" + name;
    }
    private static void indent(StringBuilder out, int depth) { for (int i=0; i<depth; i++) out.append("  "); }

    private static byte[] readFile(File file,int max)throws Exception{try(InputStream in=new FileInputStream(file)){return readLimited(in,max);}}
    private static byte[] readLimited(InputStream in,int max)throws Exception{ByteArrayOutputStream out=new ByteArrayOutputStream(Math.min(max,65536));byte[] buffer=new byte[16384];while(out.size()<max){int n=in.read(buffer,0,Math.min(buffer.length,max-out.size()));if(n<0)break;out.write(buffer,0,n);}return out.toByteArray();}
    private static void listAssets(AssetManager assets,String path,JSONArray out,int max)throws Exception{if(out.length()>=max)return;String[] children=assets.list(path);if(children==null||children.length==0){if(!path.isEmpty())out.put(path);return;}for(String child:children){if(out.length()>=max)return;String next=path.isEmpty()?child:path+"/"+child;String[] grand=assets.list(next);if(grand!=null&&grand.length>0)listAssets(assets,next,out,max);else out.put(next);}}
    private static String normalizeAsset(String path){String v=path==null?"":path.trim();while(v.startsWith("/"))v=v.substring(1);if(v.contains(".."))throw new IllegalArgumentException("asset path may not contain ..");return v;}
    private static JSONObject cookieInfo(Object dex){if(dex==null)return null;try{JSONObject out=new JSONObject();for(String name:new String[]{"mCookie","mInternalCookie"}){Object v=field(dex,name);if(v!=null)out.put(name,summarizeCookie(v));}return out.length()==0?null:out;}catch(Throwable ignored){return null;}}
    private static Object field(Object object,String name)throws Exception{Class<?> c=object.getClass();while(c!=null){try{Field f=c.getDeclaredField(name);f.setAccessible(true);return f.get(object);}catch(NoSuchFieldException e){c=c.getSuperclass();}}return null;}
    private static JSONObject analyzeCookie(JSONObject cookie) throws Exception {
        JSONObject out = new JSONObject().put("field_count", 0).put("value_count", 0).put("fields", new JSONArray());
        if (cookie == null) return out;
        JSONArray fields = new JSONArray();
        int valueCount = 0;
        for (String name : new String[]{"mCookie", "mInternalCookie"}) {
            if (!cookie.has(name) || cookie.isNull(name)) continue;
            Object value = cookie.get(name);
            int count = value instanceof JSONArray ? ((JSONArray)value).length() : 1;
            fields.put(new JSONObject().put("name", name).put("shape", value instanceof JSONArray ? "array" : "scalar").put("count", count));
            valueCount += count;
        }
        return out.put("field_count", fields.length()).put("value_count", valueCount).put("fields", fields);
    }

    private static Object summarizeCookie(Object value)throws Exception{if(value==null)return JSONObject.NULL;if(value.getClass().isArray()){JSONArray a=new JSONArray();int n=Math.min(java.lang.reflect.Array.getLength(value),32);for(int i=0;i<n;i++)a.put(String.valueOf(java.lang.reflect.Array.get(value,i)));return a;}return String.valueOf(value);}
    private static boolean looksLikeDexHeader(byte[] bytes, int off) {
        if (off < 0 || off + 0x70 > bytes.length) return false;
        if (bytes[off] != 'd' || bytes[off+1] != 'e' || bytes[off+2] != 'x' || bytes[off+3] != '\n' || bytes[off+7] != 0) return false;
        int v0 = bytes[off+4], v1 = bytes[off+5], v2 = bytes[off+6];
        if (v0 < '0' || v0 > '9' || v1 < '0' || v1 > '9' || v2 < '0' || v2 > '9') return false;
        long fileSize = u32le(bytes, off + 0x20);
        long headerSize = u32le(bytes, off + 0x24);
        long endian = u32le(bytes, off + 0x28);
        return fileSize >= 0x70 && headerSize == 0x70 && (endian == 0x12345678L || endian == 0x78563412L);
    }
    private static long u32le(byte[] bytes, int off) {
        return ((long)bytes[off] & 0xff) | (((long)bytes[off+1] & 0xff) << 8) | (((long)bytes[off+2] & 0xff) << 16) | (((long)bytes[off+3] & 0xff) << 24);
    }
    private static long u64le(byte[] bytes, int off) {
        return ((long)bytes[off] & 0xff) | (((long)bytes[off+1] & 0xff) << 8) | (((long)bytes[off+2] & 0xff) << 16) | (((long)bytes[off+3] & 0xff) << 24)
                | (((long)bytes[off+4] & 0xff) << 32) | (((long)bytes[off+5] & 0xff) << 40) | (((long)bytes[off+6] & 0xff) << 48) | (((long)bytes[off+7] & 0xff) << 56);
    }

    private static long parseAddress(Object value){String text=String.valueOf(value).trim().toLowerCase();if(text.startsWith("0x"))text=text.substring(2);return Long.parseUnsignedLong(text,16);}
    private static String hex(long value){return "0x"+Long.toUnsignedString(value,16);}
    private static int clamp(int v,int min,int max){return Math.max(min,Math.min(max,v));}
    private static String escapeXml(String v){if(v==null)return "";return v.replace("&","&amp;").replace("\"","&quot;").replace("<","&lt;").replace(">","&gt;");}
    private static JSONObject status(boolean supported,String reason)throws Exception{return new JSONObject().put("supported",supported).put("reason",reason);}
    private static JSONObject unsupported(String capability,String reason,JSONArray strategies)throws Exception{return ok().put("supported",false).put("capability",capability).put("reason",reason).put("strategies",strategies);}
    private static JSONObject ok()throws Exception{return new JSONObject().put("ok",true);}private static JSONObject error(String c,String m)throws Exception{return new JSONObject().put("ok",false).put("error",new JSONObject().put("code",c).put("message",m));}

    private record DexBytes(byte[] bytes, JSONObject source, boolean truncated){}
    private record ElfBytes(byte[] bytes, JSONObject source, boolean truncated){}
    private record ApkSource(String label,String path){JSONObject json()throws Exception{return new JSONObject().put("label",label).put("path",path);}}

    private record MapEntry(long start,long end,String permissions,long offset,String device,long inode,String path,String raw){
        long size(){return end-start;} JSONObject json()throws Exception{return new JSONObject().put("start",hex(start)).put("end",hex(end)).put("size",size()).put("permissions",permissions).put("offset",hex(offset)).put("device",device).put("inode",inode).put("pathname",path==null||path.isEmpty()?JSONObject.NULL:path);}
    }
}
