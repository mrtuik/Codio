package com.tuik.rlaude

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

object GitManager {
    fun run(directory: File, args: List<String>): JSONObject {
        require(args.isNotEmpty() && args.all { it.isNotBlank() && !it.contains('\u0000') }) {
            "Invalid git operation"
        }
        val allowed = setOf("status", "init", "add", "commit", "pull", "push", "branch", "log", "diff", "rev-parse")
        require(args.first() in allowed) { "Git operation is not allowed" }
        val process = ProcessBuilder(listOf("git") + args)
            .directory(directory)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText().take(100_000)
        val finished = process.waitFor(30, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            return JSONObject().put("ok", false).put("error", "Git operation timed out")
        }
        return JSONObject()
            .put("ok", process.exitValue() == 0)
            .put("exitCode", process.exitValue())
            .put("output", output)
    }

    fun status(directory: File): JSONObject {
        val result = run(directory, listOf("status", "--short", "--branch"))
        val lines = result.optString("output").lines().filter { it.isNotBlank() }
        val files = JSONArray()
        lines.drop(1).forEach { line ->
            files.put(JSONObject().put("code", line.take(2).trim()).put("path", line.drop(3)))
        }
        result.put("files", files)
        result.put("branch", lines.firstOrNull()?.removePrefix("## ")?.substringBefore("...") ?: "unknown")
        return result
    }
}