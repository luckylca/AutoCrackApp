package com.luckylca.runtimeinspector.testapp;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.webkit.WebView;

public final class MainActivity extends Activity {
    private TextView target;
    private int clicks;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (android.os.Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout root = new LinearLayout(this);
        root.setId(R.id.inspect_root);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(48, 80, 48, 48);
        root.setBackgroundColor(Color.WHITE);

        TextView title = label("Runtime Inspector Test", 24f);
        root.addView(title, matchWrap());

        target = label("Inspector Target", 22f);
        target.setId(R.id.inspect_target_text);
        target.setPadding(24, 30, 24, 30);
        target.setBackgroundColor(0xffe8eef8);
        root.addView(target, matchWrap());

        Button click = new Button(this);
        click.setId(R.id.inspect_click_button);
        click.setText("Click Target");
        click.setOnClickListener(new TargetClickListener());
        root.addView(click, matchWrap());

        LinearLayout nested = new LinearLayout(this);
        nested.setOrientation(LinearLayout.VERTICAL);
        nested.setPadding(32, 24, 32, 24);
        TextView nestedLabel = label("Nested Inspector Label", 18f);
        nestedLabel.setId(R.id.inspect_nested_label);
        nested.addView(nestedLabel, matchWrap());
        root.addView(nested, matchWrap());

        Button dialog = new Button(this);
        dialog.setId(R.id.inspect_dialog_button);
        dialog.setText("Open Inspector Dialog");
        dialog.setOnClickListener(v -> showInspectorDialog());
        root.addView(dialog, matchWrap());

        WebView.setWebContentsDebuggingEnabled(true);
        WebView webView = new WebView(this);
        webView.setId(R.id.inspect_webview);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.loadDataWithBaseURL(
                "https://autocrack.test/",
                "<html><body><h3>AutoCrack WebView Fixture</h3><script>document.body.dataset.ready='1';</script></body></html>",
                "text/html",
                "UTF-8",
                null);
        root.addView(webView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 360));

        setContentView(root);
    }

    private void showInspectorDialog() {
        Dialog dialog = new Dialog(this);
        TextView text = label("Inspector Dialog Window", 22f);
        text.setId(R.id.inspect_dialog_text);
        text.setPadding(80, 80, 80, 80);
        text.setBackgroundColor(Color.WHITE);
        dialog.setContentView(text);
        dialog.show();
    }

    private TextView label(String text, float sp) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(sp);
        view.setTextColor(Color.BLACK);
        return view;
    }

    private static ViewGroup.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    public final class TargetClickListener implements View.OnClickListener {
        @Override
        public void onClick(View view) {
            clicks++;
            target.setText("Clicked " + clicks);
        }
    }
}
