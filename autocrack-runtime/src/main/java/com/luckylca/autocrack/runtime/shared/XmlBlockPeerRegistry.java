package com.luckylca.autocrack.runtime.shared;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import org.json.JSONObject;

/**
 * Weak target-process registry for XmlBlock native peers observed by the Xposed bootstrap.
 *
 * <p>This never guesses ART object offsets. Entries exist only when Xposed/LSPosed actually
 * intercepted XmlBlock.newParser(int) and could read the hidden framework fields there.</p>
 */
public final class XmlBlockPeerRegistry {
    private static final XmlBlockPeerRegistry INSTANCE = new XmlBlockPeerRegistry();
    private final Map<Object, Peer> peers = Collections.synchronizedMap(new WeakHashMap<>());

    public static XmlBlockPeerRegistry get() { return INSTANCE; }
    private XmlBlockPeerRegistry() {}

    public void record(Object parser, Object block, long nativeTree, long parseState, int sourceResId) {
        if (parser == null) return;
        peers.put(parser, new Peer(block, nativeTree, parseState, sourceResId, System.currentTimeMillis()));
    }

    public JSONObject describe(Object parser) throws Exception {
        Peer peer = parser == null ? null : peers.get(parser);
        if (peer == null) {
            return new JSONObject().put("ok", true)
                    .put("captured", false)
                    .put("xposed_required", true)
                    .put("reason", "No Xposed XmlBlock.newParser capture exists for this parser. The target process may not have loaded the current AutoCrack Runtime module yet.");
        }
        Object block = peer.block.get();
        return new JSONObject().put("ok", true)
                .put("captured", true)
                .put("capture_strategy", "Xposed XmlBlock.newParser(int) after-hook + XposedHelpers hidden-field access")
                .put("native_tree", hex(peer.nativeTree))
                .put("native_tree_nonzero", peer.nativeTree != 0L)
                .put("parse_state", hex(peer.parseState))
                .put("parse_state_nonzero", peer.parseState != 0L)
                .put("source_res_id", peer.sourceResId)
                .put("captured_at_ms", peer.capturedAtMs)
                .put("block_alive", block != null)
                .put("block_class", block == null ? JSONObject.NULL : block.getClass().getName())
                .put("art_object_offset_guessing", false);
    }

    private static String hex(long value) { return "0x" + Long.toUnsignedString(value, 16); }

    private static final class Peer {
        final WeakReference<Object> block;
        final long nativeTree;
        final long parseState;
        final int sourceResId;
        final long capturedAtMs;

        Peer(Object block, long nativeTree, long parseState, int sourceResId, long capturedAtMs) {
            this.block = new WeakReference<>(block);
            this.nativeTree = nativeTree;
            this.parseState = parseState;
            this.sourceResId = sourceResId;
            this.capturedAtMs = capturedAtMs;
        }
    }
}
