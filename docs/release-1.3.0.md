# Deal Hunter 1.3.0

The website and Android app now share the current compact/grid interface, claimed savings,
folding summary, countdowns, and title expansion behavior.

## Automatic loading

- Detect native Android using Capacitor's native platform flag, including its default
  `https://localhost` address. Android uses the published feed instead of a nonexistent local API.
- Render the saved snapshot immediately and refresh it in the background.
- Validate incoming snapshots, reject older snapshots, and keep cards visible during refresh.
- Time out stalled requests so later refreshes can proceed.
- If a provider request fails, retain that provider's previously detected offers only
  while their known end dates remain in the future. Successful empty scans still remove offers.
- Bundle the date library locally so network loss cannot prevent startup.

## Widget updates

- A native JobScheduler service reads the same public feed approximately every 30 minutes,
  independently of the app screen. Jobs require network access and persist across reboot.
- The native snapshot is available to the app at startup. Widget artwork is downloaded off
  the UI thread and remains in persistent storage.
- Android can delay background jobs during Doze, battery restrictions, and after a force stop.
  This is not an always-running service or a guarantee of exact update times.
- Existing widget buttons and tap-to-game navigation remain available.

## Additional giveaway discovery

GamerPower's full-game feed supplements the direct Steam, Epic, GOG, and itch.io collectors.
Partner discovery recognizes Ubisoft, EA, Fanatical, Humble, and IndieGala offer links.
Partner offers require a listed paid value, an end date, and a matching store domain.
Trials, free weekends, raffles, subscription offers, and purchase requirements are excluded.
Availability depends on the upstream feed; this does not claim direct or complete coverage
of every publisher catalog. No games are hardcoded into the collector.

## Verification

Run `npm test` for Android-origin startup, cache visibility, invalid/stale feed handling,
network timeouts, mobile classification, and partner filtering regression tests.

The Android build keeps the established package name and yellow lightning launcher icon.
The APK version code is 5 and version name is 1.3.0. This is a debug-signed testing build,
not a Google Play production release.
