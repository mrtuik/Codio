# Codio

**Code. Build. Ship.**

Codio is a phone-first native Android shell for a local OpenCode coding runtime. The UI is intentionally kept in one self-contained asset:

```text
app/src/main/assets/codio.html
```

The HTML talks only to validated methods on `window.Codio`. Filesystem, Git, runtime process management, credentials, and the foreground service stay in Kotlin.

## Build

Requirements:

- JDK 17
- Android SDK with platform 35
- Gradle 8.7+

```bash
gradle assembleDebug
```

The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.

The included GitHub Actions workflow builds the debug APK and uploads it as an artifact. It deliberately uses a debug build, so release signing secrets are not required.

## Runtime setup

The app expects the managed OpenCode executable at:

```text
<app internal files>/Codio/Runtime/opencode
```

`RuntimeManager.installFromFile()` verifies a SHA-256 digest before installing and marks the binary executable. A production distribution should provide a versioned, architecture-specific runtime manifest and download the matching package before starting the foreground service. The OpenCode server is started on `127.0.0.1:4096` only.

Until a runtime is installed, Codio stays usable for local projects, file editing, and supported Git operations and reports AI as offline. It does not generate fake AI responses.

## Security boundaries

- The WebView does not receive arbitrary shell execution.
- Terminal commands are allowlisted and validated in native code.
- Project paths are canonicalized and constrained under Codio storage.
- GitHub credentials are designed to live in Android Keystore-backed storage, never in the HTML or WebView storage.
- Runtime diagnostics exclude credentials.

## Included MVP surfaces

Native Android shell, single-file WebView UI, bootstrap directories, runtime health/status, foreground service, AI request transport to local OpenCode, projects, files, editor, restricted terminal, Git status/commit/push/pull, settings, GitHub security boundary, diagnostics, dark/light/system themes, and offline-safe local editing.