# Changelog

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
are in Celsius; the Docker image and the CI workflow have not been run yet.
