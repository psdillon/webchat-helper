# WebChatHelper

An IntelliJ Platform plugin that runs the ChatGPT web UI in a tool window on the right of the IDE and automates attaching project files and diffing ChatGPT's responses. See `SPEC.md` for the requirements and `PLAN.md` for implementation progress.

## Installation

The plugin is not yet published to the JetBrains Marketplace, so build it and install the zip from disk. It needs IntelliJ IDEA 2025.1 or later, and has been run on 2025.1.1.1 and 2026.3.

1. Build the plugin zip from the project folder (see [Prerequisites](#prerequisites) for what the build needs):

   ```bash
   ./gradlew buildPlugin
   ```

   This writes `build/distributions/webchat-helper-<version>.zip`, for example `webchat-helper-0.1.0.zip`.
2. In IntelliJ, open **Settings** (`Ctrl+Alt+S`) and go to **Plugins**.
3. Click the gear icon at the top of the Plugins page and choose **Install Plugin from Disk…**.
4. Select the zip file itself, without unpacking it, and click **OK**.
5. Restart the IDE if it asks you to.
6. Open a project and click **WebChatHelper** on the right-hand tool window bar.

To update, build again and repeat steps 2 to 5 with the new zip. To remove the plugin, go to **Settings → Plugins → Installed**, select **WebChatHelper** and choose **Uninstall** from its gear menu or dropdown.

## ChatGPT login and privacy

The plugin keeps you logged in to ChatGPT the same way the IDE's other embedded browsers keep their state: in the IDE's shared browser storage, the `jcef_cache` folder in the IDE's system folder. On Windows that is `%LOCALAPPDATA%\JetBrains\IntelliJIdea2026.3\jcef_cache` for IntelliJ IDEA 2026.3.

Things to know, because chats can contain sensitive information:

- The folder holds the ChatGPT session cookie, which gives access to your whole ChatGPT account including chat history, and cached page data, which may include fragments of chats.
- It is shared with the IDE's other embedded browsers and readable by anything running as your user, including other IDE plugins. The plugin does not encrypt it.
- Each IDE version has its own system folder, so you log in again after upgrading the IDE, and the old version's `jcef_cache` is left behind with its cookies. Delete old ones you no longer need.
- To log out, use ChatGPT's own log out. To remove everything, close the IDE and delete the `jcef_cache` folder. This also clears the IDE's other embedded browsers, for example:

```bash
rm -rf "$LOCALAPPDATA/JetBrains/IntelliJIdea2026.3/jcef_cache"
```

A separate cookie store at a location you choose is not possible: IntelliJ's JCEF API does not let a plugin give its browser its own storage folder, only move the storage for the whole IDE. A reverse proxy between the browser and ChatGPT could protect the session cookie by holding it itself, so the browser never stores it, but the plugin does not do this.

## Development

### Prerequisites

- JDK 21. `JAVA_HOME` must point to it. It is installed at `C:\Program Files\Java\jdk-21.0.12`.
- The plugin is compiled against IntelliJ IDEA 2025.1.1.1, the oldest supported version, which the build downloads on first use. To use an installed copy instead, set `platformLocalPath` in `gradle.properties`.
- Optional: IntelliJ IDEA 2026.3 installed at `C:\Program Files\JetBrains\IntelliJ IDEA 2026.3` (`latestIdeLocalPath` in `gradle.properties`), for checking the plugin on the newest IDE. Clear that setting if you don't have it.

If a terminal still has an old `JAVA_HOME`, set it for that session first:

```bash
export JAVA_HOME='/c/Program Files/Java/jdk-21.0.12'
```

### Start the sandbox IDE

The sandbox IDE is a separate copy of IntelliJ with the plugin installed, using its own settings and caches under `.intellijPlatform/sandbox/`. Your normal IDE is not affected.

From IntelliJ: open the **Gradle** tool window, then run **Tasks → intellij platform → runIde**.

From a terminal in the project folder:

```bash
./gradlew runIde
```

The command keeps running until you close the sandbox IDE. Once it is up, open a project and click **WebChatHelper** on the right-hand tool window bar.

To try the same build on the newest IDE, run this instead:

```bash
./gradlew runIdeLatest
```

To check the plugin's API use against both IDEs without opening them, run this (the first run downloads over 1 GB of dependencies):

```bash
./gradlew verifyPlugin
```

Notes:

- `runIde` starts IntelliJ IDEA Ultimate 2025.1.1.1, which has no free tier, so the sandbox shows a **Manage Licenses** dialog until you sign in or start a trial. The dialog also blocks the tool window.
- Open a project other than this one in the sandbox. Opening `webchat-helper` itself makes the sandbox index its own cache folder (`.intellijPlatform/`), which logs "file is being used by another process" warnings.
- Restarting from inside the sandbox (for example after changing plugins) does not restart the sandbox: it closes it and opens your normal IntelliJ instead. Run `runIde` again.
- GPU acceleration of the embedded browser is turned off for sandbox runs, because the GPU process crashes on this machine ("Too many restarts of the GPU process").
- The sandbox log is `.intellijPlatform/sandbox/webchat-helper/IU-2025.1.1.1/log_runIde/idea.log`. For `runIdeLatest`, replace `runIde` with `runIdeLatest` in that path.
- The sandbox has its own ChatGPT login, separate from your normal IDE's, in the browser storage under `.intellijPlatform/sandbox/webchat-helper/IU-2025.1.1.1/system_runIde/`.

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
