# Changelog

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
