package com.luckylca.autocrack

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.luckylca.autocrack.ui.AutoCrackApp
import com.luckylca.autocrack.ui.MobileAgentLaunchRoute
import com.luckylca.autocrack.ui.MobileAgentRouteRequest

class MainActivity : ComponentActivity() {
    private var routeRequest by mutableStateOf<MobileAgentRouteRequest?>(null)
    private var routeSequence = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        routeRequest = intent.toRouteRequest()
        enableEdgeToEdge()
        setContent {
            AutoCrackApp(routeRequest = routeRequest, onRouteConsumed = { routeRequest = null })
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        routeRequest = intent.toRouteRequest()
    }

    private fun Intent.toRouteRequest(): MobileAgentRouteRequest? {
        val route = when (action) {
            ACTION_OPEN_CONVERSATION -> getStringExtra(EXTRA_CONVERSATION_ID)
                ?.takeIf(String::isNotBlank)
                ?.let(MobileAgentLaunchRoute::Conversation)
            ACTION_OPEN_TERMINAL -> MobileAgentLaunchRoute.Terminal
            else -> null
        } ?: return null
        routeSequence += 1
        return MobileAgentRouteRequest(routeSequence, route)
    }

    companion object {
        const val ACTION_OPEN_CONVERSATION = "com.luckylca.autocrack.action.OPEN_CONVERSATION"
        const val ACTION_OPEN_TERMINAL = "com.luckylca.autocrack.action.OPEN_TERMINAL"
        const val EXTRA_CONVERSATION_ID = "conversation_id"
    }
}
