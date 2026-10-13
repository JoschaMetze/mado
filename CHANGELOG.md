# Changelog

## Unreleased

- Art of the day has more to choose from. The hub's page takes any mix of three sources (the
  Art Institute of Chicago, the Cleveland Museum of Art, and WikiArt's 600 most viewed
  paintings) and of four kinds of work (paintings, prints, drawings and watercolours,
  photographs). The day picks the source, and the others stand in if it has nothing that
  fits. A switch lets the Art Institute send works still in copyright as well, for a frame
  at home. Left alone it is as before: public-domain paintings from the Art Institute.
- Night mode. During its night hours the frame shows only a deep amber clock on black, with
  the backlight at its lowest and Tama asleep. Whispers and nudges that arrive are kept and
  shown in the morning; urgent alerts show at once. A touch or two knocks wakes it for 45
  seconds, or for good if it was put to sleep by hand outside its night hours. Music playing
  keeps it up. The hours (23:00 to 07:00 to begin with) are set on
  the hub's page and kept by the frame, so nights work with the hub down. The rail has a
  Sleep switch, and `frame_sleep`, `frame_wake`, `POST /api/sleep` and `POST /api/wake` do the
  same from outside; `frame_state` says whether the frame is asleep.
- The glass turns itself down to 60% of the chosen brightness between sunset and sunrise.
- The now-playing scene has a "Close" in its top right corner, like the weather and projects
  pages, and the rail lists those two pages beside the scenes.
- Tama, the frame's companion: a small creature on the far side of the glass. It wanders
  (keeping off the words), has hobbies it picks by mood, reacts to touch, tilt and knocks,
  dresses for the weather, wears headphones while music plays, minds alerts, listens for a
  lost hub on a tin-can telephone, and remembers its favourite spot and artists. The rail can
  hide it and mute it; it is silent unless its sounds are switched on.
- `frame_companion` and `POST /api/companion` ask Tama for a gesture, a mood or a hobby, and
  `frame_state` says what it is doing.
- Projects can have an icon, so they are recognised at a glance: `frame_project` and
  `POST /api/project` take `icon` (the URL of a PNG or JPEG, or the image in base64), and the
  hub's page takes an upload. A project without one gets its initials on a coloured disc.
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
- Frame: tap the weather on the dashboard for a weather page. The place's sky fills the glass
  (its colour by day and night, with cloud, rain, snow, stars or lightning), the sun or moon
  sits on its arc between sunrise and sunset, the next 24 hours are a temperature curve over
  the chance of rain, and the week is a strip along the bottom. Every place has a tab; tap one
  or drag sideways and the sky and the curve flow into the other place. Tapping another
  place's line on the dashboard opens the page there. A tap closes it, and it closes by itself
  after two minutes.
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
