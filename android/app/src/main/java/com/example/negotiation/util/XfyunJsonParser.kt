package com.example.negotiation.util

import org.json.JSONArray
import org.json.JSONObject

/**
 * 解析讯飞语音识别返回的 JSON 结果
 *
 * 讯飞听写返回格式：
 * {
 *   "sn": "1",
 *   "ls": false,
 *   "bg": 0,
 *   "ed": 0,
 *   "ws": [
 *     {
 *       "bg": 0,
 *       "cw": [
 *         {"sc": 0.0, "w": "你好"}
 *       ]
 *     }
 *   ]
 * }
 */
object XfyunJsonParser {

    fun parseResult(jsonString: String): String {
        if (jsonString.isBlank()) return ""
        return try {
            val obj = JSONObject(jsonString)
            val wsArray = obj.optJSONArray("ws") ?: return ""
            val sb = StringBuilder()
            for (i in 0 until wsArray.length()) {
                val wsObj = wsArray.optJSONObject(i) ?: continue
                val cwArray = wsObj.optJSONArray("cw") ?: continue
                for (j in 0 until cwArray.length()) {
                    val cwObj = cwArray.optJSONObject(j) ?: continue
                    val word = cwObj.optString("w", "")
                    if (word.isNotBlank()) {
                        sb.append(word)
                    }
                }
            }
            sb.toString()
        } catch (e: Exception) {
            ""
        }
    }
}
