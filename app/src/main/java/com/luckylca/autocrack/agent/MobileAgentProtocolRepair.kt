package com.luckylca.autocrack.agent

import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

internal data class MobileAgentProtocolRepairResult(
    val messages: List<MobileAgentMessage>,
    val synthesizedToolResults: Int,
    val droppedOrphanToolResults: Int,
)

/** Makes persisted interrupted tool rounds valid before they are sent to a model again. */
internal object MobileAgentProtocolRepair {
    fun repair(messages: List<MobileAgentMessage>): MobileAgentProtocolRepairResult {
        val repaired = mutableListOf<MobileAgentMessage>()
        val pendingCalls = linkedMapOf<String, String>()
        var synthesized = 0
        var droppedOrphans = 0

        fun finishPendingRound() {
            pendingCalls.forEach { (callId, toolName) ->
                repaired += MobileAgentMessage(
                    id = UUID.randomUUID().toString(),
                    role = MobileAgentRole.TOOL,
                    content = JSONObject()
                        .put("ok", false)
                        .put("cancelled", true)
                        .put("interrupted", true)
                        .put("error", "上次 Agent 进程在工具返回前中断；未自动重复可能产生副作用的操作")
                        .toString(),
                    createdAtEpochMillis = System.currentTimeMillis(),
                    toolCallId = callId,
                    toolName = toolName,
                )
                synthesized += 1
            }
            pendingCalls.clear()
        }

        messages.forEach { message ->
            when {
                message.role == MobileAgentRole.ASSISTANT && message.toolCallsJson != null -> {
                    finishPendingRound()
                    repaired += message
                    val calls = runCatching { JSONArray(message.toolCallsJson) }.getOrElse {
                        throw IllegalArgumentException("历史 toolCalls JSON 无效：${message.id}", it)
                    }
                    for (index in 0 until calls.length()) {
                        val call = calls.optJSONObject(index) ?: continue
                        val callId = call.optString("id").takeIf(String::isNotBlank) ?: continue
                        val toolName = call.optJSONObject("function")?.optString("name").orEmpty()
                        pendingCalls[callId] = toolName
                    }
                }

                message.role == MobileAgentRole.TOOL -> {
                    val callId = message.toolCallId
                    if (callId != null && pendingCalls.remove(callId) != null) {
                        repaired += message
                    } else {
                        droppedOrphans += 1
                    }
                }

                else -> {
                    finishPendingRound()
                    repaired += message
                }
            }
        }
        finishPendingRound()
        return MobileAgentProtocolRepairResult(repaired, synthesized, droppedOrphans)
    }
}
