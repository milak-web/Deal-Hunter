# Deal Hunter 1.4.0 - Rich game notifications

- Original 0.72-second Deal Hunter chime for game alerts and last-day reminders.
- Game title and artwork thumbnail in normal notifications, with a large game image when expanded.
- One final-hour notification with a red system chronometer on the right: 24sp collapsed,
  32sp expanded. Android updates the seconds without repeated alerts or a foreground service.
- Expanding the countdown also shows game artwork and the game title.
- Notification taps focus the selected game in Deal Hunter.
- Artwork loads off the UI thread, is stored on disk, and is bounded in memory and cache size.
  Missing artwork leaves the text alert usable; a loaded image updates the same notification quietly.
- Existing muted notification settings are respected. The final-hour alert chimes once;
  updating its image or ticking its countdown does not repeat the sound.
- Last-day reminders use the native picture renderer and persist their schedule across reboot.

The app keeps the same DealHunter name, lightning launcher icon, and Android package.
Version code 6, version name 1.4.0. This APK is a debug-signed testing build.

## Verification

`npm test` covers website and Android startup, feed handling, provider filtering, and rich notification
payloads. Android compilation verifies the native renderer and notification layouts.
An Android emulator could not start because the computer had approximately 250 MB free disk space;
device rendering and audible playback still need confirmation on a phone.

Android 7 and newer use the system live countdown. Android 6 receives a static remaining-minutes
label. Android and manufacturer skins control notification framing and expansion behavior.
