package com.luckylca.autocrack.agent

object AgentQuestionPlanner {
    fun expandTerms(question: String): List<String> {
        val normalized = question.trim().lowercase()
        if (normalized.isBlank()) return emptyList()

        val terms = linkedSetOf<String>()
        TOKEN_REGEX.findAll(normalized).forEach { match ->
            val token = match.value.trim('.', '/', '-', '_')
            if (token.length >= 2 && token !in STOP_WORDS) terms += token
        }

        EXPANSIONS.forEach { (triggers, values) ->
            if (triggers.any(normalized::contains)) {
                terms += values
            }
        }

        return terms
            .map(String::trim)
            .filter { it.length >= 2 }
            .distinct()
            .take(MAX_TERMS)
    }

    private const val MAX_TERMS = 18
    private val TOKEN_REGEX = Regex("[\\p{L}\\p{N}_.$/-]{2,}")
    private val STOP_WORDS = setOf(
        "帮我", "分析", "这个", "应用", "app", "apk", "一下", "什么", "怎么", "是否",
        "the", "and", "for", "with", "this", "that", "from", "into", "does", "how",
    )

    private val EXPANSIONS = listOf(
        setOf("登录", "认证", "login", "auth", "账号") to listOf(
            "login", "auth", "token", "password", "session", "oauth", "credential", "account",
        ),
        setOf("加密", "解密", "签名", "encrypt", "decrypt", "crypto") to listOf(
            "encrypt", "decrypt", "cipher", "aes", "rsa", "sha", "hmac", "digest", "signature",
            "keystore", "secretkey", "publickey", "privatekey",
        ),
        setOf("网络", "请求", "接口", "http", "api", "抓包") to listOf(
            "http", "https", "okhttp", "retrofit", "request", "response", "url", "socket",
            "websocket", "interceptor", "dns",
        ),
        setOf("证书", "ssl", "tls", "pinning", "中间人") to listOf(
            "certificate", "trustmanager", "hostnameverifier", "ssl", "tls", "pinning", "x509",
        ),
        setOf("动态加载", "热更新", "dexclassloader", "插件") to listOf(
            "dexclassloader", "pathclassloader", "baseDexClassLoader", "loadclass", "loadlibrary",
            "system.load", "system.loadlibrary", "dynamic",
        ),
        setOf("root", "越狱", "magisk", "kernelsu") to listOf(
            "root", "su", "magisk", "kernelsu", "busybox", "mount", "test-keys",
        ),
        setOf("反调试", "调试检测", "frida", "xposed", "hook") to listOf(
            "ptrace", "tracerpid", "debugger", "isdebuggerconnected", "frida", "xposed", "substrate",
            "hook", "gum-js-loop",
        ),
        setOf("webview", "网页", "javascript") to listOf(
            "webview", "javascriptinterface", "addjavascriptinterface", "setjavascriptenabled", "loadurl",
        ),
        setOf("数据库", "存储", "sqlite", "sharedpreferences") to listOf(
            "sqlite", "database", "room", "sharedpreferences", "datastore", "realm", "mmkv",
        ),
        setOf("隐私", "定位", "通讯录", "设备标识") to listOf(
            "location", "contacts", "imei", "android_id", "advertisingid", "clipboard", "camera",
            "microphone", "bluetooth", "telephony",
        ),
    )
}
