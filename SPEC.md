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

* The plugin must run chatgpt in JCEF.
* The plugin will not use any OpenAI APIs.
* The plugin's UI will be a tool window anchored on the right side of the IDE, following the convention of other AI tools.
* The ChatGPT login will persist across IDE restarts using a cookie store.
  * The cookie store location will be configurable in settings, defaulting to `~/.webchat-helper-cookiestore`.
* All paths shown to ChatGPT or stored in zips or diff files are relative to the project root.

### File Uploads
* The plugin will have a configurable list of glob paths for files that it should not send to chatgpt, using the same format as a .gitignore file.
  * Globs are always relative to the project root.
  * The default list is: `.git/`, `.idea/`, `.env`, `build/`, `out/`, `target/`.
  * The project's .gitignore files are not used for exclusions.
  * A file matching an exclusion glob that is explicitly attached as an individual file will still be attached.
  * A file matching an exclusion glob that is within an attached folder will not be attached.
* Files and folders can be dragged into the tool window to attach them to the prompt.
  * Dropped files and folders go through the plugin's own attachment handling (exclusions, zipping and prompt text), not ChatGPT's native drop handling.
  * Drags are accepted from both the IntelliJ Project view and the OS file explorer.
* When typing into the prompt, when the '@' character is entered at the start of the prompt or after whitespace (including a linebreak), a file/folder selection dialog will be opened using the ChooseByNamePopup + GotoFileModel approach.  If files/folders are selected, they will be attached to the prompt and the '@' will be removed from the prompt.
  * If the dialog is cancelled, nothing is attached and the '@' remains in the prompt.
  * Whether a single file/folder or multiple files/folders can be selected will be configurable in settings.
  * Whether folders are always shown, or only shown when the search text ends with '/' (the default GotoFileModel behaviour), will be configurable in settings.
* A button under the JCEF will allow selection of multiple files/folders using the standard FileChooser + FileChooserDescriptor approach.
* For each file added, the text "<file path> attached<linebreak><linebreak>" is to be injected into the prompt.
* If the user attempts to attach a folder of more than 3 files, the folder will be attached as a zip and the text "project files under <path> attached<linebreak><linebreak>" will be injected into the prompt, otherwise the files within the folder will be attached as individual files with the appropriate message injected into the prompt.
  * Files are counted after exclusions have been applied.  Folders are not counted.
  * Empty folders are included in the zip.
  * The zip is named after the selected folder.  If the selected folder is the project root, the zip is named after the project root folder.
  * Temporary zips are created in the OS's conventional temp location, using the IntelliJ Platform's temp file helpers (e.g. `FileUtil.createTempDirectory`).
* For each ChatGPT conversation, the plugin keeps a snapshot folder holding a copy of every file sent to that conversation, as it was when last sent.
  * Files are stored at their path relative to the project root.
  * Snapshot folders are created in the OS's conventional temp location.
  * Whenever a file is sent (individually, in a zip or in a diff), its copy in the conversation's snapshot folder is updated.
* If a folder that would be attached as a zip contains any files that are in the conversation's snapshot folder, a diff file will be attached instead of the zip, and the text "changes to <path> since last attachment<linebreak><linebreak>" will be injected into the prompt.
  * The diff file is a unified diff file (`.diff`), not a patch file (e.g. not `git format-patch` output).
  * The diff compares the folder's current files (after exclusions) against the snapshot folder's files under the same path.
  * Files that are not in the snapshot folder are included in the diff as additions.
  * Files that are in the snapshot folder but no longer exist in the project are included in the diff as deletions.  Files that still exist but are now excluded are not treated as deletions.
  * The diff file is named after the folder, following the same naming rule as the zip.
  * After the diff file is attached, the snapshot folder is updated: added and modified files are copied in, and deleted files are removed.
  * If there are no changes, nothing is attached and the user is informed.
* Temporary file lifetime:
  * Snapshot folders are kept until the IDE exits, then deleted.
  * Zips and diff files are deleted once uploaded.
* The messages injected into the prompt for file/folder attachments will be ordered to match the order in which the user attached the files/folders.
  * The first message is injected at the start of the prompt.
  * Each subsequent message is injected directly after the previously injected messages.
  * If the previously injected messages have been modified, the new message is injected at the start of the prompt.
  * The trailing blank line of each message separates it from the next message.
* If an attachment would exceed ChatGPT's file count or file size limits, the attachment is stopped and the user is informed.

### Diffs of Files Provided by ChatGPT
* If a chatgpt response contains a file or file snippet that matches a project file, a button will be added within ChatGPT's page to open a diff window against the matching project file.
  * The matching project file is the most recently attached file with the same file name.
  * Files attached as part of a folder (whether attached as individual files or as a zip) count as attached files for matching.
  * The record of attached files is held in memory only and is not retained across IDE restarts.
  * If no matching file is found, no button is added and nothing is downloaded.
  * File snippets are diffed against the whole project file; the user is expected to interpret the resulting diff.
* If a chatgpt response contains a zip, a button will be added within ChatGPT's page to open a diff window of the zip contents against the current project files.
  * Paths in the zip are treated as relative to the project root.
  * Files in the zip with no matching project file are not shown in the diff.
* Changes can be applied to the project files from the diff window.

### Settings
* The settings will be an application-level page in the IDE Settings dialog, applying to all projects.
* A button under the JCEF will open the plugin's settings page.
* The settings will include:
  * The exclusion glob list.
  * The cookie store location.
  * Single or multiple selection in the '@' file/folder selection dialog.
  * Whether folders are always shown in the '@' file/folder selection dialog.
