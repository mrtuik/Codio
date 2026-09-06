package com.tuik.rlaude

import org.json.JSONObject

/** Consistent result envelope used by every WebView bridge method. */
object JsonResult {
    fun ok(data: Any? = null): String {
        val result = JSONObject().put("ok", true)
        if (data != null) result.put("data", data)
        return result.toString()
    }

    fun error(message: String, code: String = "ERROR"): String =
        JSONObject()
            .put("ok", false)
            .put("error", JSONObject().put("code", code).put("message", message))
            .toString()
}