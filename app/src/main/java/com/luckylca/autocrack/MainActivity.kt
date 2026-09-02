package com.luckylca.autocrack

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.luckylca.autocrack.agent.MobileAgentTaskCoordinator
import com.luckylca.autocrack.agent.MobileAgentTaskSnapshot
import com.luckylca.autocrack.agent.MobileAgentTaskStatus
import com.luckylca.autocrack.ui.AutoCrackApp
import com.luckylca.autocrack.ui.MobileAgentLaunchRoute
import com.luckylca.autocrack.ui.MobileAgentPictureInPictureState
import com.luckylca.autocrack.ui.MobileAgentRouteRequest
import com.luckylca.autocrack.ui.nextPictureInPictureState

class MainActivity : ComponentActivity() {
    private var routeRequest by mutableStateOf<MobileAgentRouteRequest?>(null)
    private var pictureInPictureState by mutableStateOf<MobileAgentPictureInPictureState?>(null)
    private var inPictureInPictureMode by mutableStateOf(false)
    private var routeSequence = 0L
    private var lastPictureInPictureParamsKey: Pair<Boolean, String>? = null
    private val taskCoordinator by lazy { MobileAgentTaskCoordinator.get(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        routeRequest = intent.toRouteRequest()
        enableEdgeToEdge()
        setContent {
            val tasks by taskCoordinator.tasks.collectAsState()
            SideEffect { synchronizePictureInPictureState(tasks) }
            AutoCrackApp(
                routeRequest = routeRequest,
                onRouteConsumed = { routeRequest = null },
                pictureInPictureState = pictureInPictureState.takeIf { inPictureInPictureMode },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        routeRequest = intent.toRouteRequest()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S &&
            pictureInPictureState?.isRunning == true &&
            supportsPictureInPicture()
        ) {
            runCatching { enterPictureInPictureMode(buildPictureInPictureParams(autoEnter = false)) }
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPictureInPictureMode = isInPictureInPictureMode
        if (!isInPictureInPictureMode && pictureInPictureState?.isRunning != true) {
            pictureInPictureState = null
        }
    }

    private fun synchronizePictureInPictureState(tasks: Map<String, MobileAgentTaskSnapshot>) {
        val hasRunningTask = tasks.values.any { it.status == MobileAgentTaskStatus.RUNNING }
        val nextState = nextPictureInPictureState(tasks, pictureInPictureState)
            .takeIf { hasRunningTask || inPictureInPictureMode }
        if (pictureInPictureState != nextState) pictureInPictureState = nextState

        val stage = nextState?.stage.orEmpty()
        val paramsKey = hasRunningTask to stage
        if (lastPictureInPictureParamsKey != paramsKey) {
            lastPictureInPictureParamsKey = paramsKey
            updatePictureInPictureParams(hasRunningTask, stage)
        }
    }

    private fun updatePictureInPictureParams(autoEnter: Boolean, stage: String) {
        if (!supportsPictureInPicture()) return
        runCatching { setPictureInPictureParams(buildPictureInPictureParams(autoEnter, stage)) }
    }

    private fun buildPictureInPictureParams(
        autoEnter: Boolean,
        stage: String = pictureInPictureState?.stage.orEmpty(),
    ): PictureInPictureParams = PictureInPictureParams.Builder()
        .setAspectRatio(Rational(16, 9))
        .apply {
            val sourceRect = Rect()
            if (window.decorView.getGlobalVisibleRect(sourceRect) && !sourceRect.isEmpty) {
                setSourceRectHint(sourceRect)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (autoEnter) setAutoEnterEnabled(true) else setAutoEnterEnabled(false)
                setSeamlessResizeEnabled(false)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                setTitle("模型输出")
                setSubtitle(stage)
            }
        }
        .build()

    private fun supportsPictureInPicture(): Boolean =
        packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

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
