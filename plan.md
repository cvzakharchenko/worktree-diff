# Worktree Diff Plugin Plan

## Goal

Build a small IntelliJ Platform plugin for IDEA 2026.1+ that lets a user compare the current repository checkout with another local Git worktree of the same repository.

The plugin should stay Git-aware and avoid raw directory comparison, because large repositories often contain many irrelevant or ignored files.

## Product Scope

- Support one Git repository per IDE project.
- Show a side tool window with:
  - a dropdown of other worktrees for the current repository;
  - automatic selection of the first available other worktree;
  - a refresh action;
  - a toggle to include all local changes from either side;
  - a file list of paths included in the current comparison.
- Open a two-sided IDE diff when a file is clicked:
  - current worktree file on the left;
  - selected worktree file on the right;
  - both sides backed by real files on disk, so IntelliJ's normal editable file diff behavior applies.

## Explicit Non-Goals

- No support for multiple repositories in one IDE project.
- No cross-repository comparison.
- No raw recursive folder comparison.
- No special rename handling; renamed files can appear as delete/add.
- No submodule-specific behavior.
- No support for unsaved editor buffer contents during refresh; refresh compares disk state only.
- No custom diff editor unless the built-in IDE diff chain cannot satisfy navigation.
- No support for IDE versions before 2026.1.

## Platform Strategy

- Use a normal IntelliJ tool window for the side panel.
- Prefer existing IntelliJ UI components and actions where they keep the code smaller.
- Reuse the built-in diff viewer through the IntelliJ diff API.
- Use a diff request chain for the current file list so built-in previous/next navigation can move through multiple files if supported.
- Only add custom next/previous actions if a prototype proves the built-in diff chain navigation cannot advance across this plugin's file list.

Relevant references:

- Tool windows: https://plugins.jetbrains.com/docs/intellij/tool-windows.html
- Tool window UX: https://plugins.jetbrains.com/docs/intellij/tool-window.html
- IntelliJ Platform Gradle dependency examples: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html

## Git Strategy

Use Git CLI output for the comparison core. It is stable, easy to test without the IDE, and gives precise Git ignore/status semantics.

Use these commands as the likely baseline:

- `git worktree list --porcelain -z`
- `git diff --name-only -z --no-renames <current-head> <selected-head>`
- `git status --porcelain=v1 -z --untracked-files=all`

The worktree dropdown can later switch to an IDE Git API if that is simpler in 2026.1, but the initial plan should favor CLI because `git worktree list --porcelain -z` is directly machine-readable and does not require depending on internal Git plugin implementation details.

Relevant references:

- `git worktree list --porcelain`: https://git-scm.com/docs/git-worktree.html
- `git status --porcelain`: https://git-scm.com/docs/git-status

## Comparison Semantics

For a selected worktree pair:

1. Start with paths changed between the current worktree `HEAD` and selected worktree `HEAD`.
2. Add local changes from the current worktree.
3. Add local changes from the selected worktree.
4. Deduplicate paths.
5. If the include-local-changes toggle is off, remove paths whose current files on disk are equal on both sides.
6. Display the remaining paths.

Local changes include tracked modifications and untracked, non-ignored files as reported by Git status.

With the toggle off, the list represents files whose current disk contents differ between the two working copies. If there are no current differences, the list is empty.

With the toggle on, the list also includes files that Git reports as locally changed on either side, even if the current disk contents happen to match.

## File Content Rules

- Existing file on both sides: compare file bytes or text content from disk.
- Existing only on current side: show current file against empty/missing content.
- Existing only on selected side: show empty/missing content against selected file.
- Binary files can be listed; opening should rely on IntelliJ's built-in binary/file diff handling.
- Ignored files are excluded by relying on Git status/diff behavior rather than scanning directories directly.

## Main Components

- `WorktreeService`
  - Finds the current repository root.
  - Lists other worktrees.
  - Resolves selected worktree metadata and heads.

- `ComparisonService`
  - Runs Git commands.
  - Computes candidate paths.
  - Filters unchanged files when the toggle is off.
  - Produces file comparison entries for the UI.

- `WorktreeDiffToolWindow`
  - Owns the dropdown, toggle, refresh button, status text, and file list.
  - Auto-selects the first available worktree.
  - Runs refresh work in the background and updates UI on completion.

- `DiffOpener`
  - Converts comparison entries into IntelliJ diff requests.
  - Opens a built-in diff viewer for the selected entry.
  - Attempts to provide the full current comparison list as a request chain.

## Error And Empty States

- No Git repository: hide or show an empty state explaining that the project is not a Git repository.
- No other worktrees: show an empty state and disable refresh/list actions.
- Selected worktree missing or prunable: show an error state and ask the user to refresh after fixing worktrees.
- Git command failure: show a concise error in the tool window and keep the previous successful result if available.
- Empty comparison result: show an empty list with a short "No differences" state.

## Testing Strategy

- Unit-test the comparison core outside the IDE where possible by creating temporary Git repositories and worktrees.
- Cover:
  - equal worktrees;
  - different heads;
  - tracked local modifications on either side;
  - untracked non-ignored files;
  - ignored files excluded;
  - files that appear in head diff but are made equal by local edits;
  - deleted files;
  - toggle on/off behavior.
- Add a small IDE integration test only where IntelliJ APIs are involved, such as tool window registration or diff request creation.

## Implementation Order

1. Update plugin metadata and Gradle target to IDEA 2026.1+.
2. Implement the Git command runner and parser for worktrees/status/head diff.
3. Implement the comparison model and filtering logic with tests.
4. Replace the template tool window with the real side panel.
5. Add diff opening through the built-in IntelliJ diff API.
6. Prototype diff-chain navigation and only add custom actions if needed.
7. Polish empty/error states and run the plugin in a sandbox IDE.
