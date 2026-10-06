# Home Net Sync

Home Net Sync is a native Android app that copies eligible files from folders on a phone to a shared folder on an SMB network drive.

## Features

- Select one or more phone folders using Android's folder picker.
- Set an SMB server, share, optional credentials, and a destination path relative to the share root.
- Optionally limit files by modified date with an **AFTER** date, a **BEFORE** date, or both. The selected days are inclusive; clear either date to leave that boundary unset.
- Start a manual sync and follow progress in the app or its notification. Cancel a queued or active sync from the app.
- Preserve each selected folder's name and internal directory structure at the destination. Existing remote files are skipped.

Sync work waits for Wi-Fi, including Wi-Fi without internet access, and may be deferred by Android. Large syncs run as a foreground operation with a persistent progress notification. The SMB drive must be reachable from that Wi-Fi network. On Android 13 and newer, allow notifications to see sync progress in the notification shade. Sync itself can run if notifications are denied, but its notification will not be shown.

## Requirements

- Android 8.0 (API 26) or newer
- Android SDK 37 to build the app
- Java 17 for Gradle builds

## Build

Open the project in Android Studio and let Gradle sync, or build a debug APK from the project root:

```powershell
.\gradlew.bat assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Configure and use

1. Add one or more phone folders, such as `DCIM/Camera`.
2. Enter the SMB server address and share name. Add credentials if the share requires them.
3. Enter a destination folder relative to the share root, for example `Archive/Phone`.
4. Optionally set an **AFTER** date and/or a **BEFORE** date. Files modified on either selected boundary date are included. If AFTER is later than BEFORE, the app requires you to correct the range before starting.
5. Tap **Sync now**. Use **Cancel sync** to cancel a queued or running operation.

The app does not discover or configure SMB servers. The SMB implementation uses the jcifs-ng library.
