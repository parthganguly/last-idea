# Last Idea

Last Idea is an open-source, local-first Android idea catcher built around speed:
open, type, leave. Every idea is a plain Markdown file, not an opaque database row.

The design keeps the old APK's black-paper/white-ink icon assets, uses
open-licensed EB Garamond as the default app font, and modernizes the first
screen with a quick typewriter opening and a darker liquid-metal finish. The
ornate borders and skull treatment were removed from the active UI.

## Features

- Fast launch into a fresh capture page
- Typewriter-style opening screen
- First-run choice between local storage and a user-picked Google Drive folder
- APK-derived row icons and bundled fonts
- Markdown pages saved as `page-000001.md`, `page-000002.md`, and so on
- Folder/category index for grouping ideas
- Tap `Last Idea` at the top of a page to open the index
- Long-press an idea in the index to open, move, or delete it
- Long-press a folder heading or shortcut to delete that folder and its Markdown files
- Autosave after 1 second of idle typing
- Immediate save when the app pauses
- Index page with saved idea titles
- Swipe left/right between ideas
- First-run Markdown starter page
- Optional guide item
- Wipe all ideas
- Optional password lock with salted PBKDF2 hashing
- Optional auto-lock after 15 seconds away
- Screenshot blocking while password protection is enabled
- Font selector with EB Garamond as the default app-wide font
- No network permission and no ad SDKs

## Storage

Markdown files can be written locally to the app-specific Documents directory:

```text
Android/data/org.lastidea.app/files/Documents/LastIdea/
```

Or the user can choose a Google Drive folder through Android's system folder
picker. The app uses Android's Storage Access Framework for this, so it does not
need network permission or a bundled Google API client.

Android may hide app-specific external folders from some file managers on newer
releases, but the files are plain UTF-8 Markdown and can be accessed through
Android tooling, backup flows, or future export/share features.

## Build

Requirements:

- JDK 17+
- Android SDK with platform 34 installed
- Gradle 8.x, or the generated wrapper once present

Build from the project root:

```powershell
.\gradlew.bat assembleDebug
```

Run lint:

```powershell
.\gradlew.bat lintDebug
```

## Project Shape

This first implementation intentionally avoids external runtime libraries. The
app is a single native Android Activity plus small helpers:

- `MainActivity`: screens, quick capture flow, and navigation
- `MarkdownStore`: `.md` file persistence
- `PasswordVault`: password hashing and verification
- `AppSettings`: preferences
- `PageFrameLayout`: clean borderless dark surface plus the modern metal overlay

## Open-Source Position

This project now bundles the visual/font assets extracted from the supplied APK
for the app rebuild. It still does not carry over legacy ad SDKs, network
permissions, plaintext password storage, old app code, or proprietary rules
text.

Before publishing broadly as an open-source release, confirm you have the rights
to redistribute those extracted visual/font assets.

License: MIT.
