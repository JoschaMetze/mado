# Mado

Mado (窓, "window") turns an old Android photo frame into a display your other machines and
agents can send things to. It is not a browser kiosk: the frame runs a small native app, so
it stays smooth on very modest hardware.

What works today:

- **Now playing.** The album cover of whatever Apple Music is playing on your Mac, with a
  backdrop made from the cover. Tap the cover to fill the screen with it; tap the text to
  play or pause.
- **Liquid alerts.** Anything on your network can put a message on the glass at one of three
  levels: a *whisper* (a bead at the edge), a *nudge* (one line that leaves by itself) or
  *urgent* (stays until dismissed).
- **MCP.** Agents get `frame_notify`, `frame_look` (a screenshot of the frame) and
  `frame_state`.
- **The rail.** Tap or swipe the left edge of the frame for scenes, brightness and the way
  back to the launcher.

Planned: Tama (a small on-screen companion), weather for several places, a dashboard, agent
canvases, night mode.

## How it fits together

```
 senders                         hub                          frame
 ───────                         ───                          ─────
 Mac helper (now playing) ─┐
 agents over MCP ──────────┼──▶  mado-hub  ◀── one socket ──  Mado app
 curl, scripts, cron ──────┘     (Rust)       frame dials out (Kotlin)
```

| Folder | What it is |
|---|---|
| `hub/` | `mado-hub`, the brain. Rust. Serves the frame's WebSocket, an HTTP API, MCP, and a web interface where all configuration lives. Runs on a home server or on a Mac. |
| `frame-app/` | The Android app that draws everything. Kotlin, plain Canvas views, `minSdk 23`. |
| `nowplaying/` | `mado-nowplaying`, a macOS helper that reports Apple Music to the hub. Rust. |

The frame holds no secrets and listens on no ports: it opens one outbound connection to the
hub and draws what it is told. It keeps its clock (and, later, weather) working when the hub
is down.

## Running it

**1. The hub**, on any machine the frame can reach:

```sh
cd hub
cargo run --release
```

It listens on `0.0.0.0:7878` (`MADO_BIND` to change) and keeps its config in `./data`
(`MADO_DATA`). Open `http://HOST:7878/` for the web interface.

**2. The frame app.** With the frame connected over USB and `adb` working:

```sh
cd frame-app
./run.sh ws://HOST:7878/frame
```

The hub address is remembered; later runs need no argument. Put machine-specific setup such
as `JAVA_HOME` or `ANDROID_SERIAL` in `frame-app/env.local.sh`.

**3. Now playing**, on the Mac with the music:

```sh
cd nowplaying
cargo run --release -- http://HOST:7878
```

macOS asks once for permission to control Music. The helper never launches Music.

## Talking to the hub

Send an alert:

```sh
curl -X POST http://HOST:7878/api/notify \
  -H 'content-type: application/json' \
  -d '{"text": "Backup finished", "level": "nudge", "source": "nas"}'
```

| Endpoint | |
|---|---|
| `POST /api/notify` | `text`, optional `level` (`whisper`, `nudge`, `urgent`), `source`, `ttl_s` |
| `GET /api/look` | JPEG of what the frame is showing |
| `GET /api/state` | Connected frames, now playing, config, recent activity |
| `POST /api/now_playing` | What a player is doing; see `nowplaying/src/main.rs` for the shape. A helper for another player (Spotify, say) posts the same JSON. |
| `POST /mcp` | MCP over streamable HTTP |

For an MCP client, add `http://HOST:7878/mcp` as a remote server.

## Security

The hub has no authentication yet. Run it where only your own devices can reach it, for
example bound to a [Tailscale](https://tailscale.com) address, and do not expose it to the
internet. The frame itself does not need to be on the tailnet; it only needs to reach the hub.

## Hardware

Developed on a Uhale 102KZ: Rockchip RK3126, Mali-400 (OpenGL ES 2.0), 1 GB RAM, Android
6.0.1, 1280×800. The app targets that class of device: no Compose, no large frameworks,
artwork is resized and blurred on the hub so the frame only has to draw it. Layouts currently
assume a 1280×800 landscape screen.

The app must be installable on the frame, which for most photo frames means getting `adb`
access first. How to do that depends on the device and is not covered here.
