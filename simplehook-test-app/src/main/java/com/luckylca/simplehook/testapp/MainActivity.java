package com.luckylca.simplehook.testapp;

import android.app.Activity;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        HookTargets targets = new HookTargets("activity");
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(32, 32, 32, 32);
        addLine(layout, "getInt=" + targets.getInt());
        addLine(layout, "getBoolean=" + targets.getBoolean());
        addLine(layout, "getString=" + targets.getString());
        addLine(layout, "add=" + targets.add(2, 3));
        addLine(layout, targets.overload(5));
        addLine(layout, targets.overload("five"));
        addLine(layout, "fields=" + HookTargets.staticField + "/" + targets.instanceField);
        setContentView(layout);
    }

    public void loadDelayedTarget() throws ClassNotFoundException {
        Class.forName("com.luckylca.simplehook.testapp.DelayedTarget", true, getClassLoader());
    }

    private void addLine(LinearLayout layout, String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(18f);
        view.setPadding(0, 8, 0, 8);
        layout.addView(view);
    }
}
