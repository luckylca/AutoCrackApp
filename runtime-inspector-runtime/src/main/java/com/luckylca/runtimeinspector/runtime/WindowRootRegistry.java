package com.luckylca.runtimeinspector.runtime;

import android.view.View;
import com.luckylca.autocrack.runtime.shared.WindowRegistry;
import java.util.List;

final class WindowRootRegistry {
    private WindowRootRegistry() {}

    static void install() throws Throwable { WindowRegistry.get().install(); }
    static List<View> snapshot() { return WindowRegistry.get().snapshot(64); }
}
