package com.luckylca.simplehook.runtime;

import android.content.Context;
import com.luckylca.simplehook.core.SimpleHookLimits;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import org.json.JSONArray;
import org.json.JSONObject;

final class JsonLogStore {
    private final File directory;
    private final File active;

    JsonLogStore(Context context) {
        directory = new File(context.createDeviceProtectedStorageContext().getFilesDir(), "logs");
        active = new File(directory, "simplehook.jsonl");
    }

    synchronized void append(JSONObject entry) throws Exception {
        directory.mkdirs();
        byte[] bytes = (entry.toString() + "\n").getBytes(StandardCharsets.UTF_8);
        if (bytes.length > SimpleHookLimits.MAX_LOG_ENTRY_BYTES) {
            entry = new JSONObject().put("timestamp", System.currentTimeMillis())
                    .put("package", entry.opt("package"))
                    .put("rule_id", entry.opt("rule_id"))
                    .put("phase", "dropped").put("error", "LOG_ENTRY_TOO_LARGE");
            bytes = (entry.toString() + "\n").getBytes(StandardCharsets.UTF_8);
        }
        if (active.length() + bytes.length > SimpleHookLimits.MAX_LOG_FILE_BYTES) rotate();
        try (FileOutputStream output = new FileOutputStream(active, true)) {
            output.write(bytes);
        }
    }

    synchronized JSONArray query(String ruleId, String packageName, int limit) throws Exception {
        int bounded = Math.max(1, Math.min(limit, 2000));
        ArrayDeque<JSONObject> selected = new ArrayDeque<>();
        for (int index = SimpleHookLimits.MAX_LOG_FILES - 1; index >= 0; index--) {
            File file = index == 0 ? active : new File(directory, "simplehook.jsonl." + index);
            if (!file.isFile()) continue;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    new FileInputStream(file), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    JSONObject item = new JSONObject(line);
                    if (ruleId != null && !ruleId.equals(item.optString("rule_id"))) continue;
                    if (packageName != null && !packageName.equals(item.optString("package"))) continue;
                    selected.addLast(item);
                    while (selected.size() > bounded) selected.removeFirst();
                }
            }
        }
        return new JSONArray(selected);
    }

    private void rotate() {
        File oldest = new File(directory, "simplehook.jsonl." + (SimpleHookLimits.MAX_LOG_FILES - 1));
        if (oldest.exists()) oldest.delete();
        for (int i = SimpleHookLimits.MAX_LOG_FILES - 2; i >= 1; i--) {
            File source = new File(directory, "simplehook.jsonl." + i);
            if (source.exists()) source.renameTo(new File(directory, "simplehook.jsonl." + (i + 1)));
        }
        if (active.exists()) active.renameTo(new File(directory, "simplehook.jsonl.1"));
    }
}
