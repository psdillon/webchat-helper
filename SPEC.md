# WebChatHelper - an IntelliJ plugin that automates tasks when using ChatGPT web within the IntelliJ

## About this Document

In this document:

* Requirement IDs:
  * Consist of 3 or 4 character sequences made up of digits or upper-case letters, that must be unique across the whole document.
  * Start with a hash character.
  * Can be specified anywhere within a bullet point, making the whole bullet point the requirement.
  * AI prompts can reference requirements by ID with or without the hash prefix.
  * Inserting IDs is only allowed when all of the following conditions are met:
    * Should only be inserted where there is a hash character surrounded by whitespace (a linebreak counts as whitespace), that is not at the start of a line.
    * Should only be inserted at the end of leaf bulletpoints after the "About this Document" section.
    * Only IDs that do not already exist in the document can be inserted.
    * IDs must be randomized.
    * Shorter IDs are preferred.
* If "TODO" appears in a requirement, the text after "TODO" should not be implemented.  The text before the "TODO" can be implemented if present, but should be done cautiously, and interactive questioning and suggestions, while of the lowest priority, are welcome in order to:
  * Implement the requirement before the "TODO"
  * Resolve/action the task described after the "TODO", through discussion and suggestions only, not implementation.
* A requirement may end with the following marker text:
  * NEEDS REFINEMENT - the requirement is poorly defined, should not be implemented, but will soon become a requirement, so some consideration should be given to support this in future.  Suggestions for refinement are welcome but of low priority.
  * DETAIL NEEDED - the requirement is defined, but needs more detail to specify fully.  It should not be implemented, but some consideration should be given to support this in future.  Suggestions are welcome.

## Requirements

* The plugin must run chatgpt in JCEF. #R4F
* The plugin will not use any OpenAI APIs. #3Q0
* The plugin's UI will be a tool window anchored on the right side of the IDE, following the convention of other AI tools. #0E6
* A button under the JCEF will reload ChatGPT by recreating the browser and reopening the page it was showing, so the page can be recovered after the IDE restarts its embedded browser (e.g. after GPU process crashes). #K2W
* A button under the JCEF, next to the reload button, will take the browser back to the ChatGPT start page. #W4R
* The ChatGPT login will persist across IDE restarts using the IDE's shared JCEF browser storage (the `jcef_cache` folder in the IDE's system folder), which embedded browsers use by default. #E65
  * Note: this storage holds the ChatGPT session cookie and cached page data, which may include fragments of chats. It is shared with the IDE's other embedded browsers, readable by anything running as the user (including other plugins), and not encrypted by the plugin.
  * Note: the IDE's system folder is per IDE version, so the login is not carried over when the IDE is upgraded, and the old version's `jcef_cache` (with its cookies) is left behind.
  * Note: a separate cookie store at a configurable location was dropped. The JCEF API in IntelliJ 2026.3 does not let a plugin give its browser its own storage location: `CefRequestContext.createContext` takes no settings, `JBCefBrowserBuilder` cannot set a request context, and CEF requires a request context's cache path to be inside the global one. The only alternative is moving the storage for the whole IDE.
  * Note: a reverse proxy between the browser and ChatGPT could protect the session cookie by holding it itself, so it is never stored by the browser.
* All paths shown to ChatGPT or stored in zips or diff files are relative to the project root. #VV0

### File Uploads #7HS
* The plugin will have a configurable list of glob paths for files that it should not send to chatgpt, using the same format as a .gitignore file. #AN7
  * Globs are always relative to the project root. #8BN
  * The default list is: `.git/`, `.idea/`, `.env`, `build/`, `out/`, `target/`. #CI3
  * The project's .gitignore files are not used for exclusions. #2C0
  * A file matching an exclusion glob that is explicitly attached as an individual file will still be attached. #O8Y
  * A file matching an exclusion glob that is within an attached folder will not be attached. #AV2
* Files and folders can be dragged into the tool window to attach them to the prompt. #LI7
  * Dropped files and folders go through the plugin's own attachment handling (exclusions, zipping and prompt text), not ChatGPT's native drop handling. #5PD
  * Drags are accepted from both the IntelliJ Project view and the OS file explorer. #9T1
* When typing into the prompt, when the '@' character is entered at the start of the prompt or after whitespace (including a linebreak), a file/folder selection dialog will be opened using the ChooseByNamePopup + GotoFileModel approach.  If files/folders are selected, they will be attached to the prompt and the '@' will be removed from the prompt. #B11
  * If the dialog is cancelled, nothing is attached and the '@' remains in the prompt. #O2S
  * Whether a single file/folder or multiple files/folders can be selected will be configurable in settings, defaulting to multiple. #0AJ
  * Whether folders are always shown, or only shown when the search text ends with '/' (the default GotoFileModel behaviour), will be configurable in settings. #4QH
* A button under the JCEF will allow selection of multiple files/folders using the standard FileChooser + FileChooserDescriptor approach. #Z7H
* For each file added, the text "<file path> attached<linebreak><linebreak>" is to be injected into the prompt. #C5S
* If the user attempts to attach a folder of more than 3 files, the folder will be attached as a zip and the text "project files under <path> attached<linebreak><linebreak>" will be injected into the prompt, otherwise the files within the folder will be attached as individual files with the appropriate message injected into the prompt. #Q18
  * Files are counted after exclusions have been applied.  Folders are not counted. #XI4
  * Empty folders are included in the zip. #R58
  * The zip is named after the selected folder.  If the selected folder is the project root, the zip is named after the project root folder. #01S
  * Temporary zips are created in the OS's conventional temp location, using the IntelliJ Platform's temp file helpers (e.g. `FileUtil.createTempDirectory`). #MM4
* For each ChatGPT conversation, the plugin keeps a snapshot folder holding a copy of every file sent to that conversation, as it was when last sent. #0RX
  * Files are stored at their path relative to the project root. #H37
  * Snapshot folders are created in the OS's conventional temp location. #5Z5
  * Whenever a file is sent (individually, in a zip or in a diff), its copy in the conversation's snapshot folder is updated. #62W
  * A conversation is identified by the ID in its ChatGPT address. A new chat has no ID until its first message is sent, and has no snapshot folder until then: files sent before the ID appears are not added to any snapshot, so a folder attached in a new chat is always attached as a zip, and a later diff in that conversation includes those files as additions. #W3J
    * Note: this avoids having to tell a new chat being given its ID apart from the user opening another chat, which look the same to the plugin (both are a change from a page with no ID to one with an ID). Getting that wrong would give a conversation a snapshot of files it never received, and so diffs against them.
* If a folder that would be attached as a zip contains any files that are in the conversation's snapshot folder, a diff file will be attached instead of the zip, and the text "changes to <path> since last attachment<linebreak><linebreak>" will be injected into the prompt. #I81
  * The diff file is a unified diff file (`.diff`), not a patch file (e.g. not `git format-patch` output). #0Y9
  * The diff compares the folder's current files (after exclusions) against the snapshot folder's files under the same path. #D4X
  * Files that are not in the snapshot folder are included in the diff as additions. #YN9
  * Files that are in the snapshot folder but no longer exist in the project are included in the diff as deletions.  Files that still exist but are now excluded are not treated as deletions. #7EX
  * The diff file is named after the folder, following the same naming rule as the zip. #94Q
  * After the diff file is attached, the snapshot folder is updated: added and modified files are copied in, and deleted files are removed. #0R9
  * If there are no changes, nothing is attached and the user is informed. #25L
* Temporary file lifetime: #T10
  * Snapshot folders are kept until the IDE exits, then deleted. #BN4
  * Zips and diff files are deleted once uploaded. #H4O
    * Note: ChatGPT's page doesn't show when an upload has finished, and deleting a file before ChatGPT has read it breaks the upload. So a zip or diff file is deleted once it is no longer in the prompt's attachment list: the prompt was sent (which ChatGPT only allows once uploads have finished) or the user removed it. Any still waiting are deleted when the project closes.
* The messages injected into the prompt for file/folder attachments will be ordered to match the order in which the user attached the files/folders. #25C
  * The first message is injected at the start of the prompt. #9N6
  * Each subsequent message is injected directly after the previously injected messages. #R1F
  * If the previously injected messages have been modified, the new message is injected at the start of the prompt. #8K9
  * The trailing blank line of each message separates it from the next message. #4UZ
* If an attachment would exceed ChatGPT's file count or file size limits, the attachment is stopped and the user is informed. #UP4
* ChatGPT does not accept empty files, so an empty file that would be attached as an individual file (whether selected directly or from a folder) is not attached, no "<file path> attached" text is injected for it, and the user is informed.  Other files in the same attachment are still attached.  Empty files in a zip are included in the zip as normal. #E0Z

### Diffs of Files Provided by ChatGPT #GD3
* If a chatgpt response contains a file or file snippet that matches a project file, a button will be added within ChatGPT's page to open a diff window against the matching project file. #AD4
  * The matching project file is the most recently attached file with the same file name. #Z98
  * Files attached as part of a folder (whether attached as individual files or as a zip) count as attached files for matching. #7P2
  * The record of attached files is held in memory only and is not retained across IDE restarts. #3JG
  * If no matching file is found, no button is added and nothing is downloaded. #8N4
  * File snippets are diffed against the whole project file; the user is expected to interpret the resulting diff. #YH8
* If a chatgpt response contains a zip, a button will be added within ChatGPT's page to open a diff window of the zip contents against the current project files. #0RY
  * Paths in the zip are treated as relative to the project root. #15X
  * Files in the zip with no matching project file are not shown in the diff. #F3R
* Changes can be applied to the project files from the diff window. #3PS

### Settings #X4G
* The settings will be an application-level page in the IDE Settings dialog, applying to all projects. #Y0G
* A button under the JCEF will open the plugin's settings page. #7IO
* The settings will include: #CL0
  * The exclusion glob list. #8LI
  * Single or multiple selection in the '@' file/folder selection dialog. #T48
  * Whether folders are always shown in the '@' file/folder selection dialog. #N67
