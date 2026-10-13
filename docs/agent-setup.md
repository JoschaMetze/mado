# Setting up Mado on a rooted Android frame: a guide for agents

You are an AI coding agent helping someone turn an Android photo frame into a Mado frame.
This file tells you what to do and what to leave alone. The human reading over your
shoulder is the frame's owner; the frame sits in their home.

Read the whole file before you run anything.

## What you are setting up

Three pieces (see the [README](../README.md) for what they do):

1. **The hub** (`hub/`), on a machine that is always on and that the frame can reach.
2. **The frame app** (`frame-app/`), installed on the frame over `adb`.
3. **The menu bar app** (`mac/`), optional, on a Mac that plays Apple Music or Spotify.

The frame makes one outbound WebSocket connection to the hub. Nothing connects in to the
frame, and the frame holds no secrets.

## Ground rules

- **Ask before anything that cannot be undone.** Flashing partitions, deleting or disabling
  system apps, changing the home app, factory resets. Say what you intend and wait for a yes.
- **Do not make the frame play sound** without asking. It is in someone's living space.
- **Do not turn on network adb** (`adb tcpip`, `service.adb.tcp.port`) unless the owner asks.
  On a rooted device it gives root to anyone on the network.
- **Urgent alerts stay on screen until someone taps them.** Test with `nudge`, and ask
  before sending `urgent`, because the owner has to walk over and dismiss it.
- **Keep device details out of the repository**: serial numbers, IP addresses, hostnames,
  place names, anything from the owner's hub `data/` directory. Machine-specific build
  settings go in `frame-app/env.local.sh`, which git ignores.
- **Report what you verified and what you did not.** If you could not see the screen, say so;
  `GET /api/look` on the hub returns a picture of it.

## What "rooted" is needed for

Installing and running Mado needs only working `adb`. Root is what usually gets you there on
a locked-down frame, and it is what lets you stop the vendor's own frame app from taking the
screen back. How to root a particular frame is outside this guide and differs per device. If
`adb devices` does not list the frame as `device`, stop and tell the owner; do not attempt to
root it yourself unless they ask and you have instructions for that exact model.

## Step 1: learn the device (read-only)

```sh
adb devices                                   # must show the frame as "device"
adb shell getprop ro.build.version.sdk        # needs 23 (Android 6.0) or higher
adb shell getprop ro.product.model
adb shell wm size                             # layouts assume 1280x800 landscape
adb shell wm density
adb shell cat /proc/meminfo | head -3
adb shell su -c id                            # is root available without a prompt?
adb shell pm list packages -3                 # what is installed
adb shell dumpsys window windows | grep mCurrentFocus   # what owns the screen now
```

Tell the owner what you found before going on. Things that change the plan:

- **API level below 23**: the app will not install. Stop.
- **A screen that is not 1280x800**: the app runs, but layouts are drawn in pixels for that
  size and will look wrong. Say so; fixing it is a code change in the `*View.kt` files.
- **Android 10 or later**: everything works except starting at boot (see step 5).
- **A vendor frame app that relaunches itself**: note its package name for step 5.

## Step 2: build tools

You need a JDK (17 or newer; 21 is what the project is built with), the Android SDK with
platform 36, and a Rust toolchain. Check before installing anything:

```sh
java -version; cargo --version; ls "$ANDROID_HOME/platforms" 2>/dev/null
```

If something is missing, tell the owner what you propose to install and where; disk space and
package managers are their call. Put any environment setup the build needs (for example
`JAVA_HOME`, `ANDROID_HOME`, `ANDROID_SERIAL`) in `frame-app/env.local.sh`:

```sh
export JAVA_HOME=/path/to/jdk
export ANDROID_HOME=/path/to/android/sdk
export ANDROID_SERIAL=the-frame-serial     # only needed with several devices attached
```

## Step 3: run the hub

Ask the owner where the hub should live. It must be always on and reachable from the frame's
network: a home server is ideal, the owner's main computer works for trying it out.

With Docker, from the repository root:

```sh
docker compose up -d --build
```

Without Docker:

```sh
cd hub && cargo run --release
```

The hub listens on port 7878 and keeps its settings in `./data` (or the `mado-data` volume).
Check it, then find the address the frame should use:

```sh
curl -s http://localhost:7878/api/state
```

Use an address that will not change: a hostname, or an IP the router has reserved. A laptop
that moves between Wi-Fi and Ethernet changes address, and the frame then shows "no hub".

The hub has no authentication. It must not be reachable from the internet. If the owner uses
Tailscale or similar, bind or firewall it so only their devices can reach it. A machine with
a host firewall may need port 7878 opened for the frame.

## Step 4: install the frame app and connect it

```sh
cd frame-app
./run.sh ws://HUB_ADDRESS:7878/frame
```

This builds a debug APK, installs it, launches it, and tells it where the hub is (the address
is remembered). Then confirm from the hub's side:

```sh
curl -s http://HUB_ADDRESS:7878/api/state      # "frames" should list one frame
curl -s http://HUB_ADDRESS:7878/api/look -o look.jpg   # then look at the picture
curl -s -X POST http://HUB_ADDRESS:7878/api/notify \
  -H 'content-type: application/json' \
  -d '{"text": "Hello from the hub", "source": "setup"}'
```

Open `look.jpg` and check it yourself. You should see the dashboard: a painting, the time,
and after the test alert a teal message from the right edge.

If no frame appears within about 20 seconds:

- A small red dot at the bottom left of the frame's screen means it cannot reach the hub.
- `adb shell ping -c 1 HUB_ADDRESS` checks the route from the frame.
- `adb logcat -d | grep -iE "FATAL|AndroidRuntime"` shows a crash, if there was one.
- The frame's saved address is in
  `/data/data/jp.co.xenocode.mado/shared_prefs/mado.xml` (readable with `su`).

## Step 5: make it stick

**Start at power-on.** The app registers for the boot broadcast and puts itself on screen when
the frame powers up. This works on Android 6 to 9. It only takes effect after the app has
been opened once, which step 4 did. You can test it without rebooting:

```sh
adb shell input keyevent KEYCODE_HOME
adb shell su -c 'am broadcast -a android.intent.action.BOOT_COMPLETED -n jp.co.xenocode.mado/.BootReceiver'
adb shell dumpsys window windows | grep mCurrentFocus    # should name jp.co.xenocode.mado
```

The boot broadcast arrives after the launcher is already showing, and Android 10 and later
block it altogether. To boot straight into Mado, make it the home app: it offers itself as
one, so press Home (`adb shell input keyevent KEYCODE_HOME`) and choose Mado, then "Always".
This changes the home app, so ask the owner first. The old launcher stays installed and the
rail's "Home screen" entry opens it; undo the choice under Settings, Home.

**The boot splash (optional).** Until Mado appears the frame still shows the vendor's logo and
boot animation. [boot-splash.md](boot-splash.md) replaces both with Mado's. Offer it; do not
do it unasked. It writes to `/system`, and on Rockchip frames to a boot partition, where a
bad write leaves a frame that does not start: take the backups that page describes and get
a yes for each part.

**Keep the screen on.** The app holds the screen on while it is in front. If the frame
sleeps anyway, check `adb shell settings get system screen_off_timeout`, and ask before
changing it.

**The vendor's frame app.** If it keeps coming back over Mado, ask the owner how they want
to deal with it. Disabling is reversible and is the right first step:

```sh
adb shell su -c 'pm disable VENDOR_PACKAGE'      # undo with: pm enable VENDOR_PACKAGE
```

Deleting it from `/system` is not reversible without a backup. Do not do it unless the owner
asks, and take a copy of the APK first.

**Getting out of Mado.** The frame probably has no Home button. A tap or swipe on the left
edge of the screen opens Mado's rail, which has a "Home screen" entry. It opens the frame's
other launcher, also when Mado is the home app. Tell the owner this.

## Step 6: settings

Everything is configured in the hub's web page, `http://HUB_ADDRESS:7878/`. Walk the owner
through it rather than editing files:

- **Weather places.** Search by city. The first place is "here"; others get a line each with
  their local time, and can be renamed ("Mum & Dad").
- **Projects.** Added by agents through `frame_project`, or by hand here.
- **Art of the day.** "Another painting" picks a different one for today.

## Step 7: connect agents

Add the hub to an MCP client as a remote (streamable HTTP) server at
`http://HUB_ADDRESS:7878/mcp`. For Claude Code:

```sh
claude mcp add --transport http mado http://HUB_ADDRESS:7878/mcp
```

Tools: `frame_notify`, `frame_companion`, `frame_project`, `frame_look`, `frame_state`. `frame_look` returns an
image of the frame's screen, which is how an agent checks its own work.

## Step 8 (optional): now playing from a Mac

On the Mac that plays the music. This needs Xcode and
[XcodeGen](https://github.com/yonaskolb/XcodeGen); check for both before installing anything.

```sh
cd mac && scripts/build.sh install
```

That builds Mado.app, copies it to `/Applications` and opens it. It lives in the menu bar
and has no window. Then, with the owner:

- Choose **Hub Address…** in its menu and enter the hub's address.
- macOS asks the owner to allow Mado to control Music (and Spotify, if used). They must
  click Allow; you cannot do it for them. Until they do, the app reports nothing.
- **Start at Login** is a switch in the same menu. Ask before turning it on.

The app never launches a player. When a track plays, the frame switches to the cover; two
minutes after a pause it returns to the dashboard.

The build is ad-hoc signed unless `mac/Local.xcconfig` names a signing team. That is fine on
the Mac that built it. Do not put anyone's team ID or identity in a tracked file.

## Things that will confuse you

- **The clock reads 1970 after a power cut** on frames without a clock battery, until the
  network time arrives. The app waits for a real date before asking for the day's painting,
  and weather starts working once the time is right.
- **Weather uses HTTPS with its own root certificates.** Android 6 does not trust Let's
  Encrypt, so the app bundles the two ISRG roots in `frame-app/app/src/main/res/raw/` and
  trusts only those for its weather requests.
- **Debug builds can crash in Android 6's runtime** when two network requests first run the
  same code at the same time (a SIGSEGV inside `libart.so`, not a Java exception). The
  weather client is limited to one request at a time for this reason. If you add network
  code, keep requests sequential and test on the device.
- **The device's own `tar` and other tools may be very old.** Prefer `adb push`/`adb pull`.
- **`adb root` often does nothing on vendor ROMs.** Use `adb shell su -c '...'`.

## When you are done

Tell the owner, in plain words:

- what is installed and where the hub runs,
- the hub's web address,
- how to leave Mado (left edge, "Home screen"),
- anything you changed on the frame beyond installing the app,
- anything you could not verify.
