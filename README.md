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

The bootstrap flow expects:

1. An architecture-specific, gzip-compressed rootfs URL and SHA-256 in
   `app/src/main/assets/runtime-manifest.json`.
2. A real install-time `libproot.so` for each supported ABI in
   `app/src/main/jniLibs/<abi>/`.
3. The rootfs to contain `/bin/sh`, Node.js, and npm.

The bootstrap manager creates `Codio/Runtime/rootfs`, downloads and verifies the
data tarball, safely extracts it, runs `npm install -g opencode-ai` through the
packaged proot library, verifies `opencode --version`, and persists
`bootstrap_complete`. The OpenCode server is then launched headlessly on
`127.0.0.1:4096` by the foreground service.

The source bundle contains the manifest and ABI placeholders, but not a
third-party proot binary or a hosted rootfs release asset. Those distribution
inputs must be supplied before a fresh install can pass the full bootstrap
acceptance flow; the app surfaces a retryable error instead of silently
pretending the runtime is installed.

## Security boundaries

- The WebView does not receive arbitrary shell execution.
- Terminal commands are allowlisted and validated in native code.
- Project paths are canonicalized and constrained under Codio storage.
- GitHub credentials are designed to live in Android Keystore-backed storage, never in the HTML or WebView storage.
- Runtime diagnostics exclude credentials.

## Included MVP surfaces

Native Android shell, single-file WebView UI, bootstrap directories, runtime health/status, foreground service, AI request transport to local OpenCode, projects, files, editor, restricted terminal, Git status/commit/push/pull, settings, GitHub security boundary, diagnostics, dark/light/system themes, and offline-safe local editing.