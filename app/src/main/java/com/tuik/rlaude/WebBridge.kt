package com.tuik.rlaude

import android.webkit.JavascriptInterface
import android.webkit.WebView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class WebBridge(
    private val webView: WebView,
    private val files: FileManager,
    private val runtime: RuntimeManager,
    private val openCode: OpenCodeManager,
    private val bootstrap: BootstrapManager,
    private val scope: CoroutineScope
) {
    private val github = GitHubManager(webView.context)
    private var currentSession: String? = null
    private var currentSessionProjectId: String? = null

    @JavascriptInterface
    fun getDeviceInfo(): String = try {
        JsonResult.ok(
            JSONObject()
                .put("platform", "android")
                .put("app", "Rlaude")
                .put("version", "1.0.0")
                .put("architecture", runtime.architecture())
        )
    } catch (error: Exception) {
        JsonResult.error(error.message ?: "Could not read device info")
    }

    @JavascriptInterface
    fun getRuntimeStatus(): String = try {
        JsonResult.ok(runtime.status())
    } catch (error: Exception) {
        JsonResult.error(error.message ?: "Could not read runtime status")
    }

    @JavascriptInterface
    fun getBootstrapStatus(): String = try {
        JsonResult.ok(bootstrap.status())
    } catch (error: Exception) {
        JsonResult.error(error.message ?: "Could not read bootstrap status")
    }

    @JavascriptInterface
    fun retryBootstrap(): String {
        scope.launch {
            try {
                val result = bootstrap.retry { status -> emit("bootstrap", status) }
                emit("bootstrap", result)
            } catch (error: Exception) {
                emit(
                    "bootstrap",
                    JSONObject()
                        .put("state", "failed")
                        .put("ready", false)
                        .put("error", error.message ?: "Bootstrap failed")
                )
            }
        }
        return JsonResult.ok(JSONObject().put("accepted", true))
    }

    @JavascriptInterface
    fun getProjects(): String = call { files.projects() }

    @JavascriptInterface
    fun ensureDefaultProject(): String = call { files.ensureDefaultProject() }

    @JavascriptInterface
    fun createProject(request: String): String = call {
        val json = JSONObject(request)
        files.createProject(
            json.getString("name"),
            json.optString("template", "blank"),
            json.optBoolean("initializeGit", true)
        )
    }

    @JavascriptInterface
    fun renameProject(request: String): String = call {
        val json = JSONObject(request)
        files.renameProject(json.getString("id"), json.getString("name"))
    }

    @JavascriptInterface
    fun deleteProject(request: String): String = call {
        files.deleteProject(JSONObject(request).getString("id"))
        JSONObject().put("deleted", true)
    }

    @JavascriptInterface
    fun listFiles(request: String): String = call {
        val json = JSONObject(request)
        files.listFiles(json.getString("projectId"), json.optString("path", ""))
    }

    @JavascriptInterface
    fun readFile(request: String): String = call {
        val json = JSONObject(request)
        files.readFile(json.getString("projectId"), json.getString("path"))
    }

    @JavascriptInterface
    fun writeFile(request: String): String = call {
        val json = JSONObject(request)
        files.writeFile(json.getString("projectId"), json.getString("path"), json.getString("content"))
    }

    @JavascriptInterface
    fun createFile(request: String): String = call {
        val json = JSONObject(request)
        files.createEntry(json.getString("projectId"), json.getString("path"), false)
    }

    @JavascriptInterface
    fun createFolder(request: String): String = call {
        val json = JSONObject(request)
        files.createEntry(json.getString("projectId"), json.getString("path"), true)
    }

    @JavascriptInterface
    fun deleteFile(request: String): String = call {
        val json = JSONObject(request)
        files.deleteEntry(json.getString("projectId"), json.getString("path"))
        JSONObject().put("deleted", true)
    }

    @JavascriptInterface
    fun renameFile(request: String): String = call {
        val json = JSONObject(request)
        files.renameEntry(json.getString("projectId"), json.getString("from"), json.getString("to"))
        JSONObject().put("renamed", true)
    }

    @JavascriptInterface
    fun startSession(request: String): String = call {
        val json = JSONObject(request)
        currentSession = json.optString("sessionId").ifBlank { null }
        currentSessionProjectId = json.optString("projectId").ifBlank { null }
        JSONObject().put("sessionId", currentSession ?: "")
    }

    @JavascriptInterface
    fun getSession(): String = JsonResult.ok(JSONObject().put("sessionId", currentSession ?: ""))

    @JavascriptInterface
    fun sendMessage(request: String): String {
        return try {
            val json = JSONObject(request)
            val projectId = json.getString("projectId")
            val text = json.getString("text")
            val requestId = json.optString("requestId", System.currentTimeMillis().toString())
            if (currentSessionProjectId != projectId) {
                currentSession = null
                currentSessionProjectId = projectId
            }
            scope.launch {
                try {
                    val result = openCode.sendMessage(currentSession, projectId, text)
                    currentSession = result.optString("sessionId").ifBlank { currentSession }
                    currentSessionProjectId = projectId
                    emit("message", JSONObject().put("requestId", requestId).put("result", result))
                } catch (error: Exception) {
                    emit("error", JSONObject().put("requestId", requestId).put("message", error.message ?: "AI request failed"))
                }
            }
            JsonResult.ok(JSONObject().put("accepted", true).put("requestId", requestId))
        } catch (error: Exception) {
            JsonResult.error(error.message ?: "Invalid message request", "INVALID_REQUEST")
        }
    }

    @JavascriptInterface
    fun stopGeneration(): String {
        // Cancellation is isolated at the OpenCode transport boundary; this
        // method remains safe even when there is no active request.
        return JsonResult.ok(JSONObject().put("stopped", true))
    }

    @JavascriptInterface
    fun getGitStatus(request: String): String = call {
        val project = files.projectDir(JSONObject(request).getString("projectId"))
        GitManager.status(project)
    }

    @JavascriptInterface
    fun gitCommit(request: String): String = call {
        val json = JSONObject(request)
        val project = files.projectDir(json.getString("projectId"))
        GitManager.run(project, listOf("add", "-A"))
        GitManager.run(project, listOf("commit", "-m", json.getString("message")))
    }

    @JavascriptInterface
    fun gitPush(request: String): String = call {
        GitManager.run(files.projectDir(JSONObject(request).getString("projectId")), listOf("push"))
    }

    @JavascriptInterface
    fun gitPull(request: String): String = call {
        GitManager.run(files.projectDir(JSONObject(request).getString("projectId")), listOf("pull", "--ff-only"))
    }

    @JavascriptInterface
    fun githubLogin(): String = JsonResult.error("GitHub device authentication is not configured in this build", "NOT_CONFIGURED")

    @JavascriptInterface
    fun githubLogout(): String = call { github.disconnect() }

    @JavascriptInterface
    fun githubRepos(): String = call { github.repositories() }

    @JavascriptInterface
    fun getGitHubStatus(): String = call { github.status() }

    @JavascriptInterface
    fun cloneRepo(request: String): String =
        JsonResult.error("Clone requires a configured GitHub account", "NOT_CONFIGURED")

    @JavascriptInterface
    fun startTerminal(request: String): String = call {
        val json = JSONObject(request)
        val project = files.projectDir(json.getString("projectId"))
        val raw = json.getString("command").trim()
        val parts = raw.split(Regex("\\s+")).filter { it.isNotBlank() }
        val allowed = setOf("pwd", "ls", "git", "gradle", "./gradlew", "npm", "python", "python3")
        if (parts.isEmpty() || parts.first() !in allowed) {
            throw SecurityException("Terminal allows approved developer commands only")
        }
        val command = if (parts.first() == "pwd") listOf("pwd")
        else if (parts.first() == "ls") listOf("ls", "-la")
        else {
            val subcommand = parts.getOrNull(1)
            val safeSubcommands = setOf("status", "log", "diff", "branch", "show", "tasks", "test", "build", "run", "start")
            if (parts.first() == "git" && subcommand !in safeSubcommands) {
                throw SecurityException("This terminal only permits read-only Git commands")
            }
            if (parts.first() in setOf("gradle", "./gradlew") && subcommand !in safeSubcommands) {
                throw SecurityException("Only approved Gradle tasks can run here")
            }
            if (parts.first() == "npm" && subcommand !in setOf("test", "run")) {
                throw SecurityException("Only npm test and npm run are allowed here")
            }
            if (parts.first() in setOf("python", "python3")) {
                throw SecurityException("Run scripts from a configured task instead of passing arbitrary Python")
            }
            parts.take(12)
        }
        val process = ProcessBuilder(command).directory(project).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText().take(100_000)
        process.waitFor()
        JSONObject().put("exitCode", process.exitValue()).put("output", output)
    }

    @JavascriptInterface
    fun getModelConfig(): String = call { runtime.modelConfig() }

    @JavascriptInterface
    fun setModelConfig(request: String): String = call {
        val json = JSONObject(request)
        runtime.setModelConfig(
            json.getString("provider"),
            json.optString("model", ""),
            json.optString("apiKey", "").ifBlank { null }
        )
        scope.launch { emit("runtime", openCode.restart()) }
        JSONObject().put("saved", true)
    }

    @JavascriptInterface
    fun restartRuntime(): String {
        scope.launch {
            val result = openCode.restart()
            emit("runtime", result)
        }
        return JsonResult.ok(JSONObject().put("accepted", true))
    }

    @JavascriptInterface
    fun stopRuntime(): String {
        runtime.stop()
        return try {
            webView.context.startService(
                android.content.Intent(webView.context, RlaudeOpenCodeService::class.java)
                    .setAction(RlaudeOpenCodeService.ACTION_STOP)
            )
            JsonResult.ok(JSONObject().put("stopped", true))
        } catch (error: Exception) {
            JsonResult.error(error.message ?: "Could not stop runtime")
        }
    }

    @JavascriptInterface
    fun getDiagnostics(): String = call {
        JSONObject()
            .put("runtime", runtime.status())
            .put("storage", files.rlaudeRoot.absolutePath)
            .put("security", JSONObject()
                .put("localhostOnly", true)
                .put("arbitraryShellFromWebView", false)
                .put("credentialsInWebView", false))
    }

    @JavascriptInterface
    fun getEvents(): String = JsonResult.ok(JSONArray())

    private fun <T> call(operation: () -> T): String = try {
        JsonResult.ok(operation())
    } catch (error: Exception) {
        JsonResult.error(error.message ?: "Operation failed", "OPERATION_FAILED")
    }

    private fun emit(type: String, payload: JSONObject) {
        val event = JSONObject().put("type", type).put("payload", payload)
        webView.post {
            webView.evaluateJavascript(
                "window.RlaudeNative && window.RlaudeNative.onEvent(${JSONObject.quote(event.toString())})",
                null
            )
        }
    }
}