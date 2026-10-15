# Mado on the 159K frame: what fits, what has to change

Assessed 2026-10-14 against abradburne/mado at commit 4343985 (2026-10-13).
Our frame: Digital Picture Frame 159K, RK3326 (4x A35, Mali-G31), 1 GB RAM, Android 8.1 (API 27),
1920x1080 at 160 dpi, WebView 113, network ADB with root (docs/digital-picture-frame-159k.md).

## What Mado is

Three parts: a Rust hub (HTTP + MCP + web config page, port 7878), a native Kotlin frame app
(Canvas views, no WebView, minSdk 23, one outbound WebSocket to the hub), and an optional Mac
menu-bar app for now-playing. The frame holds no secrets and listens on nothing. Content today:
painting of the day, time, weather (fetched by the frame from Open-Meteo), "projects" (name +
status + one-line note + icon, pushed by agents), alerts in three levels, now playing, a
companion creature, night mode. There is no calendar, no message feed, no e-mail/Slack/Teams.

## Fit with our hardware

| Topic | Mado assumes | We have | Verdict |
| --- | --- | --- | --- |
| Android | API 23+ | API 27 | fine |
| CPU/GPU | RK3126 + Mali-400, 32-bit | RK3326 + Mali-G31, 64-bit | better than its reference |
| RAM | 1 GB | 1 GB | same class; native app is the right choice here |
| Screen | 1280x800, layouts in absolute pixels | 1920x1080 | **main porting job** |
| Install | USB adb | network adb | fine; set ANDROID_SERIAL to ip:5555 in env.local.sh |
| Boot | BOOT_COMPLETED receiver works on 6-9; HOME intent offered | 8.1 | both work; needs Frameo disabled or Mado chosen as Home |
| Vendor app | "disable if it comes back" | net.frameo.frame still enabled | repo rule: prove the way back first |
| Hub host | always-on machine on the frame's LAN, no auth | not decided | needs a decision (see below) |
| Network ADB | its guide says do not turn it on | we already run it | keep frame on isolated network; document |

### Screen size

DashboardView, WeatherView, ProjectsView, MessagesView, AlertView, NowPlayingView and RailView
draw with literal pixel coordinates for 1280x800 (e.g. `drawPlaque(canvas, 52f, 44f, 520f …)`,
`canvas.drawRect(70f, 70f, 634f, 634f …)`, clock at 176 px). `frame_look` also says 1280x800.
Options, cheapest first:

1. **Scale the canvas 1.5x** in each view's `onDraw` (`canvas.scale(w/1280f, h/800f)` and divide
   touch coordinates by the same factor). 1920/1280 = 1.5 exactly, 1080/800 = 1.35, so either
   accept a slight vertical stretch, letterbox to 1920x1200-equivalent, or scale uniformly by 1.35
   and give the extra 192 px of width to a new column (see "What to add"). The author notes the
   frame's GPU "smudges scaled paths"; that is the Mali-400 on the reference device, so test on
   ours before assuming it.
2. Make the layouts relative to w/h. More work, touches every view, and the author may do it
   upstream; worth asking in an issue before forking.

## What to add for our use

Mado's extension points: the hub-to-frame socket (JSON with a `t` field; the frame handles
`notify`, `night`, `now_playing`, `hub`, `companion`, `art`, `projects`, `look`, `config`), the
HTTP API, and MCP tools. Everything below is new code on both sides or a creative reuse.

### 1. Outlook calendar (the main use)

- **Source:** Microsoft Graph (`/me/calendarView` with start/end, delta queries for changes).
  Needs an Entra app registration (delegated `Calendars.Read`, device-code or auth-code flow) and
  a refresh token kept on the hub. For a work tenant the admin may have to consent. Alternative
  with zero registration: the published ICS link of the calendar (Outlook → Share → Publish), but
  that lags by up to hours and shows no free/busy for others.
- **Hub:** a `calendar.rs` poller (every 2-5 min) that keeps the next 24-48 h of events and pushes
  `{ "t": "agenda", "items": [...] }` to the frame; expose `GET /api/agenda` for agents.
  The hub's data dir already holds config; add the token there (never in the repo).
- **Frame:** an `AgendaView` (new Canvas view) on the dashboard: today's timeline with the
  current/next meeting large, the rest as a list; a "next in 12 min" line near the clock; an
  optional nudge-level alert 5 min before a meeting. The 1920 px width makes room for a column
  beside the painting.
- **Cheapest first cut without frame code:** push the next few events as "projects" (name = time
  + title, note = location/organizer, status = on_track/needs_you). Ugly but zero Kotlin.

### 2. Feed of important mail / Slack / Teams

Mado already has the right primitive: alerts with `whisper`/`nudge`/`urgent`, a Messages page
keeping the last 200, and `source`. So the feed is mostly **senders**, not frame work:

- **Mail (Outlook):** Graph `/me/mailFolders/inbox/messages` filtered by importance, flagged,
  or a rule-driven folder ("Frame"), or a Graph change-notification webhook. Post
  `{text, level, source:"mail"}` to `/api/notify`. Keep text to a subject + sender; the body
  does not belong on a wall.
- **Teams:** Graph chat/channel message subscriptions need admin consent in most tenants and
  are the hardest; a practical shortcut is Outlook's "missed activity" e-mails from Teams, or a
  Power Automate flow ("when I'm @mentioned" → HTTP POST to the hub) which only needs your own
  account.
- **Slack:** a small bot with `app_mentions:read` / `im:history` (Events API) or a Slack
  workflow "send a webhook" step → `/api/notify`. Mentions and DMs only; channel traffic would
  make the frame noisy.
- **Frame work (optional):** a `FeedView` showing the last N items grouped by source with
  read/unread, instead of the transient alert. The MessagesView already lists alerts, so this may
  be a styling change plus a "pin to dashboard" config switch.

All of these need the hub to be reachable from wherever the sender runs: fine for scripts on
the LAN; for Graph webhooks or Slack events the hub needs a public HTTPS endpoint, which Mado
explicitly warns against (no auth). Use a relay (Tailscale Funnel, a Cloudflare tunnel, or a
tiny authenticated proxy) rather than exposing 7878.

### 3. Claude notification sink

Works out of the box: `claude mcp add --transport http mado http://HUB:7878/mcp` gives Claude
Code `frame_notify`, `frame_project`, `frame_look`, `frame_state`. For the desktop app, the
same URL as a remote MCP server (needs the hub reachable from the Mac). For scheduled or
cloud sessions the hub must be reachable from the cloud, same caveat as above. A Claude Code
hook (Stop / Notification events) that curls `/api/notify` covers "a session needs you" without
any MCP round trip.

## Host-side blockers on this Mac (today)

- Java: `/usr/bin/java` is the Apple stub; `openjdk@21` is installed via Homebrew but not on
  PATH (`JAVA_HOME=/opt/homebrew/opt/openjdk@21`).
- Android SDK: not installed (needs platform 36 + build-tools 36.1.0; `sdkmanager` via
  Homebrew `android-commandlinetools`).
- Rust: not installed (`brew install rustup` or `rustup-init`), unless the hub runs in Docker.
- Docker: installed but the daemon is not running.

## Decisions needed

1. **Where the hub runs.** Always-on box on the frame's LAN (NAS, mini PC, Raspberry Pi; Docker
   image is ready) or this Mac while trying it out. The frame must reach it; the Mac must reach
   it for MCP.
2. **Fork or upstream for 1920x1080.** Open an issue first; a scale-factor patch is a day, a
   relative layout is more.
3. **Calendar source:** Graph app registration (best) vs published ICS (no setup, laggy).
4. **Frameo's fate.** Mado as Home app (reversible) vs disabling Frameo (reversible, but prove
   the way back first).

## Suggested order

1. Build tools on the Mac; hub on the Mac for now; install the unmodified app on the frame with
   a 1.35x uniform scale hack; check `frame_look`. One evening.
2. Wire Claude Code MCP and a Slack/mail webhook sender to `/api/notify`. Same evening.
3. Calendar: hub poller + agenda view. The real project; a few evenings.
4. Decide the permanent hub host and move it.
