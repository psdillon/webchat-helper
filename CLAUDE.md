# CLAUDE.md

## Project

WebChatHelper is an IntelliJ Platform plugin that runs the ChatGPT web UI in JCEF inside a right-hand tool window and automates file attachments and diffs. It does not use any OpenAI APIs.

## Source of truth

- `SPEC.md` is the source of truth for all behaviour. Read it before implementing or changing anything, and before asking clarifying questions: many decisions are already recorded there.
- Follow the conventions in the "About this Document" section of `SPEC.md`: requirement IDs (e.g. `#K7Q`), "TODO", "NEEDS REFINEMENT" and "DETAIL NEEDED" markers.
- Reference requirement IDs in code comments, commit messages and discussion where a change implements a specific requirement.
- If implementation reveals a gap or conflict in the spec, raise it rather than guessing, and update `SPEC.md` once the user decides.
- `PLAN.md` holds the implementation breakdown and order. Keep its task checkboxes up to date as work is completed.

## Implementation notes

- Interaction with ChatGPT (prompt text injection, file attachment, response parsing, injected buttons) depends on ChatGPT's page structure, which changes without notice. Keep page selectors and injected JavaScript in one place so they are easy to update, and fail with a clear message to the user when they stop matching.
- Use IntelliJ Platform helpers where they exist (e.g. `FileUtil` for temp files, `FileChooser`, `ChooseByNamePopup`, the platform diff API) rather than hand-rolled equivalents.
- All paths shown to ChatGPT or stored in zips, diff files and snapshot folders are relative to the project root.
