package com.luckylca.autocrack.agent

import org.junit.Assert.assertTrue
import org.junit.Test

class AgentQuestionPlannerTest {
    @Test
    fun expandTerms_addsLoginCryptoAndNetworkVocabulary() {
        val terms = AgentQuestionPlanner.expandTerms("分析登录请求、Token 保存方式和加密实现")

        assertTrue("login" in terms)
        assertTrue("token" in terms)
        assertTrue("okhttp" in terms)
        assertTrue("cipher" in terms)
    }

    @Test
    fun expandTerms_addsDynamicLoadingAndAntiDebugVocabulary() {
        val terms = AgentQuestionPlanner.expandTerms("查找动态加载、Frida 和反调试检测")

        assertTrue("dexclassloader" in terms)
        assertTrue("loadlibrary" in terms)
        assertTrue("frida" in terms)
        assertTrue("ptrace" in terms)
    }

    @Test
    fun expandTerms_returnsEmptyForBlankQuestion() {
        assertTrue(AgentQuestionPlanner.expandTerms("   ").isEmpty())
    }
}
