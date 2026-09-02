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
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private MemoryIntrospector() {}

    public static boolean supports(String kind) {
        return Set.of(
                "memory.maps", "memory.modules", "memory.native.modules", "memory.read", "memory.native.probe", "memory.dladdr", "memory.module.dump", "memory.module.file_dump",
                "memory.dex.list", "memory.dex.dump", "memory.assets.list", "memory.assets.pull",
                "memory.xml.pull", "memory.xml.binary", "memory.apk.entries", "memory.apk.pull", "memory.capabilities").contains(kind);
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
            case "memory.dex.list" -> dexList(request);
            case "memory.dex.dump" -> dexDump(request);
            case "memory.assets.list" -> assetsList(context, request);
            case "memory.assets.pull" -> assetsPull(context, request);
            case "memory.xml.pull" -> xmlPull(context, request);
            case "memory.xml.binary" -> xmlBinary(context, request);
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
                .put("dex_file_backed",status(true,"runtime DexFile enumeration + readable backing file copy"))
                .put("dex_art_memory",status(false,"ART DexFile native pointer/cookie reconstruction is version-specific and not implemented for API "+android.os.Build.VERSION.SDK_INT))
                .put("assets",status(true,"runtime AssetManager list/open"))
                .put("xml_logical",status(true,"Resources.getXml"))
                .put("xml_binary_apk",status(true,"file-backed APK binary XML via Resources.getValue or entry path"))
                .put("xml_binary_memory",status(false,"native XmlBlock/ResXMLTree recovery not implemented for this API"))
                .put("apk_entries",status(true,"base/split APK ZipFile entry enumeration and bounded entry pull"));
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
    private static MapEntry findMapContaining(long address) throws Exception { for (MapEntry entry : readMaps(MAX_MAPS)) if (address >= entry.start && address < entry.end) return entry; return null; }

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
    private static byte[] readFile(File file,int max)throws Exception{try(InputStream in=new FileInputStream(file)){return readLimited(in,max);}}
    private static byte[] readLimited(InputStream in,int max)throws Exception{ByteArrayOutputStream out=new ByteArrayOutputStream(Math.min(max,65536));byte[] buffer=new byte[16384];while(out.size()<max){int n=in.read(buffer,0,Math.min(buffer.length,max-out.size()));if(n<0)break;out.write(buffer,0,n);}return out.toByteArray();}
    private static void listAssets(AssetManager assets,String path,JSONArray out,int max)throws Exception{if(out.length()>=max)return;String[] children=assets.list(path);if(children==null||children.length==0){if(!path.isEmpty())out.put(path);return;}for(String child:children){if(out.length()>=max)return;String next=path.isEmpty()?child:path+"/"+child;String[] grand=assets.list(next);if(grand!=null&&grand.length>0)listAssets(assets,next,out,max);else out.put(next);}}
    private static String normalizeAsset(String path){String v=path==null?"":path.trim();while(v.startsWith("/"))v=v.substring(1);if(v.contains(".."))throw new IllegalArgumentException("asset path may not contain ..");return v;}
    private static JSONObject cookieInfo(Object dex){if(dex==null)return null;try{JSONObject out=new JSONObject();for(String name:new String[]{"mCookie","mInternalCookie"}){Object v=field(dex,name);if(v!=null)out.put(name,summarizeCookie(v));}return out.length()==0?null:out;}catch(Throwable ignored){return null;}}
    private static Object field(Object object,String name)throws Exception{Class<?> c=object.getClass();while(c!=null){try{Field f=c.getDeclaredField(name);f.setAccessible(true);return f.get(object);}catch(NoSuchFieldException e){c=c.getSuperclass();}}return null;}
    private static Object summarizeCookie(Object value)throws Exception{if(value==null)return JSONObject.NULL;if(value.getClass().isArray()){JSONArray a=new JSONArray();int n=Math.min(java.lang.reflect.Array.getLength(value),32);for(int i=0;i<n;i++)a.put(String.valueOf(java.lang.reflect.Array.get(value,i)));return a;}return String.valueOf(value);}
    private static long parseAddress(Object value){String text=String.valueOf(value).trim().toLowerCase();if(text.startsWith("0x"))text=text.substring(2);return Long.parseUnsignedLong(text,16);}
    private static String hex(long value){return "0x"+Long.toUnsignedString(value,16);}
    private static int clamp(int v,int min,int max){return Math.max(min,Math.min(max,v));}
    private static String escapeXml(String v){if(v==null)return "";return v.replace("&","&amp;").replace("\"","&quot;").replace("<","&lt;").replace(">","&gt;");}
    private static JSONObject status(boolean supported,String reason)throws Exception{return new JSONObject().put("supported",supported).put("reason",reason);}
    private static JSONObject unsupported(String capability,String reason,JSONArray strategies)throws Exception{return ok().put("supported",false).put("capability",capability).put("reason",reason).put("strategies",strategies);}
    private static JSONObject ok()throws Exception{return new JSONObject().put("ok",true);}private static JSONObject error(String c,String m)throws Exception{return new JSONObject().put("ok",false).put("error",new JSONObject().put("code",c).put("message",m));}

    private record ApkSource(String label,String path){JSONObject json()throws Exception{return new JSONObject().put("label",label).put("path",path);}}

    private record MapEntry(long start,long end,String permissions,long offset,String device,long inode,String path,String raw){
        long size(){return end-start;} JSONObject json()throws Exception{return new JSONObject().put("start",hex(start)).put("end",hex(end)).put("size",size()).put("permissions",permissions).put("offset",hex(offset)).put("device",device).put("inode",inode).put("pathname",path==null||path.isEmpty()?JSONObject.NULL:path);}
    }
}
