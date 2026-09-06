package com.tuik.rlaude

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persists chat sessions to a small JSON file so the sidebar can show a
 * ChatGPT-style history instead of losing every conversation on navigation.
 * Kept intentionally simple (one JSON file, synchronized read-modify-write)
 * since chat volume for a single on-device user is low.
 */
class ChatStore(files: FileManager) {
    private val storeFile = File(files.rlaudeRoot, "chats.json")
    private val lock = Any()

    fun listSessions(): JSONArray = synchronized(lock) {
        val sessions = readAll()
        val result = JSONArray()
        val entries = mutableListOf<JSONObject>()
        for (i in 0 until sessions.length()) entries.add(sessions.getJSONObject(i))
        entries.sortByDescending { it.optLong("updatedAt") }
        entries.forEach { session ->
            result.put(
                JSONObject()
                    .put("id", session.getString("id"))
                    .put("title", session.optString("title", "New chat"))
                    .put("projectId", session.optString("projectId", ""))
                    .put("updatedAt", session.optLong("updatedAt"))
                    .put("messageCount", session.optJSONArray("messages")?.length() ?: 0)
            )
        }
        result
    }

    fun getSession(sessionId: String): JSONObject? = synchronized(lock) {
        findSession(readAll(), sessionId)
    }

    fun appendMessage(sessionId: String, projectId: String, role: String, text: String) {
        if (text.isBlank()) return
        synchronized(lock) {
            val sessions = readAll()
            var session = findSession(sessions, sessionId)
            if (session == null) {
                session = JSONObject()
                    .put("id", sessionId)
                    .put("projectId", projectId)
                    .put("title", makeTitle(text))
                    .put("messages", JSONArray())
                sessions.put(session)
            }
            val messages = session.optJSONArray("messages") ?: JSONArray().also { session.put("messages", it) }
            messages.put(JSONObject().put("role", role).put("text", text).put("ts", System.currentTimeMillis()))
            // Cap history per session so the file can't grow without bound.
            while (messages.length() > MAX_MESSAGES_PER_SESSION) messages.remove(0)
            session.put("updatedAt", System.currentTimeMillis())
            pruneOldSessions(sessions)
            writeAll(sessions)
        }
    }

    fun deleteSession(sessionId: String) {
        synchronized(lock) {
            val sessions = readAll()
            val kept = JSONArray()
            for (i in 0 until sessions.length()) {
                val session = sessions.getJSONObject(i)
                if (session.optString("id") != sessionId) kept.put(session)
            }
            writeAll(kept)
        }
    }

    private fun pruneOldSessions(sessions: JSONArray) {
        if (sessions.length() <= MAX_SESSIONS) return
        val entries = mutableListOf<JSONObject>()
        for (i in 0 until sessions.length()) entries.add(sessions.getJSONObject(i))
        entries.sortByDescending { it.optLong("updatedAt") }
        val kept = entries.take(MAX_SESSIONS)
        val result = JSONArray()
        kept.forEach { result.put(it) }
        // Mutate in place since JSONArray has no direct "replace contents".
        while (sessions.length() > 0) sessions.remove(0)
        for (i in 0 until result.length()) sessions.put(result.get(i))
    }

    private fun findSession(sessions: JSONArray, sessionId: String): JSONObject? {
        for (i in 0 until sessions.length()) {
            val session = sessions.getJSONObject(i)
            if (session.optString("id") == sessionId) return session
        }
        return null
    }

    private fun makeTitle(firstMessage: String): String {
        val clean = firstMessage.trim().replace(Regex("\\s+"), " ")
        return if (clean.length > 48) clean.take(48).trimEnd() + "…" else clean.ifBlank { "New chat" }
    }

    private fun readAll(): JSONArray = runCatching {
        if (!storeFile.exists()) return@runCatching JSONArray()
        JSONArray(storeFile.readText())
    }.getOrElse { JSONArray() }

    private fun writeAll(sessions: JSONArray) {
        storeFile.parentFile?.mkdirs()
        storeFile.writeText(sessions.toString())
    }

    companion object {
        private const val MAX_SESSIONS = 200
        private const val MAX_MESSAGES_PER_SESSION = 400
    }
}
