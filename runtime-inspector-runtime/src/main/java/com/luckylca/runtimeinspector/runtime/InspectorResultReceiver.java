package com.luckylca.runtimeinspector.runtime;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import org.json.JSONObject;

public final class InspectorResultReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!InspectorChannel.ACTION_RESULT.equals(intent.getAction())) return;
        try {
            JSONObject payload = new JSONObject(intent.getStringExtra(InspectorChannel.JSON));
            Bundle extras = new Bundle();
            extras.putString("json", payload.toString());
            context.getContentResolver().call(InspectorChannel.URI, "complete", null, extras);
        } catch (Throwable error) {
            android.util.Log.e("RuntimeInspector", "Rejected result broadcast", error);
        }
    }
}
