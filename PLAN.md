# WebChatHelper Implementation Plan

Tasks reference requirement IDs in `SPEC.md`. The order puts the riskiest parts (driving ChatGPT's page from JCEF) early, so problems surface before the features that depend on them are built. Each phase should end with something runnable in a sandbox IDE (`runIde`).

## Phase 1: Project scaffold
- [ ] Create a Kotlin IntelliJ Platform plugin project using the IntelliJ Platform Gradle Plugin (2.x), targeting a recent IntelliJ IDEA version.
- [ ] `plugin.xml` with plugin ID, name and description; no OpenAI API dependencies (#3Q0).
- [ ] Confirm `runIde` starts a sandbox IDE with the plugin loaded.
- [ ] Add `.gitignore` for Gradle/IDE output (`.gradle/`, `build/`, `.idea/` as appropriate).

## Phase 2: Tool window and browser
- [ ] Right-anchored tool window (#0E6) hosting a `JBCefBrowser` that loads ChatGPT (#R4F).
- [ ] Toolbar/button row under the browser, ready for the attach and settings buttons (#Z7H, #7IO).
- [ ] Handle JCEF not being supported (show a message instead of failing).

## Phase 3: Settings
- [ ] Application-level `PersistentStateComponent` and `Configurable` page in the IDE Settings dialog (#X4G, #Y0G).
- [ ] Settings fields: exclusion globs with defaults (#8LI, #CI3), cookie store location (#7FX, #9FS), '@' multi-select (#T48, #0AJ), '@' always-show-folders (#N67, #4QH).
- [ ] Settings button under the browser opens the page (#7IO).

## Phase 4: Login persistence (spike first)
- [ ] Spike: confirm how to give the JCEF browser a persistent cookie store at a configurable path (e.g. a `CefRequestContext` with its own cache path vs. app-wide JCEF settings). Report findings before building on it.
- [ ] Persist the ChatGPT login in the cookie store across IDE restarts (#E65, #9FS).

## Phase 5: ChatGPT page bridge (spike first)
- [ ] Spike: prove programmatic file upload into ChatGPT's prompt (e.g. CDP `DOM.setFileInputFiles` vs. a synthetic drop event). Report findings before building on it.
- [ ] Single module holding all page selectors and injected JavaScript, with clear user-facing errors when selectors stop matching.
- [ ] JS-to-Kotlin bridge (`JBCefJSQuery`) for page events.
- [ ] Read and modify prompt text, including inserting linebreaks without submitting.
- [ ] Detect the current conversation identity (from the URL) and conversation changes, including new chats that have no ID yet.

## Phase 6: Attachment core
- [ ] Exclusion matching with .gitignore-format globs relative to the project root (#AN7, #8BN), ignoring project .gitignore files (#2C0).
- [ ] Explicitly attached files bypass exclusions; files inside attached folders do not (#O8Y, #AV2).
- [ ] Attach a single file and inject "<file path> attached" with a project-relative path (#C5S, #VV0).
- [ ] Message ordering: first at start, subsequent after previous injections, fallback to start if previous injections were modified (#25C, #9N6, #R1F, #8K9, #4UZ).
- [ ] Track attached files in memory for diff matching (#3JG, #7P2).
- [ ] Stop and inform the user when ChatGPT's file count or size limits would be exceeded (#UP4).

## Phase 7: Attachment entry points
- [ ] Attach button using `FileChooser` with a multi-select file/folder descriptor (#Z7H).
- [ ] Drag and drop from the Project view and the OS file explorer, routed through the plugin's handling instead of ChatGPT's (#LI7, #5PD, #9T1).
- [ ] '@' trigger at prompt start or after whitespace, opening `ChooseByNamePopup` + `GotoFileModel`; remove '@' on selection, keep it on cancel; honour the two '@' settings (#B11, #O2S, #0AJ, #4QH).

## Phase 8: Folders and zips
- [ ] Folder attachment: count files after exclusions, folders not counted; 3 or fewer as individual files, otherwise a zip (#Q18, #XI4).
- [ ] Zip naming, project-relative entry paths, empty folders included (#01S, #VV0, #R58).
- [ ] Temp file creation and cleanup: zips and diff files deleted after upload (#MM4, #T10, #H4O).

## Phase 9: Snapshots and incremental diffs
- [ ] Per-conversation snapshot folder in the OS temp location, updated whenever a file is sent; deleted on IDE exit (#0RX, #H37, #5Z5, #62W, #BN4).
- [ ] When a folder would be zipped and the snapshot has files under it, attach a unified `.diff` file instead with the "changes to <path> since last attachment" message (#I81, #0Y9, #94Q).
- [ ] Diff contents: compare against the snapshot; additions, modifications and deletions; excluded-but-existing files are not deletions (#D4X, #YN9, #7EX).
- [ ] Update the snapshot after sending; inform the user and attach nothing when there are no changes (#0R9, #25L).

## Phase 10: Diffs of ChatGPT responses
- [ ] Detect code blocks in ChatGPT responses and determine their file names; match to the most recently attached file with that name (#AD4, #Z98, #7P2).
- [ ] Inject a diff button into ChatGPT's page only when a match is found (#8N4); snippets diff against the whole file (#YH8).
- [ ] Open the platform diff window with the response content against the project file, allowing changes to be applied (#3PS).
- [ ] Intercept zip downloads from responses (`CefDownloadHandler`), add a diff button, and diff zip entries against project files by project-relative path, skipping unmatched files (#0RY, #15X, #F3R).

## Open questions to raise when reached
- Phase 4 and 5 spikes may change the approach for cookie storage and file upload.
- Phase 9: how a new chat without a conversation ID maps to a snapshot folder once ChatGPT assigns one.
- Phase 10: how the file name for a response code block is determined (code block header, nearby text, or another heuristic). The spec defines the match rule but not how the name is found.
