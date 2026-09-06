package com.tuik.rlaude

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

class FileManager(context: Context) {
    val rlaudeRoot = File(context.filesDir, "Rlaude")
    private val projectsRoot = File(rlaudeRoot, "Projects")

    init {
        listOf("Projects", "Runtime", "OpenCode", "Cache", "Downloads", "Backups")
            .map { File(rlaudeRoot, it) }
            .forEach { it.mkdirs() }
    }

    fun projectDir(projectId: String): File {
        val project = safeSegment(projectId)
        val directory = File(projectsRoot, project)
        if (!directory.canonicalPath.startsWith(projectsRoot.canonicalPath + File.separator)) {
            throw SecurityException("Project path is outside Rlaude storage")
        }
        return directory
    }

    fun projects(): JSONArray {
        val result = JSONArray()
        projectsRoot.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name.lowercase() }
            ?.forEach { directory ->
                result.put(
                    JSONObject()
                        .put("id", directory.name)
                        .put("name", directory.name)
                        .put("updatedAt", directory.lastModified())
                        .put("path", directory.absolutePath)
                )
            }
        return result
    }

    fun createProject(name: String, template: String, initializeGit: Boolean): JSONObject {
        val cleanName = name.trim()
        if (cleanName.isEmpty() || cleanName.length > 80 || !cleanName.matches(Regex("[\\w .-]+"))) {
            throw IllegalArgumentException("Use 1–80 letters, numbers, spaces, dots, dashes, or underscores")
        }
        val directory = projectDir(cleanName)
        if (!directory.mkdirs()) throw IOException("A project with that name already exists")
        when (template.lowercase()) {
            "html" -> File(directory, "index.html").writeText(
                "<!doctype html>\n<html lang=\"en\">\n<head><meta charset=\"utf-8\"><title>$cleanName</title></head>\n<body><h1>$cleanName</h1></body>\n</html>\n"
            )
            "python" -> File(directory, "main.py").writeText("def main():\n    print(\"Hello from $cleanName\")\n\nif __name__ == \"__main__\":\n    main()\n")
            "node" -> {
                File(directory, "package.json").writeText(
                    """{"name":"${cleanName.lowercase().replace(" ", "-")}","version":"1.0.0","private":true,"scripts":{"start":"node index.js"}}"""
                )
                File(directory, "index.js").writeText("console.log(\"Hello from $cleanName\");\n")
            }
            "android" -> File(directory, "README.md").writeText("# $cleanName\n\nAndroid project workspace.\n")
            else -> File(directory, "README.md").writeText("# $cleanName\n\nCreated with Rlaude.\n")
        }
        if (initializeGit) GitManager.run(directory, listOf("init"))
        return JSONObject().put("id", cleanName).put("name", cleanName).put("template", template)
    }

    fun renameProject(projectId: String, newName: String): JSONObject {
        val source = projectDir(projectId)
        val cleanName = newName.trim()
        if (cleanName.isEmpty() || !cleanName.matches(Regex("[\\w .-]+"))) {
            throw IllegalArgumentException("Invalid project name")
        }
        val destination = projectDir(cleanName)
        if (destination.exists()) throw IOException("A project with that name already exists")
        if (!source.renameTo(destination)) throw IOException("Could not rename project")
        return JSONObject().put("id", cleanName).put("name", cleanName)
    }

    fun deleteProject(projectId: String) {
        val project = projectDir(projectId)
        if (!project.deleteRecursively()) throw IOException("Could not delete project")
    }

    fun listFiles(projectId: String, relative: String): JSONArray {
        val directory = resolve(projectDir(projectId), relative)
        if (!directory.isDirectory) throw IOException("Folder not found")
        val result = JSONArray()
        directory.listFiles()?.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
            ?.forEach { file ->
                result.put(
                    JSONObject()
                        .put("name", file.name)
                        .put("path", projectDir(projectId).toPath().relativize(file.toPath()).toString())
                        .put("type", if (file.isDirectory) "folder" else "file")
                        .put("size", if (file.isFile) file.length() else 0)
                )
            }
        return result
    }

    fun readFile(projectId: String, relative: String): JSONObject {
        val file = resolve(projectDir(projectId), relative)
        if (!file.isFile) throw IOException("File not found")
        if (file.length() > 2_000_000) throw IOException("File is larger than the 2 MB editor limit")
        return JSONObject().put("path", relative).put("content", file.readText()).put("size", file.length())
    }

    fun writeFile(projectId: String, relative: String, content: String): JSONObject {
        if (content.length > 5_000_000) throw IOException("File is larger than the 5 MB write limit")
        val file = resolve(projectDir(projectId), relative)
        file.parentFile?.mkdirs()
        file.writeText(content)
        return JSONObject().put("path", relative).put("size", file.length())
    }

    fun createEntry(projectId: String, relative: String, folder: Boolean): JSONObject {
        val file = resolve(projectDir(projectId), relative)
        if (file.exists()) throw IOException("An entry with that name already exists")
        if (folder) file.mkdirs() else {
            file.parentFile?.mkdirs()
            file.createNewFile()
        }
        return JSONObject().put("path", relative).put("type", if (folder) "folder" else "file")
    }

    fun deleteEntry(projectId: String, relative: String) {
        val file = resolve(projectDir(projectId), relative)
        if (file == projectDir(projectId)) throw SecurityException("Cannot delete the project root")
        if (!file.deleteRecursively()) throw IOException("Could not delete entry")
    }

    fun renameEntry(projectId: String, from: String, to: String) {
        val source = resolve(projectDir(projectId), from)
        val destination = resolve(projectDir(projectId), to)
        if (!source.exists()) throw IOException("Source not found")
        if (destination.exists()) throw IOException("Destination already exists")
        destination.parentFile?.mkdirs()
        if (!source.renameTo(destination)) throw IOException("Could not rename entry")
    }

    private fun resolve(root: File, relative: String): File {
        if (relative.isBlank() || relative.contains('\u0000')) return root
        val file = File(root, relative).canonicalFile
        if (!file.path.startsWith(root.canonicalPath + File.separator)) {
            throw SecurityException("Path is outside the project")
        }
        return file
    }

    private fun safeSegment(value: String): String {
        if (value.isBlank() || value == "." || value == ".." || value.contains("/") || value.contains("\\")) {
            throw SecurityException("Invalid project name")
        }
        return value
    }
}