# CLAUDE.md

## Project

WebChatHelper is an IntelliJ Platform plugin that runs the ChatGPT web UI in JCEF inside a right-hand tool window and automates file attachments and diffs. It does not use any OpenAI APIs.

## Source of truth

- `SPEC.md` is the source of truth for all behaviour. Read it before implementing or changing anything, and before asking clarifying questions: many decisions are already recorded there.
- Follow the conventions in the "About this Document" section of `SPEC.md`: requirement IDs (e.g. `#K7Q`), "TODO", "NEEDS REFINEMENT" and "DETAIL NEEDED" markers.
- Reference requirement IDs in code comments, commit messages and discussion where a change implements a specific requirement.
- If implementation reveals a gap or conflict in the spec, raise it rather than guessing, and update `SPEC.md` once the user decides.
- `PLAN.md` holds the implementation breakdown and order. Keep its task checkboxes up to date as work is completed.

## Build and run

- Requires JDK 21. `JAVA_HOME` must point to it. The plugin is compiled against the oldest supported IDE, IntelliJ 2025.1.1.1 (build 251), so the one zip also runs on newer IDEs; don't use APIs that only exist in newer builds without guarding them at runtime.
- `./gradlew buildPlugin` builds the plugin zip into `build/distributions/`.
- `./gradlew runIde` starts a sandbox IDE with the plugin. It blocks until the IDE is closed, so run it in the background. Confirm the plugin loaded by checking `.intellijPlatform/sandbox/webchat-helper/IU-2025.1.1.1/log_runIde/idea.log` for `Loaded custom plugins: WebChatHelper` and for `ERROR` lines. The 2025.1 Ultimate sandbox shows a license dialog that blocks the tool window until someone signs in or starts a trial.
- `./gradlew runIdeLatest` runs the same build in the IDE at `latestIdeLocalPath` (2026.3); its log is under `log_runIdeLatest` in the same sandbox folder.
- `./gradlew verifyPlugin` checks API compatibility with 2025.1.1.1 and the IDE at `latestIdeLocalPath`. Run it after using a platform API you haven't used before.
- `./gradlew runIde -PjcefDebugPort=9222` opens a Chrome remote debugging port on the sandbox's browser (`http://localhost:9222/json` lists pages); useful for inspecting ChatGPT's page. `README.md` has the user-facing version of these instructions; keep it in step.
- `platformLocalPath` in `gradle.properties` builds against a locally installed IDE instead of downloading `platformVersion` (~1.6 GB plus extraction). It is blank because 2025.1.1.1 isn't installed on the dev machine.

## Implementation notes

- Interaction with ChatGPT (prompt text injection, file attachment, response parsing, injected buttons) depends on ChatGPT's page structure, which changes without notice. Keep page selectors and injected JavaScript in one place so they are easy to update, and fail with a clear message to the user when they stop matching.
- Use IntelliJ Platform helpers where they exist (e.g. `FileUtil` for temp files, `FileChooser`, `ChooseByNamePopup`, the platform diff API) rather than hand-rolled equivalents.
- All paths shown to ChatGPT or stored in zips, diff files and snapshot folders are relative to the project root.
