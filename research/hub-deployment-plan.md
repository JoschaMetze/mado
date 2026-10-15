# Hub deployment: VM <vm-lan-ip> + Tailscale

Drafted 2026-10-15. Tailnet: <tailnet-name> (MagicDNS suffix <tailnet>.ts.net). This Mac is on it as
macbook-pro-von-joscha. The VM's tailnet name is not known yet (SSH user needed).

## Topology

```
 Mac / Claude Code ──(tailnet, HTTPS)──▶  VM .190 : mado-hub  ◀──(LAN or tailnet, ws)──  frame .129
 Slack / Graph webhooks ──(Funnel + auth proxy, later)──▶
```

Key fact: the frame never needs to be reachable. It dials out to the hub and keeps one
WebSocket open. "No public IP for the frame" is satisfied by design; Tailscale on the frame is
optional and only buys (a) the frame working from any network, (b) adb/root reachable from
anywhere on the tailnet.

Mado has two bind settings that fit this exactly:

| Setting | Bind to | Serves | Who reaches it |
| --- | --- | --- | --- |
| `MADO_BIND` | `127.0.0.1:7878` on the VM, published on the tailnet by `tailscale serve` | web page, HTTP API, MCP | Mac, Claude Code, agents (tailnet only, HTTPS, MagicDNS cert) |
| `MADO_FRAME_BIND` | `<vm-lan-ip>:7879` (LAN) or the VM's `100.x:7879` (tailnet) | frame socket + images only | the frame |

With `tailscale serve --bg --https=443 http://127.0.0.1:7878` (or a sub-path), the hub's API is
`https://<vm>.<tailnet>.ts.net/` and the MCP endpoint `https://<vm>.<tailnet>.ts.net/mcp`.
Nothing of the hub is reachable from the LAN except the frame port, and the frame port cannot
send or change anything (Mado README, "Security").

Docker `0.0.0.0` publishing is the thing to avoid: map ports explicitly
(`127.0.0.1:7878:7878`, `<vm-lan-ip>:7879:7879`).

## Option 1 (recommended for one container): docker compose on the VM

```yaml
services:
  hub:
    build: ./hub
    restart: unless-stopped
    ports:
      - "127.0.0.1:7878:7878"
      - "<vm-lan-ip>:7879:7879"      # frame-only port on the LAN
    environment:
      MADO_FRAME_BIND: "0.0.0.0:7879"
      MADO_ADVERTISE: "off"             # frame gets the address by hand
      MADO_NAME: "Mado on vm"
    volumes:
      - mado-data:/data
volumes:
  mado-data:
```

Then `tailscale serve --bg --https=443 http://127.0.0.1:7878` on the VM. Updates = `git pull`
and `docker compose up -d --build`. The VM must have Docker; if not, `cargo build --release`
plus a systemd unit works too (hub is a single static binary).

## Option 2: Openship on the VM

What the docs say (openship.io/docs, read 2026-10-15; docs are marked work in progress):

- Self-host needs Linux, Docker + Compose, and **ports 80, 443, 3001 (dashboard), 4000 (API)**
  free; it brings Postgres, Redis and an OpenResty edge. Install: `curl -fsSL
  https://get.openship.io | sh` then `openship` (guided) or `openship up`; or clone the repo
  and `docker compose --env-file .env -f docker/docker-compose.yml up -d`. Set
  `OPENSHIP_PUBLIC_URL` or remote login is refused.
- Deploy = `openship init` + `openship deploy` in the project, config in `openship.json`.
  Mado's hub is a plain Dockerfile; openship.json has no top-level `dockerfile` key, but a
  Compose-style `services[]` entry supports `build`, `dockerfile`, `ports`, `exposed`,
  `exposedPort`, `volumes`, `env`. `composePath` can point at Mado's own `compose.yaml`.
- Routing is domain-based through the edge on 80/443 (WebSockets fine through OpenResty).
  **Binding an exposed port to one interface (LAN vs tailnet) is not documented**, so the
  frame port would be on all interfaces and the VM firewall would have to do the separation
  (`ufw allow from <lan-subnet> to any port 7879`, `ufw allow from 100.64.0.0/10 to any port
  7878`).
- Verdict: fine if Openship is wanted on that VM anyway (dashboards, more apps, Git-driven
  deploys). For the hub alone it is four extra services and two privileged ports for one
  20 MB binary. Decide on the VM's other duties first; 80/443 already in use by something else
  is the usual blocker.

## Tailscale on the frame (optional)

- Official app: `tailscale-android-universal-1.104.1.apk` from the GitHub releases
  (2026-10-08, 103 MB, `minSdk 26`; the frame is API 27). Login without a browser: Settings →
  Accounts → ⋮ → "Use an auth key" (pre-generated, tagged, reusable or not, in the admin
  console). Needs the VPN consent dialog once on the touchscreen and "start on boot" in the
  app. RAM cost is the question on a 1 GB device; measure with `dumpsys meminfo`.
- Alternative with root: `tailscale_1.104.1_arm64.tgz` static binaries, `tailscaled` run as
  root. Checked 2026-10-15: kernel 4.4.138 has `CONFIG_TUN=y`, misc device 200 `tun` is
  registered and the node exists as `/dev/tun` (Android convention, owner system:vpn); there is
  no `/dev/net/tun`, so tailscaled needs `mkdir /dev/net && ln -s /dev/tun /dev/net/tun` at
  each boot, plus something to start it (Mado's own `BootReceiver`, or a one-line `su -c`).
  iptables 1.6.1 and policy routing are present. The frame reaches the VM on the LAN (4 ms).
- Either way, lock it down in the tailnet ACL: the frame should only reach the hub's frame port,
  and only the Mac should reach the frame's 5555.

## Order

1. SSH user for the VM, then read-only check: OS, Docker, Tailscale, free ports 7878/7879
   (and 80/443/3001/4000 if Openship is wanted).
2. Join the VM to the tailnet if it is not; `tailscale serve` for 7878.
3. Hub up (Option 1 or 2), `curl https://<vm>.<tailnet>.ts.net/api/state` from the Mac.
4. Frame app build (needs JDK 21 on PATH, Android SDK 36) and install over
   `adb -s <frame-ip>:5555`, hub address `ws://<vm-lan-ip>:7879/frame`.
5. `claude mcp add --transport http mado https://<vm>.<tailnet>.ts.net/mcp` on the Mac.
6. Tailscale on the frame only if wanted after 1-5 work.

## Log 2026-10-15: VM prepared, Openship blocked by the virtual CPU

- VM `<vm>` (Ubuntu 24.04, 4 vCPU, 3.8 GB, no swap): Docker CE 29.9.1 installed, joscha in
  the docker group, ufw rules recorded (ufw itself left inactive), Tailscale logged in as
  `<vm>.<tailnet>.ts.net` / <vm-tailnet-ip>. Mado cloned to `~/mado`. Openship CLI 0.9.1 in
  `~/.openship` (vendored Node 22). A pending kernel upgrade wants a reboot.
- In compose mode the dashboard binds to loopback or to all interfaces; `--host <ip>` is
  ignored, `--public-url` forces `0.0.0.0`. Tailnet-only access would need `tailscale serve`
  (requires `sudo tailscale set --operator=joscha` once) plus `OPENSHIP_EXTRA_TRUSTED_ORIGINS`.
- `openship up --public-url http://<vm>.<tailnet>.ts.net:3001` pulled the stack; postgres,
  redis and edge came up, **the API container never became healthy**: it runs on Bun, and the
  VM's CPU is "Common KVM processor" with only SSE2 (no SSE3/SSE4/AVX). `bun -e console.log(1)`
  inside the container took 40 s and printed nothing; the API used up to 3.3 GB and restarted
  six times (exit 0, no logs). Stack stopped (`openship stop` + `docker compose stop`).
- Fix is on the hypervisor: set the VM's CPU type to `host` (Proxmox: Hardware → Processors →
  Type), reboot, then re-run `openship up`. Also consider a 2 GB swap file; the API alone
  wanted 1.7 GB RSS on this CPU.
- Host control wrote one Openship SSH key into `~/.ssh/authorized_keys` on the VM (container
  → host operations as joscha). Remove it if Openship is dropped.
- Later on 2026-10-15: VM CPU type changed to host (i7-4500U, AVX2), RAM 5.7 GB, 2 GB swapfile,
  root LV grown 30 -> 93 GB (disk was 64 GB with 32 GB unpartitioned; `growpart` + `pvresize`
  + `lvextend -r`). Passwordless sudo for joscha via /etc/sudoers.d/joscha. `openship up` then
  succeeded: api/dashboard/edge/postgres/redis healthy, dashboard on
  http://<vm>.<tailnet>.ts.net:3001 (bound 0.0.0.0, so LAN too), API on :4000.
- The instance installed in zero-auth mode: the dashboard refuses remote logins and the CLI's
  `--local` credential answers HTTP 409 until an admin exists. Fix applied: `OPENSHIP_AUTH_MODE=local`
  appended to ~/.openship/compose/.env (backup kept) and `docker compose up -d`; the login page
  now shows email/password. Admin login is set with
  `openship reset-admin-password --name … --email … [--password]` (owner's step).
  Watch out: a later `openship up` regenerates files; re-check that the .env line survives.
- `openship.json` for the hub validated (`openship config validate`), lives in ~/mado on the VM.
  Deploy command: `cd ~/mado && openship --local deploy --folder --name mado-hub --watch`.

## Result 2026-10-15: hub running under Openship

- Admin created headlessly: `openship up --public-url http://<vm>.<tailnet>.ts.net:3001
  --non-interactive --admin-name … --admin-email … --domain-kind none` with
  `OPENSHIP_ADMIN_PASSWORD` in the environment (public sign-up is disabled on self-hosted
  instances; `reset-admin-password` needs an existing admin). `openship up` keeps the
  operator-set `OPENSHIP_AUTH_MODE=local` line ("Kept 1 operator-set .env variable").
- Hub project: `mado-hub` (`proj_4TSrgFy_PK0_8lUX`), deployed from `~/mado` on the VM with
  `openship --local deploy --folder --watch`; the folder is linked via `.openship/project.json`.
  Config: `openship.json` = `{"composePath": "deploy/openship-compose.yml"}`; the compose file
  builds `../hub` and publishes `<vm-tailnet-ip>:7878` (API/MCP/web, tailnet) and
  `<vm-lan-ip>:7879` (frame socket, LAN). Bare `"7878:7878"` mappings get rewritten to
  127.0.0.1 by Openship; `exposed: true` demands a domain and fails pre-deploy on a
  self-hosted server. Each `--folder` deploy without a link created a new project; two stale
  ones were deleted.
- Verified: `GET http://<vm>.<tailnet>.ts.net:7878/api/state` and MCP `initialize` work from
  the Mac over the tailnet; port 7879 is closed on the tailnet; the frame reaches
  `<vm-lan-ip>:7879` on the LAN.
- Next: build the frame app (JDK 21 + Android SDK on the Mac), install over
  `adb -s <frame-ip>:5555`, hub address `ws://<vm-lan-ip>:7879/frame`; then
  `claude mcp add --transport http mado http://<vm>.<tailnet>.ts.net:7878/mcp`.
- Observation: network ADB to the frame drops every so often ("no route to host" while the
  port is open); `adb kill-server` + reconnect fixes it. Probably Wi-Fi power saving on the frame.

## Frame app 2026-10-15

- Toolchain on the Mac: Homebrew `openjdk@21`, `android-commandlinetools` cask with
  `platforms;android-36`, `build-tools;36.1.0`, `platform-tools` (licences accepted by the owner),
  Gradle 9.8.1 via the wrapper. `frame-app/env.local.sh` sets JAVA_HOME, ANDROID_HOME and
  ANDROID_SERIAL=<frame-ip>:5555.
- `frame-app/` is our copy of Mado's app (see frame-app/ORIGIN.md). One change: MainActivity puts
  the 1280x800 view tree on a stage scaled by screen height / 800 (1.35 on the frame), logical
  canvas 1422x800. Build: `./gradlew assembleDebug` (44 s). Install + start:
  `adb install -r app/build/outputs/apk/debug/app-debug.apk` then
  `am start -n jp.co.xenocode.mado/.MainActivity --es hub ws://<vm-lan-ip>:7879/frame`.
- Result: hub lists frame #1 as "rockchip 159K" 1920x1080 api 27 app 0.2.1; `/api/look` returns
  a 1422x800 JPEG that fills the screen without bars; clock, date, companion drawn correctly.
- Not changed: Frameo stays the home app; Mado's BootReceiver will put the app on screen after
  a boot (Android 8.1 allows it). Making Mado the home app is the owner's decision.

## 2026-10-15 later: hub data permission, orientation option

- "could not save config: Permission denied" on the hub page: the image runs as `nobody` and
  Openship's fresh named volume was root-owned. Fixed once with
  `docker run --rm -v <volume>:/data alpine chown -R 65534:65534 /data` (volume
  `openship-openship-upload-enhobz-mado-data`); it persists across deploys. If the volume is ever
  recreated (`project delete --wipe-volumes`), repeat it.
- Frame app: orientation option (landscape / portrait / landscape-flipped / portrait-flipped),
  saved on the frame, switchable from the rail's new Orientation row or with
  `am start -n jp.co.xenocode.mado/.MainActivity --es orientation portrait`. Verified: the
  panel rotates (framebuffer 1080x1920), the stage refits to 1280x2276 logical, no crash, hub
  `look` returns the tall image. Left in landscape.

## 2026-10-15, evening: git + fork

- Fork: https://github.com/JoschaMetze/mado (public, fork of abradburne/mado). The project folder
  is now a git repo: `origin` = the fork (HTTPS, gh credentials), `upstream` = abradburne/mado.
  Local `main` = upstream 4343985 + our commit a22ab86 + merge of upstream 5999bd1 (quests,
  several hub addresses). Not pushed yet: research/ and deploy/ carry LAN and tailnet addresses
  and the fork is public; decide scrub vs. keep-out first. Ignored: frame-159k backups/helpers/
  ranges, the private frame record, research/mado, build dirs, env.local.sh, log/.
- Dashboard: next-up card in all four layouts (plaque on paintings), continues with tomorrow,
  larger type for reading across the room; the earlier one-line version is gone.
- The VM copy of the project (~/pictureframe) is synced with rsync from the project root; the
  Openship link lives in its .openship/project.json.
