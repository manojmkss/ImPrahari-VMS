# LightNVR for Android

A companion app for a LightNVR server, for the things you do on a phone:
- watch every camera live, with sound;
- play back recordings;
- go through visits;
- arm and disarm;
- get alerts with the picture.

Everything else (cameras, recording, AI, alert rules, users) is the NVR's own
web settings, built into the app.

**Status: 0.2.0** — all of the above is built. Unit tests and a replay of a
real NVR playback session pass. It still needs testing on real phones before
a release.

## What's in it

| Tab | What it does |
|---|---|
| **Live** | Every camera live at once (sub streams), with group chips (Settings → Camera groups on the NVR). Tap a camera for full screen: SD/HD, sound (the camera's main stream carries it), and **Playback**. |
| **Visits** | Like the web Visits page, with the same filters: **what** was seen (people, vehicles, animals, bags, other, motion only such as lights and shadows), **where** (a camera or a camera group), **who** (a person or strangers), over today, yesterday or 7 days. Tap a visit to play it from its start. |
| **Security** | The mode now (Home / Away / Night / Off), who set it, and when the schedule changes it next. Admin accounts can switch it, optionally "keep until I change it". |
| **Settings** | Turn on alerts, open the web settings (already signed in), sign out. |

**Playback.**
- The scrub bar shows the day's recordings: drag it or tap a moment. You can pause, jump ±30 s, play at ½× to 8×, zoom (15 min to 24 h), or pick a date and time.
- Small gaps between recording files play straight through, and longer gaps are skipped.
- Picking a moment with no recording jumps to the next footage.

**Quick Settings tile.**
- Edit the Quick Settings panel and add **NVR mode**.
- A tap arms the system (Away) or, when it's armed, returns it to Home. Long-press opens the app.

**Home-screen widget.**
- Shows the mode, with a button for each mode.

The tile and widget follow mode changes made in the app at once. Otherwise
they refresh when the panel opens, or every 30 minutes. Changing the mode
needs an **admin** account, the same as in the web app.

## Alerts (no Google account)

Alerts come through [UnifiedPush](https://unifiedpush.org). A *distributor* app
on the phone holds one connection to a push server, for all the apps that use
it. **ntfy** (F-Droid or Play Store) is the usual choice.

1. Install ntfy. Its defaults are fine: it uses the public `ntfy.sh` server,
   or your own ntfy server if you set one.
2. In LightNVR: **Settings → Turn on alerts**, then allow notifications.
3. Use **Send a test** to check it works.

The NVR sends exactly what it sends to browsers (Web Push), **encrypted for
this phone**. The push server in between only sees ciphertext. Which events
alert follows the NVR's rules (web settings → Security modes / alert rules).

A notification shows the snapshot. Tap it to play that camera from just
before the moment.

The NVR must be able to reach the push server's address (`ntfy.sh`, or your
own server) over the internet.

## Install

Build it yourself (below) or sideload a debug APK: copy it to the phone, open
it, and allow "install unknown apps" for the app you opened it from.
Requires Android 8.0+.

## First run

1. Enter your NVR's address. Its IP alone (`192.168.1.10`) is enough: the app
   finds LightNVR's HTTPS port (8443) by itself. The browser's
   `http://…:8080` also works, because the app switches it to HTTPS.
   **Better: sign in with the NVR's Tailscale name** (Settings → Remote
   access shows it, e.g. `lightnvr-1.tail1234.ts.net`). It works at home and
   away, and its certificate is publicly trusted. A Tailscale IP or short name
   works too: the app looks up the full name, because Tailscale's certificate
   is for that name only. The app keeps one address, so a home IP stops
   working when you leave home.
2. LightNVR ships a self-signed certificate, so the app shows its SHA-256
   fingerprint once and asks whether to trust it. After that it trusts **only
   that certificate**. That applies to the embedded web settings too. A
   publicly-trusted certificate (Let's Encrypt) is accepted without asking.
3. Sign in with a LightNVR account. A **viewer** account can watch and see
   visits. Changing the mode needs an **admin**.

The app stores the server's tokens, never your password. They're excluded from
cloud backup and device-to-device transfer, so a new phone signs in fresh.

## How video works

The app speaks the same media-engine websocket as the web app
(`/api/media/ws`): fragmented MP4, the camera's own codec, no re-encoding on
the NVR. Fragments are handed to Media3/ExoPlayer, which decodes with
MediaCodec (hardware).

- **Live:** each camera's stream is shared between viewers, so a phone
  joining mid-stream receives timestamps from when that stream started.
  `media/Mp4.kt` rebases them to start at zero.
- **Playback:** one session runs through several recording files. Each file's
  timestamps start at its own zero, and the NVR says where the file sits in
  the session (`offset`). `media/PlaybackProtocol.kt` puts the files on one
  running timeline in one player. The app reports the moment on screen every
  half second, and the NVR keeps at most ~20 s ahead of it, so a paused
  player costs nothing. Seeking opens a fresh socket.

Video streams only while it's on screen. Leaving the screen, scrolling a tile
away or backgrounding the app closes the socket, so the NVR stops sending
straight away.

If the phone can't decode a stream (for example, it runs out of hardware
decoders in a big grid), that tile shows snapshots instead, refreshed every
few seconds.

## Build

Open this folder in Android Studio, or from a shell:

```
./gradlew testDebugUnitTest assembleDebug lintDebug
```

The APK lands in `app/build/outputs/apk/debug/`. Toolchain: JDK 17, Android
SDK 35 (the wrapper fetches Gradle 8.9).

The UnifiedPush connector is pinned to **3.0.x**: 3.1 and later are built with
Kotlin 2.2, and this project uses Kotlin 2.0.

`app/src/test/resources/playback_capture.bin` is a real playback session from
an NVR. `tools/capture_playback.py` recreates it: run it inside a throwaway
backend container, never on a real NVR, because it adds a test camera and
recordings.
