# Respiral — Android V1 Design

## Purpose

Respiral is a private, low-impact Android app for returning to personal evidence of self-worth during periods of self-doubt or negative thoughts. It is not a mood tracker, social product, or productivity tool. It helps a person pause, breathe, and revisit their achievements, affirmations, and reminders of who they are.

V1 is a private installation for one person. It works entirely offline, has no account, no network service, no analytics, and no automatic cloud sync.

## Product principles

- **Personal and private:** The vault belongs only to its owner. Content remains on-device unless the owner exports it.
- **Gentle, never demanding:** No streaks, scores, feeds, badges, or guilt-inducing reminders.
- **Fast at the moment it matters:** Reflection and capture are available in one tap from the app or widget.
- **Intentional capture:** Text is the primary medium. Photos are optional; voice notes are excluded from V1 so entries remain considered rather than frictionless.
- **Portable ownership:** Entries are stored as Markdown with private photos so the owner can export and later migrate their content.

## Target and technical approach

Respiral V1 is a native Android application, written in Kotlin with Jetpack Compose and supporting Android 10 or newer.

Native Android is chosen because it provides the most reliable implementation of home-screen widgets, scheduled notifications, native biometric/passcode authentication, local private files, and camera/gallery access. The canonical Markdown export format deliberately keeps a future iPhone implementation feasible without coupling user content to Android internals.

The app requests no network permission. It uses Android system APIs only for local storage, document export/import, notifications, widgets, camera/gallery, and authentication.

## Visual direction

The design language is a balanced mix of quiet refuge and joyful self-celebration: a sun-faded, tactile editorial look adapted for a calm personal tool.

- **Ground:** warm paper cream `#ead9b8` with subtle paper-fibre/grain texture.
- **Ink:** deep espresso `#2b1d16`; no pure black or white.
- **Settling action:** softened sage `#93a486` for reflective actions and primary “return to yourself” controls.
- **Celebratory spark:** muted terracotta `#bb7357`, used sparingly for warm emphasis, stars, and editorial bands rather than primary choices.
- **Joyful support:** mustard `#d9a92e` for small stamps and details.
- **Type:** Fraunces-style heavy, rounded editorial serif for display; a small neutral mono/sans face for body and labels. Maximum two type families.
- **Motion:** slow, restrained breathing and scene movement; no urgent motion, pulsing alerts, or visual noise.

Large editorial type, textured scene art, marquee strips, and celebratory moments belong primarily in the arrival and reflection experiences. Capture, browsing, and settings stay spacious and simple.

## Primary experience

### Welcome ritual

On first launch, Respiral offers a skippable ritual to seed the vault. It starts with “What would you like to save first?” and offers three equal entry points:

1. Something recently achieved or handled well.
2. Something people who love the user appreciate about them.
3. Something the user wants to remember on a difficult day.

Each opens a tailored prompt and a blank Markdown entry. The ritual gently presents three possible starting notes but has no completion requirement, progress debt, or reward system. The user may stop after one note or skip it entirely.

### Arrival

When opening Respiral, the owner sees a single gentle fork rather than a dashboard:

- **Remind me who I am** — starts the reflection experience, introducing it as “a few words you left for yourself.”
- **Add something good** — starts a new note or optional guided prompt.

This language is intentionally direct and compassionate. It states the purpose of the vault without making the user explain their current state.

### Capture and editing

The default capture surface is a focused text editor. A user can write a Markdown note freely or select a gentle prompt. They may attach one or more photos from the camera or the photo library. Chosen photos are copied into Respiral’s private vault; entries never depend on a gallery link that could later disappear.

Entries may use any combination of these optional tags:

- Achievements
- Affirmations
- Who I Am

Tags are aids to reflection, not mandatory categories. An entry can have no tags, one tag, or multiple tags. Existing notes can be edited and deleted with an explicit confirmation for deletion.

### Reflection

Reflection is a calming scene presenting one saved entry at a time. It pairs a time-sensitive, warm scene with the note’s title, text, and optional image. A small optional 30-second breathing action lets the user pause with that entry.

Entries can be shown at random across the vault or narrowed to one or more tags. Reflection has no score, mood judgement, engagement metric, or content ranking.

### Library

The library is a reverse-chronological timeline with full-text search. It displays the three tags as optional filters. The timeline is the primary view; tags refine it without creating three separate silos.

## Widgets

V1 offers both private home-screen widgets. Neither displays a vault entry by default.

- **Quiet return:** A small square widget with a single action that opens “Remind me who I am.”
- **Two ways in:** A wider widget with direct actions for “Remind me who I am” and “Add something good.”

Both widgets keep the home screen discreet while making the two core app actions immediately available.

## Notifications

Notifications are off by default. After opt-in, the user can independently configure a daily schedule and any combination of these notification types:

- A randomly selected saved entry.
- A generic, private nudge that reveals no vault text.
- A gentle invitation to add a new note.

Lock-screen visibility is separately controlled by the owner. Notification timing is local to the device and reschedules after device restart or time-zone changes. If Android notification permission is denied, Respiral continues working and explains how to enable it from Settings when the user next configures reminders.

## Vault storage and privacy

All vault files live in Android app-private storage. Android’s app sandbox and device encryption protect this storage when the optional Respiral lock is off.

Markdown is the canonical vault format. Each entry is stored as a `.md` file with structured front matter for its identifier, title, timestamps, tags, and ordered media references. Its body contains the user’s Markdown text. Photos are stored in a corresponding app-private media path and referenced by relative path.

A small Room database indexes entry metadata and searchable text for fast browsing. It is a derived cache: if it becomes unavailable or is corrupted, Respiral rebuilds it by reading the Markdown vault.

The application never uploads vault content, reads contacts, accesses location, or requires an account.

## Optional app lock

The app lock is an opt-in toggle. When disabled, Respiral never asks the user for biometrics or device credentials merely to open the app.

When enabled, Respiral uses Android’s native biometric prompt (fingerprint or supported face authentication) and Android device-passcode fallback. It authenticates again after five minutes of inactivity or immediately after the phone locks. The lock gates access to vault content and vault actions, including export and import.

Device-passcode fallback avoids an unrecoverable vault state if biometric enrollment changes or authentication cannot be used. This fallback is part of the enabled lock behaviour; it does not apply while the app lock is off.

## Backup, export, and import

V1 supports owner-initiated export and import only. It does not create automatic backups.

- **Export:** The user selects a destination through Android’s file picker. Respiral creates a ZIP containing all Markdown notes, attached photos, and a manifest describing the export format.
- **Import:** The user chooses a Respiral ZIP through the Android file picker. Respiral validates it in temporary private storage, displays a summary, and requires an explicit choice to merge it with the current vault or replace the current vault. It never silently overwrites entries.

An import failure leaves the existing vault unchanged. A successful merge keeps unique entries based on their stable entry identifier.

## Data flow and failure behaviour

1. **Save:** Respiral writes attached photos into private media storage, writes the Markdown note atomically, then updates the index. An interrupted or failed write preserves the previously valid vault state.
2. **Browse and reflect:** The app queries the local index, reads the selected Markdown entry and local photos, and renders the result without external calls.
3. **Permissions:** A user may decline camera, photo-library, biometric, or notification access. Each related feature becomes unavailable or offers its system-settings route; capture, reflection, and the rest of the vault remain usable.
4. **Device state:** Scheduled reminders are restored after restart and adjust to local time-zone changes. A locked app stops rendering private content until authentication succeeds.
5. **Corrupt index:** Respiral rebuilds the index from valid Markdown files. A malformed imported entry is rejected during validation and reported without changing the current vault.

## Validation strategy

Automated tests cover:

- Markdown/front-matter parsing and serialization.
- Atomic vault writes, media references, and index rebuilding.
- Search, sort order, and optional tag filtering.
- Random reflection selection with and without tag filters.
- ZIP export, import validation, merge, replacement, and failure rollback.
- Notification preference persistence and schedule calculations.

Android device or emulator tests cover:

- The first-run ritual, capture, edit, delete confirmation, and reflection flow.
- Camera/gallery photo attachment and permission denial.
- Widget actions for both supported sizes.
- Notification content and lock-screen privacy settings.
- Biometric lock, passcode fallback, five-minute inactivity expiry, and phone-lock reauthentication.

## Explicit V1 exclusions

- Accounts, login, cloud sync, telemetry, and social features.
- Voice-note capture and transcription.
- Mood tracking, journaling streaks, scores, gamification, or AI-generated advice.
- iPhone implementation, while preserving portable content for a future native iOS version.
- Automatic backup or background upload.
