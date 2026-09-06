package com.tuik.rlaude

import android.content.Context
import org.json.JSONObject

/**
 * GitHub authentication is intentionally kept out of the HTML and localStorage.
 * A device-flow implementation can store its short-lived access token through
 * SecureStorage without changing the WebBridge contract.
 */
class GitHubManager(context: Context) {
    private val storage = SecureStorage(context)

    fun status(): JSONObject = JSONObject().put("connected", storage.get("github_token") != null)

    fun disconnect(): JSONObject {
        storage.remove("github_token")
        return status()
    }

    fun repositories(): JSONObject =
        JSONObject().put("ok", false).put("error", "GitHub authentication is not configured yet")
}