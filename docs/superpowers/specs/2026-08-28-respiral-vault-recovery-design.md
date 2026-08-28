# Respiral — Markdown Vault Recovery Design

## Status and purpose

This design supersedes the dual-storage read path in the approved Android V1 design. It addresses a real Android 16 failure in which Respiral's Room search index still contains a note but the corresponding canonical Markdown file cannot be opened. Browse can render the indexed title, while Reflection fails when it tries to load the actual note.

Respiral will preserve Markdown as the portable vault format, use the existing Room database once as a local recovery source, and then serve the vault from Markdown plus an in-memory projection. This removes the possibility of Room and Markdown continuing to disagree during normal use.

## Evidence and constraints

The exact public v1.2 APK was exercised on an Android 16 ARM64 emulator. With a valid Markdown file, Save, Browse, and Reflection display a text note correctly. Temporarily making that Markdown file unavailable while leaving the Room row intact reproduces the reported screen exactly: Browse retains the title, but Reflection says the note cannot be opened.

The recovery must obey these constraints:

- Never silently discard or replace personal text.
- Never upload content or add a network permission.
- Keep the owner-readable `.md` format canonical and portable.
- Preserve malformed source files before attempting reconstruction.
- Prefer a slightly slower, simpler file-backed vault over permanent duplicate state. A personal vault is expected to contain hundreds of notes, not millions.
- Keep valid Markdown authoritative when it conflicts with the legacy index.
- Do not expose note text, filenames, paths, or exception messages in diagnostics.

## Considered approaches

### 1. Recommended: one-time recovery followed by a Markdown-only repository

Use the existing Room rows as a one-time recovery ledger for missing or malformed Markdown, then retire Room from the active read/write path. Maintain a decoded in-memory projection for responsive Compose screens and search.

This directly removes the drift class while retaining enough local data to rescue affected text. It is the simplest steady-state architecture and best matches Respiral's low-impact, owner-readable goals.

### 2. Reconcile Room and Markdown permanently

Keep both stores, run reconciliation at startup, and retry reconciliation whenever a read fails. This is a smaller immediate diff, but every save, delete, import, and crash boundary must continue maintaining two representations. It preserves the failure mode and adds increasingly complex repair rules.

### 3. Show diagnostics only

Expose a privacy-safe error code and ask the owner to export or reinstall. This would identify the failing boundary but would not recover the existing note and would leave the underlying architecture unchanged.

Respiral will implement approach 1 and display a safe diagnostic when a note cannot be recovered automatically.

## Target architecture

### Canonical file store

`VaultFileStore` remains responsible for atomic Markdown and private-media operations. Every valid entry lives at `vault/entries/<uuid>.md`; attachments remain beneath `vault/media/<uuid>/` and use relative paths in front matter.

All new saves write the Markdown and media atomically before updating the in-memory projection. Deletes stage the Markdown and media paths, update the projection only after the file operation succeeds, and retain existing rollback behaviour.

### File-backed repository

The active `VaultRepository` no longer queries or writes Room. It owns a `StateFlow` of decoded entries that is populated from the Markdown vault on an IO dispatcher. Browse, search, tag filters, Reflection, notifications, and export derive their results from that flow.

The projection is an optimisation, not a second persistent copy. It is refreshed after save, edit, delete, import, and recovery. Restarting the application always reconstructs it from Markdown.

The repository reports a small vault-health state alongside entries:

- `Healthy`: every discovered Markdown entry decoded successfully.
- `Recovered(count)`: one or more entries were reconstructed during the legacy migration.
- `NeedsAttention(code, count)`: one or more source files remain unreadable and could not be reconstructed.

Vault health contains counts and fixed codes only. It never contains titles, bodies, local paths, or raw exception messages.

### Legacy Room recovery

The first v1.3-or-newer launch checks for the legacy Room database before normal vault loading. Recovery processes each legacy index row independently:

1. If the matching Markdown file exists and decodes, Markdown wins and nothing is written.
2. If the file is missing, reconstruct a `VaultEntry` from the indexed identifier, title, searchable body, created/updated timestamps, and tags.
3. If the file exists but is malformed, first copy its exact bytes into the app-private `vault/recovery/` directory, then reconstruct the entry from the index.
4. Reattach private media by scanning the entry's existing media directory in deterministic filename order and deriving supported MIME types from file extensions. Unknown files remain untouched but are not referenced automatically.
5. Encode the reconstructed entry through `CanonicalMarkdownEntryCodec`, write it atomically, decode it again, and only then count it as recovered.

Recovery is idempotent. A valid canonical file is never overwritten on a later run. If any individual row cannot be reconstructed, other valid and recoverable notes remain available; the failure is recorded as `NeedsAttention` rather than blocking the whole vault.

After every legacy row either has a verified canonical Markdown file or has been reported as unrecoverable, Respiral records the recovery version in DataStore. Room is closed and removed from normal dependency construction. The legacy database is retained on-device for one release as an additional recovery copy and is not modified by new saves. A later maintenance release may remove it after the new architecture has been field-proven.

### Malformed Markdown without a legacy row

A malformed file that has no matching legacy row cannot be reconstructed safely. Respiral leaves the original file exactly where it is, skips it when building the active projection, and reports `RSP-R02` with the number of affected files. Valid notes continue to work.

The Library and Reflection surfaces show gentle recovery copy and the diagnostic code. They do not claim the vault is empty when unreadable files exist. The owner is warned not to reinstall or clear app data and can report the code without exposing note content.

## Navigation correction

The first save currently changes `onboardingSeen`, which can change `NavHost.startDestination` during navigation. The start destination will instead be captured once when the graph is created. Completing onboarding updates settings without rebuilding the graph, then navigation to Library is performed on Android's main dispatcher.

Delete completion and any other navigation callback that follows suspend work will use the same main-dispatcher boundary. Direct button navigation remains synchronous on the UI thread.

## User experience

Automatic recovery should be quiet but transparent:

- Successful recovery: `Respiral gently repaired 1 local note.`
- Partial or failed recovery: `Some notes need attention. Your original files have not been removed. Diagnostic: RSP-R02.`
- No notes and no unreadable files: retain the existing gentle empty-vault copy.

Reflection must never render `Take this gently` without either a note or a meaningful empty/error explanation. If a chosen entry becomes unreadable after the projection was built, the repository refreshes once and Reflection selects again from the refreshed valid entries.

## Data flow

### Startup

1. Load settings and the legacy-recovery version.
2. If recovery has not run and the legacy database exists, reconcile its rows into canonical Markdown.
3. Decode all canonical Markdown on an IO dispatcher, skipping and counting unreadable sources.
4. Publish the entry projection and vault-health state.
5. Render the requested destination.

### Save and edit

1. Validate the draft and stage media plus Markdown.
2. Promote the staged files atomically.
3. Decode the promoted Markdown as a verification step.
4. Update the in-memory projection.
5. Mark onboarding complete when applicable.
6. Navigate to Library on the main dispatcher.

If verification or projection update fails, restore the previous file state and keep the draft visible.

### Browse and Reflection

Browse filters and sorts the in-memory decoded entries. Reflection selects from the same valid entry set and therefore cannot choose an index-only identifier. Opening a note returns the decoded entry associated with that projection.

### Import

Import keeps its existing stage/validate/apply/rollback contract. After a successful apply, the repository rebuilds its in-memory projection from the newly active Markdown root. A failed refresh rolls the import back.

## Privacy and recovery safety

- `vault/recovery/` remains inside Android app-private storage.
- Recovery filenames use the entry UUID and a fixed suffix; user-authored titles never appear in filenames or logs.
- Diagnostics use fixed codes and counts only.
- No recovery content is written to Logcat in release builds.
- No automatic export, cloud backup, or external file write is introduced.
- Reinstalling or clearing app data can erase the vault; recovery copy explicitly warns against either action while unresolved files remain.

## Test strategy

Implementation follows strict red-green TDD.

Unit and integration tests will cover:

- A missing Markdown file reconstructed from a complete legacy row.
- A malformed file copied byte-for-byte to `vault/recovery/` before reconstruction.
- A valid Markdown file never overwritten by conflicting legacy data.
- Recovered timestamps, tags, body text, and deterministic media references.
- One unrecoverable file not blocking healthy entries.
- Idempotent recovery across repeated application starts.
- File-backed search, reverse chronology, tag filters, save, edit, delete, and import refresh.
- Reflection refreshing once rather than selecting an entry that no longer exists.
- Diagnostics containing no title, body, path, or exception message.

Android 16 emulator tests will cover:

- First launch → add text note → Save → Library.
- Library → open the saved note.
- Library and Arrival → Reflection displays title and body.
- A seeded legacy index row with missing Markdown is recovered and displayed.
- A seeded malformed Markdown file is preserved and recovered.
- Browse, Save, Back, Delete, and Reflection navigation do not crash or unexpectedly return to the launcher.

Before release, Respiral must pass unit tests, lint, debug APK assembly, Android-test APK assembly, and the complete connected Android 16 suite. The release APK will then be installed over v1.2 without clearing emulator data and the upgrade path will be exercised once more.

## Release and rollback

The change ships as the next patch release with a new `versionCode` and `versionName`. It installs over v1.2 and preserves app-private data. Users must not uninstall first.

If migration encounters an unexpected failure, it leaves the legacy database and all source files intact, publishes `NeedsAttention`, and allows healthy notes to remain usable. The release must not delete the legacy database, malformed Markdown, or private media.

## Out of scope

- Cloud backup, accounts, telemetry, or remote diagnostics.
- A general-purpose Markdown editor or arbitrary hand-edited front-matter migration.
- Automatic interpretation of unknown media formats.
- Deleting legacy Room data in this release.
- Recovering content after the application has been uninstalled or its app data has been cleared.
