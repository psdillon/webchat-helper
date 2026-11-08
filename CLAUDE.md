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

- Requires JDK 25 (IntelliJ 2026.3 / build 263). `JAVA_HOME` must point to it; Gradle 9 will not run on older JDKs.
- `./gradlew buildPlugin` builds the plugin zip into `build/distributions/`.
- `./gradlew runIde` starts a sandbox IDE with the plugin. It blocks until the IDE is closed, so run it in the background. Confirm the plugin loaded by checking `.intellijPlatform/sandbox/webchat-helper/IU-2026.3/log_runIde/idea.log` for `Loaded custom plugins: WebChatHelper` and for `ERROR` lines.
- `platformLocalPath` in `gradle.properties` builds against the locally installed IDE instead of downloading `platformVersion` (~1.6 GB plus extraction). It is set because the dev machine's C: drive is short on space; clear it to download instead.

## Implementation notes

- Interaction with ChatGPT (prompt text injection, file attachment, response parsing, injected buttons) depends on ChatGPT's page structure, which changes without notice. Keep page selectors and injected JavaScript in one place so they are easy to update, and fail with a clear message to the user when they stop matching.
- Use IntelliJ Platform helpers where they exist (e.g. `FileUtil` for temp files, `FileChooser`, `ChooseByNamePopup`, the platform diff API) rather than hand-rolled equivalents.
- All paths shown to ChatGPT or stored in zips, diff files and snapshot folders are relative to the project root.
