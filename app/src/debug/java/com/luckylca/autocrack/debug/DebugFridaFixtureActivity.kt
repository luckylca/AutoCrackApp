package com.luckylca.autocrack.debug

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

/** Debug-only, separate-process Java target for Frida/LLDB validation. */
class DebugFridaFixtureActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = TextView(this).apply {
            text = "AutoCrack debug instrumentation fixture"
            contentDescription = "autocrack-frida-fixture"
        }
        setContentView(text)
    }
}
