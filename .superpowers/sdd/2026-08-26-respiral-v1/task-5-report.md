# Task 5 report — welcome ritual and intentional capture

## Delivered

- A skippable `welcome` ritual with the three specified personal starting points and a freeform path.
- `onboardingSeen` persisted in local `VaultSettings` after Skip and after a successful entry save.
- An `editor?id={id}&prompt={prompt}&tags={tags}` route with title/body editing, optional tag chips, saving, and confirmation-only deletion for existing entries.
- `EntryEditorViewModel.save()` returns `false` and retains title, body, tags, and selected media when persistence fails.
- Gallery selection through `PickVisualMedia` and camera capture through `TakePicture` with an app-private cache file exposed only by the configured `FileProvider`. Both become `PendingMedia` for `VaultRepository` copy-in.
- A non-blocking message on cancelled/failed photo selection that explicitly preserves the note draft.
- Arrival navigation after save and a minimal title list backed by the vault timeline so saved entries are visible until the later library work replaces it.

## TDD evidence

### RED

1. Added `EntryEditorScreenTest` with the brief's welcome and freeform-save assertions. The requested connected test initially could not compile because the existing test source imported the removed Compose `assertExists` symbol; after updating that existing assertion API, the test APK compiled but the requested test could not execute because no device is attached.
2. Added `EntryEditorViewModelTest.save_failure_keeps_the_draft_intact` before `EntryEditorViewModel` existed. It failed at `:app:compileDebugUnitTestKotlin` with `Unresolved reference 'EntryEditorViewModel'`.

### GREEN

`JAVA_HOME="$PWD/.tooling/jdk/Contents/Home" ANDROID_HOME="$PWD/.tooling/android-sdk" GRADLE_USER_HOME="$PWD/.tooling/gradle" ./gradlew :app:testDebugUnitTest :app:assembleDebugAndroidTest`

Result: `BUILD SUCCESSFUL`; all JVM unit tests passed and production plus instrumentation sources compiled and packaged.

The full brief command was also run:

`JAVA_HOME="$PWD/.tooling/jdk/Contents/Home" ANDROID_HOME="$PWD/.tooling/android-sdk" GRADLE_USER_HOME="$PWD/.tooling/gradle" ./gradlew :app:testDebugUnitTest :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.respiral.ui.capture.EntryEditorScreenTest`

The unit phase completed, but `:app:connectedDebugAndroidTest` failed with `DeviceException: No connected devices!`.

## Self-review

- Confirmed the exact welcome labels, routes, editor test tags, `Save`, `Skip`, and `Delete` labels.
- Confirmed no voice, network, or account functionality was introduced.
- Confirmed camera files are app-private cache files and are passed through a `FileProvider` URI to `PendingMedia`; the repository remains responsible for copying them into the vault.
- Confirmed draft state is only navigated away after a successful `VaultRepository.save`; failure keeps the editor state in memory and displays an inline message.
- Ran `git diff --check` with no whitespace findings.

## Files changed

- `app/src/main/java/app/respiral/ui/onboarding/WelcomeRitualScreen.kt`
- `app/src/main/java/app/respiral/ui/capture/EntryEditorViewModel.kt`
- `app/src/main/java/app/respiral/ui/capture/EntryEditorScreen.kt`
- `app/src/main/java/app/respiral/ui/capture/PhotoPicker.kt`
- `app/src/main/java/app/respiral/ui/AppNavGraph.kt`
- `app/src/main/res/xml/file_paths.xml`
- `app/src/main/AndroidManifest.xml`
- `app/src/main/java/app/respiral/core/model/VaultSettings.kt`
- `app/src/main/java/app/respiral/data/settings/SettingsRepository.kt`
- `app/src/androidTest/java/app/respiral/ui/capture/EntryEditorScreenTest.kt`
- `app/src/test/java/app/respiral/ui/capture/EntryEditorViewModelTest.kt`
- Existing launch/settings tests adjusted for the onboarding gate and persisted setting.

## Cross-task media regression fix (2026-08-27)

Task 3/Task 5 review found that the editor passed canonical `VaultEntry.media`
separately from newly selected `PendingMedia`, but `VaultFileStore.stage` treated
pending media as the complete set. The repository contract is now explicit:
`VaultEntry.media` is retained on edit and `PendingMedia` contains newly selected
additions. Staging builds the complete desired media directory, copies retained
canonical files, appends new files using collision-safe numeric names, and swaps
the directory atomically so only unreferenced files are removed. No media removal
UI was added.

### Regression TDD evidence

- RED: the new repository tests failed before the production fix:
  `editing_an_entry_without_new_media_preserves_existing_media` and
  `editing_media_appends_new_media_without_orphans` both failed in
  `VaultRepositoryTest`.
- RED: the new `EntryEditorViewModelTest` covers saving an existing photo entry
  with no new selection and asserts canonical media is passed through unchanged.
- GREEN: focused command
  `JAVA_HOME="$PWD/.tooling/jdk/Contents/Home" ANDROID_HOME="$PWD/.tooling/android-sdk" GRADLE_USER_HOME="$PWD/.gradle" ./gradlew :app:testDebugUnitTest --tests app.respiral.data.vault.VaultRepositoryTest --tests app.respiral.ui.capture.EntryEditorViewModelTest`
  completed with `BUILD SUCCESSFUL`.
- GREEN: full command
  `JAVA_HOME="$PWD/.tooling/jdk/Contents/Home" ANDROID_HOME="$PWD/.tooling/android-sdk" GRADLE_USER_HOME="$PWD/.gradle" ./gradlew :app:testDebugUnitTest :app:assembleDebug`
  completed with `BUILD SUCCESSFUL`; 31 JVM tests passed and the debug APK was
  assembled.
- `git diff --check` completed with no whitespace findings.

## Concern

Instrumentation behavior has not been run on an Android device because there are no connected ADB devices and the project-local SDK does not include an emulator executable. The test APK is assembled successfully and is ready to run when a device or emulator is available.
