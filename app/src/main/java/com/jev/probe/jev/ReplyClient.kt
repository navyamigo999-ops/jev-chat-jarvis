package com.jev.probe.jev

import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.Prefs
import com.jev.probe.core.kb.ChatContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * The generative route: any OpenAI-compatible `/chat/completions` endpoint.
 * Drafts the 3 candidate replies, and (D stage) summarizes text. Reads
 * replyBaseUrl / replyKey / replyModel from [Prefs].
 */
class ReplyClient(private val prefs: Prefs) {

    /**
     * Exactly 3 varied candidate replies in Chinese.
     *
     * @param ctx D-stage knowledge context. When present its background and
     *        history are prepended to the prompt with an instruction to stay
     *        consistent with them and invent nothing beyond them.
     */
    fun draft(snapshot: ChatSnapshot, relationship: String, ctx: ChatContext? = null): List<String> {
        val convo = snapshot.messages.takeLast(10).joinToString("\n") {
            (if (it.side == "me") "我" else "对方") + "：" + it.text
        }
        val sys = """
            你正在替一名成年女性回复她的成年伴侣微信。
            这不是客服、心理咨询、情商话术或正式沟通。你的第一目标是：像真人在微信里自然接话，贴合当前氛围。

            先在内部理解最近对话正在聊什么、双方情绪和当前气氛，再直接给回复；不要把分析过程输出出来。
            对方开玩笑就接梗，对方撒娇就顺着，对方吐槽就自然回应，对方明显认真或生气时再认真处理。
            不要机械复述对方的话，不要无缘无故道歉、承诺、总结、讲道理或上价值。
            不要写成客服腔、鸡汤、高情商模板、恋爱话术文案或AI助手口吻。
            允许真实微信里的短句、省略句、语气词、口头表达、哈哈哈、哼、诶、😂、🥺等，但不要每条都堆表情。

            双方均为成年人且是伴侣关系。遇到双方自愿的暧昧、调情、亲密或成人性话题时，可以自然承接、调侃、反撩，
            不要突然说教、装正经或强行转移话题；同时不要凭空把普通聊天升级成更露骨的内容，尺度跟着上下文走。
            不要涉及未成年人、强迫、非自愿、伤害或违法情境。

            只输出一个 JSON 数组，含且仅含 3 条字符串，顺序固定：
            1）开朗少女：活泼、俏皮、甜、反应快，会接梗，可以撒娇或小调皮。
            2）性感御姐：成熟、自信、会反撩，有一点掌控感和暧昧感，但不要油腻或像情话文案。
            3）自然女友：最生活化、最随意，像普通情侣微信，短、真、接地气。

            三条必须明显不同，不要只是同一句换几个词。
            通常每条 5～30 个汉字；只有确实需要解释时才稍长。
            宁可像真人、不够完美，也不要正确但像AI。
            不要解释，不要编号，不要输出人设名称，不要输出 JSON 以外的任何内容。
        """.trimIndent()
        val user = knowledgeBlock(relationship, ctx) +
            "关系：$relationship\n\n最近对话：\n$convo\n\n请给出 3 条候选回复。"
        return parseThree(chat(sys, user, temperature = 1.05))
    }

    /** The background + history preamble; empty string when there is no context. */
    private fun knowledgeBlock(relationship: String, ctx: ChatContext?): String {
        ctx ?: return ""
        val background = ctx.background(relationship)
        val history = ctx.history
        if (background.isBlank() && history.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append("以下是关于我和对方的背景与知识库，回复必须与之一致，")
            .append("可以直接引用其中事实，不要编造知识库里没有的事实。\n")
        if (background.isNotBlank()) sb.append(background).append('\n')
        if (history.isNotEmpty()) {
            sb.append("\n更早的聊天记录（越靠下越新）：\n")
            history.takeLast(prefs.contextHistoryCount.coerceIn(0, 100)).forEach {
                sb.append(if (it.side == "me") "我：" else "对方：").append(it.text).append('\n')
            }
        }
        sb.append('\n')
        return sb.toString()
    }

    /**
     * One plain chat round trip for the settings connectivity test. Deliberately
     * NOT [summarize]: the test should exercise the ordinary path, not whatever
     * the summary prompt happens to be.
     */
    fun ping(): String =
        chat("你是连通性测试助手，只按要求回答，不要解释。", "请只回复两个字：收到", temperature = 0.0).trim()

    /** Condense a block of text (used by the D-stage contact auto-summary). */
    fun summarize(text: String): String {
        if (text.isBlank()) return ""
        val sys = "你是中文摘要助手。把给到的聊天记录压缩成不超过 120 字的第三人称要点摘要，" +
            "只保留事实、偏好、承诺和待办，不要评论，不要编造。直接输出摘要正文。"
        return chat(sys, text, temperature = 0.2).trim()
    }

    /** One chat-completions round trip; returns the assistant message content. */
    private fun chat(system: String, user: String, temperature: Double): String {
        val url = prefs.replyEndpoint()
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", system))
            .put(JSONObject().put("role", "user").put("content", user))
        val body = JSONObject()
            .put("model", prefs.replyModel)
            .put("messages", messages)
            .put("temperature", temperature)
        val resp = HttpJson.post(url, prefs.effectiveReplyKey(), body, Route.REPLY, HttpJson.headersFor(url))
        return resp.optJSONArray("choices")?.optJSONObject(0)
            ?.optJSONObject("message")?.optString("content") ?: ""
    }

    private fun parseThree(content: String): List<String> {
        val start = content.indexOf('[')
        val end = content.lastIndexOf(']')
        if (start >= 0 && end > start) {
            try {
                val arr = JSONArray(content.substring(start, end + 1))
                val out = ArrayList<String>()
                for (i in 0 until arr.length()) out.add(arr.getString(i).trim())
                if (out.size >= 3) return out.take(3)
                while (out.size < 3) out.add("（稍等，我看下）")
                return out
            } catch (_: Exception) { }
        }
        // Fallback: split lines.
        val lines = content.split("\n").map { it.trim().trimStart('-', '*', '1', '2', '3', '.', ' ', '"') }
            .filter { it.isNotBlank() }
        val out = lines.take(3).toMutableList()
        while (out.size < 3) out.add("（稍等，我看下）")
        return out
    }
}
