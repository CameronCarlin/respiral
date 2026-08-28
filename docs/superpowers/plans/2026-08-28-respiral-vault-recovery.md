# Respiral Vault Recovery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Recover index-only Respiral notes without losing personal content, then make canonical Markdown plus an in-memory projection the only active vault architecture.

**Architecture:** A one-time `LegacyVaultRecovery` reads the existing Room index and reconstructs missing or malformed Markdown while preserving malformed originals. A process-wide `VaultRuntime` then loads a file-backed `DefaultVaultRepository`; its `StateFlow` projection powers Browse, Reflection, notifications, and search without a second persistent index.

**Tech Stack:** Kotlin, coroutines and `StateFlow`, Jetpack Compose, DataStore Preferences, legacy Room access, Robolectric/JUnit/Truth, AndroidX Compose UI Test, Android 16 ARM64 emulator.

**Spec:** `docs/superpowers/specs/2026-08-28-respiral-vault-recovery-design.md`

## Global Constraints

- Markdown remains the canonical owner-readable format under app-private `filesDir/vault`.
- Never overwrite valid Markdown with legacy index content.
- Copy malformed source bytes into app-private `vault/recovery/` before reconstruction.
- Keep the legacy Room database unchanged and on-device for this release, but remove it from normal reads and writes.
- Diagnostics contain fixed codes and counts only—never titles, bodies, paths, filenames, or exception messages.
- Continue supporting Android 10/API 29 and newer without network permission, telemetry, accounts, or cloud services.
- Run file access and migration on `Dispatchers.IO`; run Compose navigation on `Dispatchers.Main.immediate`.
- Do not uninstall the existing app during upgrade validation because uninstalling erases its private vault.
- Every production change follows a witnessed red-green TDD cycle.

---

## Planned file structure

```text
app/src/main/java/app/respiral/
  RespiralApplication.kt                         # owns the process-wide VaultRuntime
  data/index/EntryIndexDao.kt                   # adds one-shot legacy snapshot query
  data/settings/SettingsRepository.kt           # persists recovery version/code/count
  data/vault/VaultFileStore.kt                  # tolerant scan, quarantine, media discovery
  data/vault/VaultHealth.kt                     # health/report value types and safe codes
  data/vault/LegacyVaultRecovery.kt             # one-time Room-to-Markdown reconciliation
  data/vault/VaultRepository.kt                 # file-backed StateFlow repository
  data/vault/VaultRuntime.kt                    # async bootstrap and readiness boundary
  notifications/RespiralAlarmReceiver.kt        # consumes process-wide ready repository
  ui/AppNavGraph.kt                             # consumes process-wide repository; stable nav
  ui/library/LibraryScreen.kt                   # health/recovery copy
  ui/library/LibraryViewModel.kt                # exposes repository health
  ui/reflection/ReflectionScreen.kt             # no empty “Take this gently” card
  ui/reflection/ReflectionViewModel.kt           # one refresh against file projection
app/src/test/java/app/respiral/
  data/vault/LegacyVaultRecoveryTest.kt
  data/vault/VaultRepositoryTest.kt
  data/vault/VaultRuntimeTest.kt
  ui/library/LibraryViewModelTest.kt
  ui/reflection/ReflectionViewModelTest.kt
app/src/androidTest/java/app/respiral/
  ui/capture/EntryEditorScreenTest.kt
  ui/reflection/ReflectionFlowTest.kt
```

### Task 1: Add tolerant vault scanning and privacy-safe health types

**Files:**
- Create: `app/src/main/java/app/respiral/data/vault/VaultHealth.kt`
- Modify: `app/src/main/java/app/respiral/data/vault/VaultFileStore.kt`
- Test: `app/src/test/java/app/respiral/data/vault/VaultFileStoreRecoveryTest.kt`

**Interfaces:**
- Produces: `sealed interface VaultHealth` with `Loading`, `Healthy`, `Recovered(count: Int)`, and `NeedsAttention(code: VaultDiagnosticCode, count: Int)`.
- Produces: `enum class VaultDiagnosticCode { RSP_R02, RSP_R03 }` whose `displayValue` is `RSP-R02` or `RSP-R03`.
- Produces: `data class VaultScan(val entries: List<VaultEntry>, val unreadableCount: Int)`.
- Produces internal `VaultFileStore.scan()`, `entryFileExists(id)`, `quarantineMalformed(id)`, and `discoverMedia(id)`.

- [ ] **Step 1: Write failing scan and quarantine tests**

```kotlin
@Test fun scan_keeps_valid_entries_when_another_markdown_file_is_malformed() {
    store.write(sampleEntry(title = "Readable"))
    entriesDirectory.resolve("broken.md").writeText("not canonical")

    val result = store.scan()

    assertThat(result.entries.map { it.title }).containsExactly("Readable")
    assertThat(result.unreadableCount).isEqualTo(1)
}

@Test fun quarantine_copies_exact_source_bytes_without_removing_the_original() {
    val source = entriesDirectory.resolve("${sampleEntry().id}.md")
    source.parentFile.mkdirs()
    source.writeBytes(byteArrayOf(0x00, 0x41, 0x7f))

    val preserved = store.quarantineMalformed(sampleEntry().id)

    assertThat(preserved.readBytes()).isEqualTo(byteArrayOf(0x00, 0x41, 0x7f))
    assertThat(source.readBytes()).isEqualTo(byteArrayOf(0x00, 0x41, 0x7f))
}
```

- [ ] **Step 2: Run the focused test and witness RED**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests app.respiral.data.vault.VaultFileStoreRecoveryTest
```

Expected: compilation fails because `scan` and `quarantineMalformed` do not exist.

- [ ] **Step 3: Implement the health types and tolerant file operations**

Add these exact production shapes:

```kotlin
sealed interface VaultHealth {
    data object Loading : VaultHealth
    data object Healthy : VaultHealth
    data class Recovered(val count: Int) : VaultHealth
    data class NeedsAttention(val code: VaultDiagnosticCode, val count: Int) : VaultHealth
}

enum class VaultDiagnosticCode(val displayValue: String) {
    RSP_R02("RSP-R02"),
    RSP_R03("RSP-R03"),
}

data class VaultScan(val entries: List<VaultEntry>, val unreadableCount: Int)
```

Implement `scan()` by decoding each `entries/*.md` independently and incrementing `unreadableCount` on non-cancellation failures. `quarantineMalformed(id)` copies bytes to `vault/recovery/<uuid>.malformed.md` using a temporary sibling and atomic rename, and never deletes the source. `discoverMedia(id)` returns supported files sorted by filename with MIME mappings for jpg/jpeg, png, webp, and gif.

- [ ] **Step 4: Run focused tests and existing codec/file-store tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests app.respiral.data.vault.VaultFileStoreRecoveryTest --tests app.respiral.core.markdown.MarkdownEntryCodecTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/respiral/data/vault/VaultHealth.kt app/src/main/java/app/respiral/data/vault/VaultFileStore.kt app/src/test/java/app/respiral/data/vault/VaultFileStoreRecoveryTest.kt
git commit -m "feat: add tolerant Markdown vault scanning"
```

### Task 2: Recover missing or malformed Markdown from the legacy index

**Files:**
- Modify: `app/src/main/java/app/respiral/data/index/EntryIndexDao.kt`
- Create: `app/src/main/java/app/respiral/data/vault/LegacyVaultRecovery.kt`
- Test: `app/src/test/java/app/respiral/data/vault/LegacyVaultRecoveryTest.kt`

**Interfaces:**
- Consumes: `VaultFileStore` recovery operations from Task 1 and `EntryIndexEntity`.
- Produces: `EntryIndexDao.snapshot(): List<EntryIndexEntity>`.
- Produces: `data class LegacyRecoveryReport(val recoveredCount: Int, val failureCount: Int, val diagnosticCode: VaultDiagnosticCode?)`.
- Produces: `LegacyVaultRecovery.recover(rows: List<EntryIndexEntity>): LegacyRecoveryReport`.

- [ ] **Step 1: Write failing recovery tests**

```kotlin
@Test fun missing_markdown_is_reconstructed_from_the_complete_legacy_row() = runTest {
    val row = legacyRow(title = "Still here", body = "The words survived.")

    val report = recovery.recover(listOf(row))

    assertThat(report).isEqualTo(LegacyRecoveryReport(1, 0, null))
    assertThat(store.read(UUID.fromString(row.id)).body).isEqualTo("The words survived.")
}

@Test fun malformed_markdown_is_preserved_before_reconstruction() = runTest {
    val row = legacyRow(title = "Recovered")
    val source = entryFile(row.id).apply { parentFile.mkdirs(); writeText("broken bytes") }

    recovery.recover(listOf(row))

    assertThat(recoveryFile(row.id).readText()).isEqualTo("broken bytes")
    assertThat(store.read(UUID.fromString(row.id)).title).isEqualTo("Recovered")
}

@Test fun valid_markdown_wins_over_conflicting_legacy_content() = runTest {
    store.write(sampleEntry(title = "Canonical"))

    val report = recovery.recover(listOf(legacyRow(title = "Stale index")))

    assertThat(report.recoveredCount).isEqualTo(0)
    assertThat(store.read(sampleEntry().id).title).isEqualTo("Canonical")
}
```

Also add literal tests for invalid UUID/tag rows, idempotent second recovery, timestamps, and deterministic media discovery. Assert only fixed diagnostic enums/counts—never exception text.

- [ ] **Step 2: Run the focused test and witness RED**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests app.respiral.data.vault.LegacyVaultRecoveryTest
```

Expected: compilation fails because `LegacyVaultRecovery` does not exist.

- [ ] **Step 3: Add the DAO snapshot and minimal recovery implementation**

Add:

```kotlin
@Query("SELECT * FROM entry_index ORDER BY createdAtEpochMs ASC")
suspend fun snapshot(): List<EntryIndexEntity>
```

Implement recovery row-by-row. Parse UUID, timestamps, and pipe-delimited tags defensively. For valid existing Markdown, return without writing. For malformed existing Markdown, quarantine first. Reconstruct media through `discoverMedia`, encode/write through `VaultFileStore`, then immediately read the file to verify it. Convert any row-level failure to one `RSP_R02` count and continue processing other rows; rethrow `CancellationException`.

- [ ] **Step 4: Run focused recovery tests and Room DAO compilation**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests app.respiral.data.vault.LegacyVaultRecoveryTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/respiral/data/index/EntryIndexDao.kt app/src/main/java/app/respiral/data/vault/LegacyVaultRecovery.kt app/src/test/java/app/respiral/data/vault/LegacyVaultRecoveryTest.kt
git commit -m "feat: recover Markdown from the legacy vault index"
```

### Task 3: Replace the active Room repository with a file-backed projection

**Files:**
- Modify: `app/src/main/java/app/respiral/data/vault/VaultRepository.kt`
- Modify: `app/src/main/java/app/respiral/data/vault/VaultFileStore.kt`
- Modify: `app/src/test/java/app/respiral/data/vault/VaultRepositoryTest.kt`

**Interfaces:**
- Consumes: `VaultScan` and `VaultHealth` from Task 1.
- Changes `VaultRepository` to expose `val health: Flow<VaultHealth>` with a default healthy flow for lightweight fakes, while `DefaultVaultRepository` overrides it covariantly as `StateFlow<VaultHealth>`; existing save, timeline, get, delete, rebuild, and import methods remain.
- Changes `DefaultVaultRepository` constructor to `DefaultVaultRepository(fileStore: VaultFileStore)` with no active `RespiralDatabase` dependency.
- Produces: `suspend fun refresh(recoveryReport: LegacyRecoveryReport? = null)` on `DefaultVaultRepository`; `rebuildIndex()` delegates to `refresh()` for compatibility.

- [ ] **Step 1: Rewrite repository setup and add failing file-backed tests**

Remove the in-memory Room database from `VaultRepositoryTest.setUp()` and construct `DefaultVaultRepository(fileStore)`. Add:

```kotlin
@Test fun restart_rebuilds_timeline_only_from_canonical_markdown() = runTest {
    fileStore.write(sampleEntry(title = "From Markdown"))
    val restarted = DefaultVaultRepository(fileStore)

    restarted.refresh()

    assertThat(restarted.observeTimeline("", emptySet()).first().single().title)
        .isEqualTo("From Markdown")
}

@Test fun unreadable_markdown_does_not_block_valid_timeline_entries() = runTest {
    fileStore.write(sampleEntry(title = "Valid"))
    entryDirectory.resolve("broken.md").writeText("broken")

    repository.refresh()

    assertThat(repository.observeTimeline("", emptySet()).first().map { it.title })
        .containsExactly("Valid")
    assertThat(repository.health.value)
        .isEqualTo(VaultHealth.NeedsAttention(VaultDiagnosticCode.RSP_R02, 1))
}
```

Mutation requirement: reintroducing a Room query/write or omitting `refresh()` must fail at least one test.

- [ ] **Step 2: Run repository tests and witness RED**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests app.respiral.data.vault.VaultRepositoryTest
```

Expected: compilation fails because the file-only constructor, health flow, and refresh contract do not exist.

- [ ] **Step 3: Implement the file-backed repository**

Give the interface a default `flowOf(VaultHealth.Healthy)` so existing test fakes do not acquire test-only plumbing. In the concrete repository use `MutableStateFlow<List<VaultEntry>>(emptyList())`, `MutableStateFlow<VaultHealth>(Loading)`, and a `Mutex` around refresh/save/delete/import. `observeTimeline` maps the entry flow, performs case-insensitive literal title/body search, matches any selected tag, and sorts by `createdAt` descending. `get` reads the current decoded projection.

For save: stage/promote, decode the promoted Markdown, update the projection, then complete; restore on failure. For delete: stage deletion, update the projection, then complete; restore on failure. For imports: refresh from the new Markdown root before completing the staged merge/replacement and restore on refresh failure.

Set health by combining scan and recovery results:

```kotlin
health.value = when {
    scan.unreadableCount > 0 -> VaultHealth.NeedsAttention(RSP_R02, scan.unreadableCount)
    report?.failureCount?.let { it > 0 } == true -> VaultHealth.NeedsAttention(report.diagnosticCode!!, report.failureCount)
    report?.recoveredCount?.let { it > 0 } == true -> VaultHealth.Recovered(report.recoveredCount)
    else -> VaultHealth.Healthy
}
```

- [ ] **Step 4: Run all repository and archive tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests app.respiral.data.vault.VaultRepositoryTest --tests app.respiral.data.vault.ZipVaultTransferTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/respiral/data/vault/VaultRepository.kt app/src/main/java/app/respiral/data/vault/VaultFileStore.kt app/src/test/java/app/respiral/data/vault/VaultRepositoryTest.kt
git commit -m "refactor: make Markdown the active vault source"
```

### Task 4: Bootstrap recovery once and share one repository process-wide

**Files:**
- Modify: `app/src/main/java/app/respiral/data/settings/SettingsRepository.kt`
- Create: `app/src/main/java/app/respiral/data/vault/VaultRuntime.kt`
- Modify: `app/src/main/java/app/respiral/RespiralApplication.kt`
- Modify: `app/src/main/java/app/respiral/ui/AppNavGraph.kt`
- Modify: `app/src/main/java/app/respiral/notifications/RespiralAlarmReceiver.kt`
- Test: `app/src/test/java/app/respiral/data/settings/SettingsRepositoryTest.kt`
- Test: `app/src/test/java/app/respiral/data/vault/VaultRuntimeTest.kt`
- Test: `app/src/test/java/app/respiral/RespiralApplicationTest.kt`

**Interfaces:**
- Produces `interface VaultRecoveryStateStore` with `readRecoveryState()` and `writeRecoveryState(state)`.
- Produces `data class PersistedRecoveryState(version: Int, diagnosticCode: VaultDiagnosticCode?, failureCount: Int)`.
- Produces `VaultRuntime(repository, ready)` with `start()` and `awaitReady()`.
- Produces process-wide `RespiralApplication.vaultRepository` and `suspend fun awaitVaultRepository()`.

- [ ] **Step 1: Write failing persistence and runtime tests**

```kotlin
@Test fun recovery_state_persists_version_and_safe_failure_summary() = runTest {
    val expected = PersistedRecoveryState(1, VaultDiagnosticCode.RSP_R02, 2)
    repository.writeRecoveryState(expected)
    assertThat(repository.readRecoveryState()).isEqualTo(expected)
}

@Test fun runtime_recovers_once_then_publishes_the_file_projection() = runTest {
    runtime.start(backgroundScope)
    runtime.awaitReady()

    assertThat(recovery.calls).isEqualTo(1)
    assertThat(repository.observeTimeline("", emptySet()).first().single().title)
        .isEqualTo("Recovered")
}

@Test fun runtime_database_failure_keeps_valid_markdown_available() = runTest {
    val runtime = runtime(legacySnapshot = { error("db broken") })
    runtime.start(backgroundScope)
    runtime.awaitReady()

    assertThat(repository.health.value)
        .isEqualTo(VaultHealth.NeedsAttention(VaultDiagnosticCode.RSP_R03, 1))
    assertThat(repository.observeTimeline("", emptySet()).first()).isNotEmpty()
}
```

- [ ] **Step 2: Run focused tests and witness RED**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests app.respiral.data.settings.SettingsRepositoryTest --tests app.respiral.data.vault.VaultRuntimeTest --tests app.respiral.RespiralApplicationTest
```

Expected: compilation fails because recovery state and `VaultRuntime` do not exist.

- [ ] **Step 3: Implement recovery-state persistence and runtime bootstrap**

Add DataStore integer/string keys `vault_recovery_version`, `vault_recovery_code`, and `vault_recovery_failure_count`. Store only enum name and count. `VaultRuntime.start(scope)` must be idempotent, execute on IO, open the legacy database only when its file exists and recovery version is below `1`, close it in `finally`, persist the report, refresh the repository, and complete readiness even when the legacy database fails.

On a database-open/snapshot failure, do not mark recovery version complete; publish persisted `RSP_R03` so the next launch retries. On completed row processing, persist version `1` plus any safe row-level diagnostic summary.

- [ ] **Step 4: Make `RespiralApplication` the only repository construction root**

Create an application `CoroutineScope(SupervisorJob() + Dispatchers.IO)`, construct one codec, file store, file-backed repository, recovery state store, and `VaultRuntime`, and start it from `onCreate()`.

In `AppNavGraph`, replace `defaultVaultRepository(context)` with `application.vaultRepository` and delete the local factory/Room imports. In `RespiralAlarmReceiver`, call `RespiralApplication.from(context).awaitVaultRepository()` and delete its local repository/database construction.

- [ ] **Step 5: Run focused tests, then compile all app entry points**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests app.respiral.data.settings.SettingsRepositoryTest --tests app.respiral.data.vault.VaultRuntimeTest --tests app.respiral.RespiralApplicationTest :app:assembleDebug
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/app/respiral/RespiralApplication.kt app/src/main/java/app/respiral/data/settings/SettingsRepository.kt app/src/main/java/app/respiral/data/vault/VaultRuntime.kt app/src/main/java/app/respiral/ui/AppNavGraph.kt app/src/main/java/app/respiral/notifications/RespiralAlarmReceiver.kt app/src/test/java/app/respiral/data/settings/SettingsRepositoryTest.kt app/src/test/java/app/respiral/data/vault/VaultRuntimeTest.kt app/src/test/java/app/respiral/RespiralApplicationTest.kt
git commit -m "feat: bootstrap one-time private vault recovery"
```

### Task 5: Make recovery state honest in Library and Reflection

**Files:**
- Modify: `app/src/main/java/app/respiral/ui/library/LibraryViewModel.kt`
- Modify: `app/src/main/java/app/respiral/ui/library/LibraryScreen.kt`
- Modify: `app/src/main/java/app/respiral/ui/reflection/ReflectionViewModel.kt`
- Modify: `app/src/main/java/app/respiral/ui/reflection/ReflectionScreen.kt`
- Test: `app/src/test/java/app/respiral/ui/library/LibraryViewModelTest.kt`
- Test: `app/src/test/java/app/respiral/ui/reflection/ReflectionViewModelTest.kt`
- Test: `app/src/androidTest/java/app/respiral/ui/reflection/ReflectionFlowTest.kt`

**Interfaces:**
- Consumes `VaultRepository.health`.
- Produces fixed UI messages for recovered and attention states.
- Retains `ReflectionViewModel.nextEntry(): Boolean`, but refreshes once through `repository.rebuildIndex()` after a chosen entry cannot be obtained.

- [ ] **Step 1: Write failing health and stale-selection tests**

```kotlin
@Test fun library_exposes_privacy_safe_attention_code_without_exception_text() = runTest {
    repository.health.value = VaultHealth.NeedsAttention(VaultDiagnosticCode.RSP_R02, 1)
    val viewModel = LibraryViewModel(repository, backgroundScope)

    assertThat(viewModel.healthMessage)
        .isEqualTo("Some notes need attention. Your original files have not been removed. Diagnostic: RSP-R02.")
    assertThat(viewModel.healthMessage).doesNotContain("private/path")
}

@Test fun missing_selected_entry_refreshes_once_and_selects_from_remaining_markdown() = runTest {
    val repository = EntryDisappearsRepository(first, remaining)
    val viewModel = ReflectionViewModel(repository, emptySet()) { it.first() }

    assertThat(viewModel.nextEntry()).isTrue()
    assertThat(viewModel.entry).isEqualTo(remaining)
    assertThat(repository.rebuildCalls).isEqualTo(1)
}
```

Add a Compose test asserting `Take this gently` is not displayed when there is no loaded entry and health needs attention.

- [ ] **Step 2: Run focused tests and witness RED**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests app.respiral.ui.library.LibraryViewModelTest --tests app.respiral.ui.reflection.ReflectionViewModelTest
```

Expected: failure because health messages and stale-selection refresh are absent.

- [ ] **Step 3: Implement health mapping and Reflection retry**

Map health to these exact strings:

```kotlin
VaultHealth.Recovered(1) -> "Respiral gently repaired 1 local note."
is VaultHealth.Recovered -> "Respiral gently repaired ${health.count} local notes."
is VaultHealth.NeedsAttention ->
    "Some notes need attention. Your original files have not been removed. Diagnostic: ${health.code.displayValue}."
```

In Reflection, show the mustard `Take this gently` card only when `entry != null`. On `get` failure, refresh once, re-read summaries, and select again; rethrow `CancellationException`. If health needs attention and no valid note remains, show the fixed attention message plus `Do not uninstall Respiral or clear its app data.`

- [ ] **Step 4: Run unit and Compose presentation tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests app.respiral.ui.library.LibraryViewModelTest --tests app.respiral.ui.reflection.ReflectionViewModelTest :app:assembleDebugAndroidTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/respiral/ui/library app/src/main/java/app/respiral/ui/reflection app/src/test/java/app/respiral/ui/library app/src/test/java/app/respiral/ui/reflection app/src/androidTest/java/app/respiral/ui/reflection/ReflectionFlowTest.kt
git commit -m "fix: keep recovery state honest in vault screens"
```

### Task 6: Stabilise first-save and post-suspension navigation

**Files:**
- Modify: `app/src/main/java/app/respiral/ui/AppNavGraph.kt`
- Modify: `app/src/main/java/app/respiral/ui/capture/EntryEditorScreen.kt`
- Modify: `app/src/androidTest/java/app/respiral/ui/capture/EntryEditorScreenTest.kt`

**Interfaces:**
- Captures `startDestination` once for the lifetime of one `NavHost`.
- Guarantees `onSaved` and `onDeleted` navigation executes on `Dispatchers.Main.immediate`.
- Migrates the activity Compose rule to `androidx.compose.ui.test.junit4.v2.createAndroidComposeRule`.

- [ ] **Step 1: Strengthen the failing device flow**

Update the real-activity test to synchronise with asynchronous persistence and assert the destination and persisted body:

```kotlin
@Test fun first_save_opens_library_and_the_note_opens_again() {
    onNodeWithText("Add something good").performClick()
    onNodeWithTag("entry-title").performTextInput("I showed up")
    onNodeWithTag("entry-body").performTextInput("I called a friend when it mattered.")
    onNodeWithText("Save").performClick()

    waitUntilAtLeastOneExists(hasText("Your library"), 5_000)
    onNodeWithText("I showed up").performClick()
    onNodeWithText("I called a friend when it mattered.").assertIsDisplayed()
}
```

- [ ] **Step 2: Run the focused Android 16 test and witness RED**

Run:

```bash
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.respiral.ui.capture.EntryEditorScreenTest#first_save_opens_library_and_the_note_opens_again
```

Expected: FAIL on v1.2 because changing `onboardingSeen` replaces the graph/start destination or navigation resumes off-main under the old test runner.

- [ ] **Step 3: Implement stable navigation**

Capture the destination with `remember { ... }` after initial settings are available. Keep the existing onboarding update, but wrap post-suspension navigation only:

```kotlin
scope.launch {
    markOnboardingSeen(settingsRepository)
    withContext(Dispatchers.Main.immediate) {
        navController.navigate(LIBRARY_ROUTE) {
            popUpTo(navController.graph.startDestinationId) { inclusive = true }
        }
    }
}
```

Apply the same main-dispatcher boundary to delete completion. Do not move repository file work to Main.

- [ ] **Step 4: Run the focused test, then all connected tests**

Run:

```bash
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.respiral.ui.capture.EntryEditorScreenTest#first_save_opens_library_and_the_note_opens_again
./gradlew :app:connectedDebugAndroidTest
```

Expected: PASS with no `AndroidRuntime` crash.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/respiral/ui/AppNavGraph.kt app/src/main/java/app/respiral/ui/capture/EntryEditorScreen.kt app/src/androidTest/java/app/respiral/ui/capture/EntryEditorScreenTest.kt
git commit -m "fix: stabilise vault navigation after persistence"
```

### Task 7: Prove upgrade recovery on Android 16 and prepare v1.3

**Files:**
- Modify: `app/src/androidTest/java/app/respiral/ui/reflection/ReflectionFlowTest.kt`
- Modify: `app/build.gradle.kts`
- Modify: `README.md`

**Interfaces:**
- Adds an Android test that passes a real on-device Room snapshot through `LegacyVaultRecovery` and the file-backed repository while omitting its Markdown file, then asserts Reflection renders the recovered note.
- Sets `versionCode = 4` and `versionName = "1.3"`.

- [ ] **Step 1: Add the failing Android recovery test**

```kotlin
@Test fun legacy_index_only_note_is_recovered_and_reflection_displays_its_body() {
    val database = createOnDeviceLegacyDatabase()
    database.entryIndexDao().upsert(legacyRow(title = "Still me", body = "I stayed kind under pressure."))
    val report = LegacyVaultRecovery(fileStore).recover(database.entryIndexDao().snapshot())
    val repository = DefaultVaultRepository(fileStore).apply { refresh(report) }

    composeTestRule.setContent {
        RespiralTheme { ReflectionScreen(repository, emptySet(), onBack = {}) }
    }

    waitUntilAtLeastOneExists(hasText("I stayed kind under pressure."), 5_000)
    onNodeWithText("I stayed kind under pressure.").assertIsDisplayed()
}
```

Keep seed helpers inside `androidTest`; production code must not expose test-only reset/delete APIs.

- [ ] **Step 2: Run the recovery test and witness RED before its supporting seam is complete**

Run:

```bash
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.respiral.ui.reflection.ReflectionFlowTest#legacy_index_only_note_is_recovered_and_reflection_displays_its_body
```

Expected: FAIL until the real on-device Room snapshot, recovery, file projection, and Reflection screen cooperate end-to-end.

- [ ] **Step 3: Complete the Android-only seed fixture and make the test GREEN**

Use a test-specific database name and a test-specific vault directory beneath the target context's cache directory. Insert a literal row through `RespiralDatabase`, ensure the matching `<vault>/entries/<id>.md` is absent, and exercise the real codec, recovery, file store, repository, theme, and Reflection screen. Close Room and delete the test directory in `finally`. Do not mock the repository or Reflection screen. The preserved-data probe in Step 6 separately proves the real application bootstrap and upgrade boundary.

- [ ] **Step 4: Bump version and document the storage correction**

Set:

```kotlin
versionCode = 4
versionName = "1.3"
```

Update README architecture text from “Room-derived active index” to “canonical Markdown with an in-memory projection and one-time legacy recovery.” Add an upgrade warning: install over the existing app; do not uninstall first.

- [ ] **Step 5: Run fresh release-candidate verification**

Run:

```bash
git diff --check
./gradlew --no-daemon :app:testDebugUnitTest --rerun-tasks :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest :app:connectedDebugAndroidTest
```

Expected: all unit tests, lint, assemblies, and Android 16 connected tests PASS.

- [ ] **Step 6: Perform the preserved-data upgrade probe**

On the Android 16 emulator:

1. Install the exact GitHub v0.1.2 APK.
2. Create a note and confirm Browse contains it.
3. Preserve the app data and install the new v1.3 APK with `adb install -r`.
4. Open Browse and Reflection and confirm the original title/body remain.
5. Seed or preserve an index-only test note, relaunch, and confirm automatic recovery.
6. Confirm `adb logcat` contains no `FATAL EXCEPTION` for `app.respiral`.

- [ ] **Step 7: Commit the release candidate**

```bash
git add app/src/androidTest/java/app/respiral/ui/reflection/ReflectionFlowTest.kt app/build.gradle.kts README.md
git commit -m "chore: prepare Respiral 1.3 recovery release"
```

### Task 8: Review, publish, and verify the public APK

**Files:**
- No production changes expected; only review fixes that first receive a failing regression test.

**Interfaces:**
- Produces GitHub `main`, tag/release `v0.1.3`, and public `app-debug.apk` built from the verified commit.

- [ ] **Step 1: Request code review against the approved spec and this plan**

Review for data-loss paths, cancellation swallowing, IO on Main, duplicate DataStore/Room construction, stale projections, unsafe diagnostics, and upgrade compatibility. Any accepted behaviour fix starts with a failing test.

- [ ] **Step 2: Re-run the complete verification after review changes**

Run:

```bash
git diff --check
./gradlew --no-daemon :app:testDebugUnitTest --rerun-tasks :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest :app:connectedDebugAndroidTest
```

Expected: PASS from fresh outputs.

- [ ] **Step 3: Push the verified commits**

```bash
git push origin tmp/respiral-v1:main
```

- [ ] **Step 4: Publish the APK**

Create GitHub release `v0.1.3` with `app/build/outputs/apk/debug/app-debug.apk`. Release notes must say that v1.3 repairs index-only local notes, makes Markdown the active vault source, fixes first-save navigation, and must be installed over the current app without uninstalling.

- [ ] **Step 5: Verify the public asset**

Download the release asset into a temporary directory, compare its SHA-256 with the locally verified APK, and inspect its package metadata for:

```text
applicationId: app.respiral
versionCode: 4
versionName: 1.3
application label: Respiral
```

- [ ] **Step 6: Report the release**

Provide the public APK URL, SHA-256, verified test counts, Android version/emulator ABI, and the instruction: install over the existing Respiral app; do not uninstall it first.
