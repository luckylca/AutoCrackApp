package com.luckylca.runtimeinspector.runtime;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

public final class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView view = new TextView(this);
        view.setPadding(48, 96, 48, 48);
        view.setTextSize(18f);
        view.setText("Runtime Inspector is installed.\n\nEnable this module in LSPosed and scope only apps you are authorized to inspect.\n\nThe CLI communicates through the Runtime Inspector provider.");
        setContentView(view);
    }
}
