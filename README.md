# Worktree Diff

Worktree Diff is an IntelliJ Platform plugin for comparing the current checkout with another local Git worktree from the same repository.

The plugin adds a **Worktree Diff** tool window with:

- a dropdown of other worktrees for the current repository;
- automatic selection of the first available worktree;
- a refresh action;
- tool-window title actions for comparison options, grouping, expanding, and collapsing;
- a native IDE file tree of changed, modified, and untracked non-ignored files;
- built-in IntelliJ diff views backed by the files on disk.
- side-panel selection that opens files without taking focus and follows the active file while navigating a Worktree Diff diff chain.

The first implementation targets IntelliJ IDEA 2026.1+ and intentionally supports a single repository per IDE project.

## Development

Run tests:

```powershell
.\gradlew.bat test
```

Build the plugin:

```powershell
.\gradlew.bat buildPlugin
```
