package com.jev.probe.jev

import com.jev.probe.core.Analysis
import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.Prefs
import com.jev.probe.core.RankedReply
import com.jev.probe.core.kb.ChatContext

/**
 * 砚川 DeepSeek 精简版：
 * 保留原工程对 JevClient 的调用接口，但不再调用 Jev/TypeSafe/OpenRouter。
 *
 * judge() 仅返回本地空结果；
 * draftAndRank() 只调用一次 ReplyClient，生成 3 条候选并保留原顺序。
 */
class JevClient(prefs: Prefs) {
    private val replyClient = ReplyClient(prefs)

    fun judge(
        snapshot: ChatSnapshot,
        relationship: String,
        ctx: ChatContext? = null
    ): Analysis = emptyAnalysis()

    fun draftAndRank(
        snapshot: ChatSnapshot,
        relationship: String,
        ctx: ChatContext? = null
    ): List<RankedReply> = replyClient.draft(snapshot, relationship, ctx)
        .map { RankedReply(it, 0.0) }

    fun analyze(
        snapshot: ChatSnapshot,
        relationship: String,
        ctx: ChatContext? = null
    ): Analysis = emptyAnalysis().copy(
        rankedReplies = draftAndRank(snapshot, relationship, ctx)
    )

    private fun emptyAnalysis() = Analysis(
        trueIntent = null,
        dangerLevel = null,
        sheNeeds = null,
        shouldReplyNow = null,
        bestAction = null,
        tensionResolved = null,
        literalQuestion = null,
        rankedReplies = emptyList(),
        latencyMs = 0L,
        error = null
    )
}
