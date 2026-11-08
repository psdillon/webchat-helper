# WebChatHelper Implementation Plan

Tasks reference requirement IDs in `SPEC.md`. The order puts the riskiest parts (driving ChatGPT's page from JCEF) early, so problems surface before the features that depend on them are built. Each phase should end with something runnable in a sandbox IDE (`runIde`).

## Phase 1: Project scaffold
- [x] Create a Kotlin IntelliJ Platform plugin project using the IntelliJ Platform Gradle Plugin (2.x), targeting a recent IntelliJ IDEA version.
- [x] `plugin.xml` with plugin ID, name and description; no OpenAI API dependencies (#3Q0).
- [x] Confirm `runIde` starts a sandbox IDE with the plugin loaded.
- [x] Add `.gitignore` for Gradle/IDE output (`.gradle/`, `build/`, `.idea/` as appropriate).

## Phase 2: Tool window and browser
- [x] Right-anchored tool window (#0E6) hosting a `JBCefBrowser` that loads ChatGPT (#R4F).
- [x] Toolbar/button row under the browser, ready for the attach and settings buttons (#Z7H, #7IO).
- [x] Handle JCEF not being supported (show a message instead of failing).
- [x] Reload button that recreates the browser, recovering from JCEF restarts (#K2W).
- [x] Home button that goes back to the ChatGPT start page (#W4R).
- [x] Disable JCEF GPU acceleration for sandbox (`runIde`) runs; the dev machine's GPU process crashes.

## Phase 3: Settings
- [x] Application-level `PersistentStateComponent` and `Configurable` page in the IDE Settings dialog (#X4G, #Y0G).
- [x] Settings fields: exclusion globs with defaults (#8LI, #CI3), '@' multi-select (#T48, #0AJ), '@' always-show-folders (#N67, #4QH).
- [x] Settings button under the browser opens the page (#7IO).

## Phase 4: Login persistence
- [x] The ChatGPT login persists across IDE restarts in the IDE's shared JCEF storage, with no plugin code needed (#E65). A configurable cookie store was dropped: the JCEF API does not allow a per-browser storage location (see the notes under #E65 in `SPEC.md`).

## Phase 5: ChatGPT page bridge (spike first)
- [x] Spike: prove programmatic file upload into ChatGPT's prompt (e.g. CDP `DOM.setFileInputFiles` vs. a synthetic drop event). Report findings before building on it. Findings below.
- [x] Single module holding all page selectors and injected JavaScript, with clear user-facing errors when selectors stop matching (`ChatGptPage`, run by `ChatGptBridge`).
- [x] JS-to-Kotlin bridge (`JBCefJSQuery`) for page events.
- [x] Read and modify prompt text, including inserting linebreaks without submitting.
- [x] Detect the current conversation identity (from the URL) and conversation changes, including new chats that have no ID yet.

### Spike findings (November 2026)
- **File upload: DevTools protocol `DOM.setFileInputFiles`** on ChatGPT's general file input works, from the plugin through the browser's in-process `CefDevToolsClient` (`cefBrowser.devToolsClient`; no debugging port needed). ChatGPT treats it as the user choosing files in its "Add files" dialog and starts uploading at once. It takes file paths, so file contents never pass through JavaScript and large zips are no problem.
- The general file input is the one in `form[data-chatgpt-composer]` with `multiple` and no `accept`; the form's other file inputs only take images or video.
- Fallback, also proven: build `File` objects in the page, assign them to the input through a `DataTransfer` and fire `change`. Not used, because file contents would have to be passed into the page.
- A synthetic drop event was not needed and was not tried.
- ChatGPT renames an attachment whose name it has already seen in the same draft (`SpikeTest.kt` became `SpikeTest(1).kt`).
- The prompt box is a ProseMirror editor, not a textarea. Each line, including Shift+Enter lines, is a top-level `<p>`. Text is inserted with `document.execCommand('insertText')` and lines with `execCommand('insertParagraph')`; neither submits the prompt, and inserted paths are not turned into markdown formatting.
- `JBCefJSQuery` works with ChatGPT's content security policy; the bridge is injected on every page load.
- The DevTools protocol's `Runtime.evaluate` is used for plugin-to-page calls that return results, so `JBCefJSQuery` is only used for page-to-plugin events.

## Phase 6: Attachment core
- [x] Exclusion matching with .gitignore-format globs relative to the project root (#AN7, #8BN), ignoring project .gitignore files (#2C0).
- [x] Explicitly attached files bypass exclusions; files inside attached folders do not (#O8Y, #AV2).
- [x] Attach a single file and inject "<file path> attached" with a project-relative path (#C5S, #VV0).
- [x] Message ordering: first at start, subsequent after previous injections, fallback to start if previous injections were modified (#25C, #9N6, #R1F, #8K9, #4UZ).
- [x] Track attached files in memory for diff matching (#3JG, #7P2).
- [x] Stop and inform the user when ChatGPT's file count or size limits would be exceeded (#UP4).

### Phase 6 notes
- The attach button (#Z7H, from Phase 7) was built here so attachments can be tried in the sandbox. Folders of 3 files or fewer are attached as individual files; larger folders are refused with a "not supported yet" message until Phase 8 adds zips.
- An attachment request (all the files and folders chosen at once) is checked as a whole before anything is sent; if any part can't be attached, nothing is. The exception is empty files attached individually, which ChatGPT refuses: they are left out with a warning and the rest is attached (#E0Z).
- Limits (#UP4): 20 files per message (raised from 10 in February 2026) and 512 MB per file, held in `ChatGptPage`. The files already in the prompt are counted from ChatGPT's attachment list. ChatGPT's rolling upload cap (80 files every 3 hours on paid plans) can't be seen from the page and is left to ChatGPT.
- Exclusion matching (`ExclusionMatcher`) is unit tested (`./gradlew test`), as is message ordering (`InjectedMessages`).

## Phase 7: Attachment entry points
- [x] Attach button using `FileChooser` with a multi-select file/folder descriptor (#Z7H). Done in Phase 6.
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
- The Phase 5 spike may change the approach for file upload.
- Phase 9: how a new chat without a conversation ID maps to a snapshot folder once ChatGPT assigns one. `ConversationTracker` reports a change from `New` to `Existing` both when the first message is sent and when the user opens an existing chat from a new-chat page; the two are not yet told apart.
- Phase 8: zips and diff files are to be deleted once uploaded (#H4O). ChatGPT reads the file while uploading after `DOM.setFileInputFiles`, so deletion has to wait until the upload has finished; how to detect that in ChatGPT's page is not yet known.
- Phase 10: how the file name for a response code block is determined (code block header, nearby text, or another heuristic). The spec defines the match rule but not how the name is found.
