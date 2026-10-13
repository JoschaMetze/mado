# Changelog

## Unreleased

- Tama, the frame's companion: a small creature on the far side of the glass. It wanders
  (keeping off the words), has hobbies it picks by mood, reacts to touch, tilt and knocks,
  dresses for the weather, wears headphones while music plays, minds alerts, listens for a
  lost hub on a tin-can telephone, and remembers its favourite spot and artists. The rail can
  hide it and mute it; it is silent unless its sounds are switched on.
- `frame_companion` and `POST /api/companion` ask Tama for a gesture, a mood or a hobby, and
  `frame_state` says what it is doing.
- A boot splash: Mado's mark in place of the vendor's power-on logo and boot animation.
  `assets/splash.py` builds both from `assets/splash.png`; `docs/boot-splash.md` says how to
  install them and how to go back.
- Frame: Mado can be chosen as the home app, so the frame boots straight into it instead of
  showing the launcher first. The rail's "Home screen" entry still opens the other launcher.
- Art of the day can be steered: a period (before 1800, the 1800s, 1860 onwards), a subject
  (landscapes, sea and water, portraits, still lifes, flowers, animals, towns), and things
  to leave out (religious scenes, nudes, death and war). Set on the hub's page, which then
  picks a new painting that fits and says how many it chose from.
- Hub page: "Recent" shows the newest six lines and folds the rest away.
- Frame: tap the projects on the dashboard for a page of all of them, with each one's whole
  note, progress and when it was last reported. It scrolls when the list is long; a tap
  closes it, and it closes by itself after two minutes.

## 0.2.1

- An icon: a framed painting with an alert bead on its edge. Used by the Mac app, the frame
  app's launcher entry and the hub's web page. `assets/render-icons.sh` regenerates every size
  from `assets/icon.svg`.

## 0.2.0

- Mado for Mac: a Swift menu bar app replaces the command-line `mado-nowplaying` helper. It
  reports Apple Music and Spotify, can pause sharing, starts at login if asked, and builds
  from the command line with XcodeGen. Scripts for Developer ID signing and notarization.
- Hub: `MADO_FRAME_BIND` opens a second port that serves only what a frame needs, so the
  API, MCP and web page can stay on a private network.
- Hub: the web page is redesigned around a live picture of the frame, which refreshes
  itself every 30 seconds. Alert levels are shown as pictures, projects can be edited and
  given progress, any place can be made "here", now playing and the painting's story and
  palette are shown, and every form says what it did. Still server-rendered with no script.
- Hub: a second look for the web page, "Workbench": monospace, hairline rules and numbered
  sections. Switch between it and the default "Gallery" in the page header; the choice is
  saved in the hub's config.
- Hub: exits promptly on SIGTERM, so `docker stop` no longer waits to kill it.

## 0.1.0

First release.

- **Hub** (`mado-hub`): WebSocket for frames, HTTP API, MCP server (`frame_notify`,
  `frame_project`, `frame_look`, `frame_state`), and a web page for all configuration.
  Picks a public-domain painting each day and prepares it for the frame. Keeps project
  status. Relays now-playing from a helper and play/pause taps back to it. Dockerfile and
  compose file included.
- **Frame app**: dashboard in four layouts coloured from the day's painting, with "learn
  more", the time, weather for here and for other places, and projects. Now-playing scene
  with a full-screen cover mode. Whisper, nudge and urgent alerts. A rail on the left edge
  for scenes and brightness. Starts when the frame powers up (Android 6 to 9).
- **Now-playing helper** (`mado-nowplaying`): reports Apple Music on macOS.

Known limits: the hub has no authentication; layouts assume a 1280×800 screen; temperatures
are in Celsius; the Docker image has not been built and run yet.
