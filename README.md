# Respiral

Respiral is a private, offline Android vault for saving the good things you have
done, the words you want to remember, and reminders of who you are. It has no
account, cloud sync, analytics, crash reporting, or `INTERNET` permission.

## Run it locally

1. Open this directory in Android Studio and allow Gradle to sync.
2. Select an Android 10 (API 29) or newer emulator/phone and press **Run**.
3. For a direct debug build, run:

   ```sh
   ./gradlew :app:assembleDebug
   ```

   The installable APK is `app/build/outputs/apk/debug/app-debug.apk`.

   With USB debugging enabled, it can be installed with:

   ```sh
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

The checked-in Gradle configuration uses Java 17. Android Studio can manage
the JDK and Android SDK, or the project-local toolchain used by the maintainer
can be selected when running Gradle.

## Privacy and offline storage

The vault is kept under the app's private `filesDir`; other apps cannot browse
it through normal storage access. Photos selected from the gallery or camera
are copied into the vault. Storage uses canonical Markdown with an in-memory
projection and one-time legacy recovery; Room is retained only as the legacy
source needed to recover notes created by earlier versions.

When upgrading Respiral, install the new APK over the existing app. Do not
uninstall first, because uninstalling removes the private vault and legacy data
needed for recovery.

Notifications and the home-screen widgets are opt-in. Notification text stays
private on the lock screen by default. The optional vault lock uses the phone's
biometric or device-passcode prompt only when enabled, and does not create a
separate Respiral password.

## Move or back up a vault

Before changing phones, open **Settings → Export vault** and save the ZIP in a
place you control. The archive contains the `.md` entries and copied photos;
Respiral does not upload or sync it automatically.

On the new phone, install Respiral, choose **Settings → Import vault**, select
the ZIP, review the preview, and choose **Merge** or **Replace**. Keep a copy
of the exported ZIP until the import has been checked.

## Signing and repository hygiene

The debug APK is intended for private sideloading and is signed by Android's
local debug key. Release signing is deliberately left to the owner or build
machine; never commit a keystore, passwords, or signing properties.

`local.properties`, keystores, Gradle state, and build outputs are ignored by
Git. `local.properties` should contain only machine-specific SDK paths and is
not required for a clean checkout until Android Studio or Gradle is configured.
