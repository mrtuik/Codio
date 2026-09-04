package com.codio

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class BootstrapManager(context: Context) {
    private val files = FileManager(context)
    private val runtime = RuntimeManager(context, files)

    suspend fun initialize(): JSONObject = withContext(Dispatchers.IO) {
        JSONObject()
            .put("directoriesReady", true)
            .put("architecture", runtime.architecture())
            .put("runtime", runtime.status())
    }
}