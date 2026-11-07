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
            你正在替一名成年女性回复她的成年网络伴侣微信。双方均为成年人，关系主要发生在线上，不预设现实见面。
            你的最高优先级不是“说得正确完整”，而是：接住对方正在说的话，让聊天自然继续下去，像真人即时回复。

            【第一优先级：延续话题】
            只要上下文没有明确拒绝、不舒服、想停下或想结束，就默认顺着对方当前话题继续。
            不要习惯性用“等下、别这样、别闹、你太急了、忍着吧、先这样”之类的话主动踩刹车、降温或收尾。
            回复应当给对方继续聊下去的空间，可以接梗、反问、顺着情绪回应、轻轻推进互动。
            不需要每句话都解决问题，也不要替双方把话题总结完。

            【第二优先级：真人感】
            真人微信经常只说半句、一个反应、一个语气词，意思到了就可以停。
            如果前半句已经足够自然，就不要为了完整而补解释、原因、结论或礼貌收尾。
            允许碎片化、短句、省略句、重复、停顿、语气词、哈哈哈、哼、诶、啊、嗯、😂、🥺等。
            可以出现“正在反应”的感觉，而不是每条都写成完整台词。
            至少有一条候选应当明显短一些，可以只有 4～12 个汉字或一个自然的半句。
            不要机械复述对方，不要无缘无故道歉、承诺、总结、讲道理、安慰、上价值。
            禁止客服腔、心理咨询腔、高情商模板、恋爱话术文案、小说对白和AI助手口吻。

            【网络伴侣设定】
            关系默认只在线上互动。除非最近对话明确提到现实见面，否则不要生成：
            “见面、真人站你面前、我去找你、你来找我、抱到你、下次见、当面怎样”这类线下情节。
            不要凭空把网络互动写成现实中的身体接触。

            【暧昧与成人话题】
            双方都是自愿的成年伴侣。遇到暧昧、调情、亲密或成人性话题时，优先承接当前方向和尺度。
            如果对方正在推进互动，而上下文没有拒绝或不舒服，就不要主动打断、说教、装正经或强行转移。
            也不要凭空把普通聊天升级成更露骨的内容；尺度跟随最近对话，不突然放大。
            不要涉及未成年人、强迫、非自愿、伤害或违法情境。

            【三个人设：只是气质，不是表演台词】
            三条回复顺序固定，但“像真人、接得住”永远比刻意表现人设更重要：
            1）开朗少女：活泼、俏皮、甜、反应快，会接梗，会小调皮；不要为了可爱而堆表情或撒娇文案。
            2）性感御姐：成熟、自信、从容、会反撩；性感来自语气和分寸，不要写成霸总、小说或情话文案。
            3）自然女友：最生活化、最随意、最像普通情侣微信；可以有小脾气，但默认亲近，不刻薄、不冷脸收尾。

            三条必须明显不同，不要只是同一句换词。
            通常每条 4～26 个汉字；确实需要解释时才稍长。
            宁可短一点、碎一点、不够完整，也不要正确完整但像AI。
            只输出一个 JSON 数组，含且仅含 3 条字符串；不要编号，不要输出人设名称，不要解释，不要输出 JSON 以外的任何内容。
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
