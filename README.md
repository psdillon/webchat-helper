# WebChatHelper

An IntelliJ Platform plugin that runs the ChatGPT web UI in a tool window on the right of the IDE and automates attaching project files and diffing ChatGPT's responses. See `SPEC.md` for the requirements and `PLAN.md` for implementation progress.

## Development

### Prerequisites

- JDK 25. `JAVA_HOME` must point to it (Gradle fails on older JDKs). It is installed at `C:\Program Files\Java\jdk-25.0.5.0.3`.
- IntelliJ IDEA 2026.3 installed at `C:\Program Files\JetBrains\IntelliJ IDEA 2026.3`. The build uses it instead of downloading the platform (`platformLocalPath` in `gradle.properties`; clear that setting to download instead).

If a terminal still has an old `JAVA_HOME`, set it for that session first:

```bash
export JAVA_HOME='/c/Program Files/Java/jdk-25.0.5.0.3'
```

### Start the sandbox IDE

The sandbox IDE is a separate copy of IntelliJ with the plugin installed, using its own settings and caches under `.intellijPlatform/sandbox/`. Your normal IDE is not affected.

From IntelliJ: open the **Gradle** tool window, then run **Tasks → intellij platform → runIde**.

From a terminal in the project folder:

```bash
./gradlew runIde
```

The command keeps running until you close the sandbox IDE. Once it is up, open a project and click **WebChatHelper** on the right-hand tool window bar.

Notes:

- Open a project other than this one in the sandbox. Opening `webchat-helper` itself makes the sandbox index its own cache folder (`.intellijPlatform/`), which logs "file is being used by another process" warnings.
- Restarting from inside the sandbox (for example after changing plugins) does not restart the sandbox: it closes it and opens your normal IntelliJ instead. Run `runIde` again.
- GPU acceleration of the embedded browser is turned off for sandbox runs, because the GPU process crashes on this machine ("Too many restarts of the GPU process").
- The sandbox log is `.intellijPlatform/sandbox/webchat-helper/IU-2026.3/log_runIde/idea.log`.

### Inspect the embedded browser

To use Chrome DevTools on the sandbox's browser (for example to look at ChatGPT's page structure), start the sandbox with a remote debugging port:

```bash
./gradlew runIde -PjcefDebugPort=9222
```

Then open `chrome://inspect` in Chrome (add `localhost:9222` under **Configure…**), or list the open pages at `http://localhost:9222/json`. In IntelliJ, add `-PjcefDebugPort=9222` to the arguments of the `runIde` run configuration.

The port is off by default: while it is open, any program on this machine can control the logged-in ChatGPT session.

### Build the plugin

```bash
./gradlew buildPlugin
```

The plugin zip is written to `build/distributions/`.
