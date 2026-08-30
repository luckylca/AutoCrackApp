package com.luckylca.simplehook.runtime;

import android.net.Uri;

final class SimpleHookContract {
    static final String AUTHORITY = "com.luckylca.simplehook.runtime";
    static final Uri URI = Uri.parse("content://" + AUTHORITY);
    static final String RESULT_JSON = "json";

    private SimpleHookContract() {}
}
