//! mado-nowplaying: tells a Mado hub what Apple Music is playing on this Mac.
//!
//! Usage: `mado-nowplaying [HUB_URL]` (or set `MADO_HUB`; default http://localhost:7878).
//!
//! It never launches Music. Reading the player goes through AppleScript, so macOS asks once
//! for permission to control Music. Other players can feed the hub the same way: POST the
//! same JSON to `/api/now_playing`.

use std::{
    env, fs,
    io::Read,
    process::Command,
    thread,
    time::{Duration, Instant},
};

use base64::Engine;
use serde_json::{json, Value};

const POLL: Duration = Duration::from_millis(1500);
/// The hub treats a silent helper as stopped after 30 s, so repeat ourselves well inside that.
const HEARTBEAT: Duration = Duration::from_secs(10);
/// A position this far from where steady playback would be means the listener scrubbed.
const SCRUB_S: f64 = 2.5;
const MAX_ART_BYTES: u64 = 10 << 20;

const READ_TRACK: &str = r#"
if application "Music" is not running then return "stopped"
tell application "Music"
    set s to player state as text
    if s is "stopped" then return "stopped"
    set d to character id 31
    set t to current track
    return s & d & (name of t) & d & (artist of t) & d & (album of t) & d & (duration of t) & d & (player position)
end tell
"#;

const WRITE_ARTWORK: &str = r#"
on run argv
    tell application "Music"
        try
            set bytes to raw data of artwork 1 of current track
        on error
            return "none"
        end try
    end tell
    set f to open for access POSIX file (item 1 of argv) with write permission
    set eof f to 0
    write bytes to f
    close access f
    return "ok"
end run
"#;

struct Track {
    playing: bool,
    title: String,
    artist: String,
    album: String,
    duration_s: f64,
    position_s: f64,
}

impl Track {
    fn key(&self) -> String {
        format!("{}\u{1f}{}\u{1f}{}", self.title, self.artist, self.album)
    }
}

fn osascript(script: &str, args: &[&str]) -> Option<String> {
    let out = Command::new("osascript")
        .arg("-e")
        .arg(script)
        .args(args)
        .output()
        .ok()?;
    out.status
        .success()
        .then(|| String::from_utf8_lossy(&out.stdout).trim_end_matches('\n').to_owned())
}

/// None when Music is closed, stopped, or will not say.
fn read_track() -> Option<Track> {
    let out = osascript(READ_TRACK, &[])?;
    let f: Vec<&str> = out.split('\u{1f}').collect();
    if f.len() != 6 {
        return None;
    }
    // AppleScript formats numbers for the system locale, which may use a decimal comma.
    let number = |s: &str| s.replace(',', ".").parse::<f64>().unwrap_or(0.0);
    Some(Track {
        playing: f[0] != "paused",
        title: f[1].to_owned(),
        artist: f[2].to_owned(),
        album: f[3].to_owned(),
        duration_s: number(f[4]),
        position_s: number(f[5]),
    })
}

/// The cover for the current track: from Music if it has one, otherwise looked up in the
/// iTunes catalogue by artist and album (streamed tracks often carry no local artwork).
fn artwork(track: &Track) -> Option<Vec<u8>> {
    let path = env::temp_dir().join(format!("mado-artwork-{}", std::process::id()));
    let local = osascript(WRITE_ARTWORK, &[path.to_str()?])
        .filter(|r| r == "ok")
        .and_then(|_| fs::read(&path).ok())
        .filter(|bytes| !bytes.is_empty());
    let _ = fs::remove_file(&path);
    local.or_else(|| catalogue_artwork(track))
}

fn catalogue_artwork(track: &Track) -> Option<Vec<u8>> {
    let found: Value = ureq::get("https://itunes.apple.com/search")
        .query("term", &format!("{} {}", track.artist, track.album))
        .query("entity", "album")
        .query("limit", "5")
        .timeout(Duration::from_secs(8))
        .call()
        .ok()?
        .into_json()
        .ok()?;
    let results = found["results"].as_array()?;
    let album = track.album.to_lowercase();
    let best = results
        .iter()
        .find(|r| r["collectionName"].as_str().is_some_and(|n| n.to_lowercase() == album))
        .or(results.first())?;
    let url = best["artworkUrl100"].as_str()?.replace("100x100bb", "1000x1000bb");
    let mut bytes = Vec::new();
    ureq::get(&url)
        .timeout(Duration::from_secs(8))
        .call()
        .ok()?
        .into_reader()
        .take(MAX_ART_BYTES)
        .read_to_end(&mut bytes)
        .ok()?;
    Some(bytes)
}

/// Carry taps on the frame back to Music.
fn follow_commands(hub: String) {
    let mut after: Option<u64> = None;
    loop {
        let mut request = ureq::get(&format!("{hub}/api/now_playing/commands")).timeout(Duration::from_secs(40));
        if let Some(seq) = after {
            request = request.query("after", &seq.to_string());
        }
        match request.call().ok().and_then(|r| r.into_json::<Value>().ok()) {
            Some(reply) => {
                after = reply["seq"].as_u64();
                let verb = match reply["command"].as_str() {
                    Some("playpause") => "playpause",
                    Some("next") => "next track",
                    Some("previous") => "previous track",
                    _ => continue,
                };
                osascript(
                    &format!(r#"if application "Music" is running then tell application "Music" to {verb}"#),
                    &[],
                );
            }
            None => {
                // Hub unreachable. Start over from its current seq so an old tap is not replayed.
                after = None;
                thread::sleep(Duration::from_secs(3));
            }
        }
    }
}

struct Sent {
    state: &'static str,
    key: String,
    position_s: f64,
    at: Instant,
}

fn main() {
    let hub = env::args()
        .nth(1)
        .or_else(|| env::var("MADO_HUB").ok())
        .unwrap_or_else(|| "http://localhost:7878".into())
        .trim_end_matches('/')
        .to_owned();
    let source = Command::new("scutil")
        .args(["--get", "ComputerName"])
        .output()
        .ok()
        .map(|o| String::from_utf8_lossy(&o.stdout).trim().to_owned())
        .filter(|name| !name.is_empty())
        .unwrap_or_else(|| "Mac".into());
    println!("mado-nowplaying: reporting Music on {source} to {hub}");

    let commands_hub = hub.clone();
    thread::spawn(move || follow_commands(commands_hub));

    let mut art_key = String::new();
    let mut art: Option<String> = None;
    let mut send_art = false;
    let mut sent: Option<Sent> = None;
    let mut reachable = true;

    loop {
        let track = read_track();
        let state = match &track {
            Some(t) if t.playing => "playing",
            Some(_) => "paused",
            None => "stopped",
        };
        let key = track.as_ref().map(Track::key).unwrap_or_default();
        let position_s = track.as_ref().map_or(0.0, |t| t.position_s);

        if key != art_key {
            art = track
                .as_ref()
                .and_then(artwork)
                .map(|bytes| base64::engine::general_purpose::STANDARD.encode(bytes));
            send_art = art.is_some();
            art_key = key.clone();
        }

        let due = sent.as_ref().is_none_or(|s| {
            let expected = s.position_s
                + if s.state == "playing" {
                    s.at.elapsed().as_secs_f64()
                } else {
                    0.0
                };
            s.state != state || s.key != key || s.at.elapsed() >= HEARTBEAT || (position_s - expected).abs() > SCRUB_S
        });
        if due || send_art {
            let mut body = json!({ "state": state, "source": source, "position_s": position_s });
            if let Some(t) = &track {
                body["title"] = json!(t.title);
                body["artist"] = json!(t.artist);
                body["album"] = json!(t.album);
                body["duration_s"] = json!(t.duration_s);
            }
            if send_art {
                body["art_b64"] = json!(art);
            }
            let reply = ureq::post(&format!("{hub}/api/now_playing"))
                .timeout(Duration::from_secs(10))
                .send_json(body)
                .ok()
                .and_then(|r| r.into_json::<Value>().ok());
            match reply {
                Some(reply) => {
                    // A restarted hub has lost the cover; hand it over again next round. If it
                    // was in this very request, the hub could not read it and never will.
                    send_art = !send_art && art.is_some() && reply["have_art"] == false;
                    sent = Some(Sent {
                        state,
                        key,
                        position_s,
                        at: Instant::now(),
                    });
                    if !reachable {
                        println!("mado-nowplaying: hub is back");
                    }
                    reachable = true;
                }
                None => {
                    sent = None;
                    send_art = art.is_some();
                    if reachable {
                        eprintln!("mado-nowplaying: cannot reach {hub}; will keep trying");
                    }
                    reachable = false;
                }
            }
        }
        thread::sleep(POLL);
    }
}
