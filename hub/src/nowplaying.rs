//! Now playing: a helper on the machine with the music posts the track here; the hub prepares
//! the artwork for the frame's weak GPU and relays it. Taps on the frame travel back to the
//! helper as commands it long-polls for.

use std::{
    io::Cursor,
    time::{Duration, Instant},
};

use axum::{
    body::Bytes,
    extract::{Path, Query, State},
    http::{header, StatusCode},
    response::{IntoResponse, Response},
    Json,
};
use base64::Engine;
use image::{codecs::jpeg::JpegEncoder, imageops::FilterType, ImageFormat};
use serde::Deserialize;
use serde_json::{json, Value};

use crate::Shared;

const STATES: [&str; 3] = ["playing", "paused", "stopped"];
pub const COMMANDS: [&str; 3] = ["playpause", "next", "previous"];
/// A helper that goes quiet (Mac asleep, helper quit) must not leave the frame "playing".
const HELPER_SILENCE: Duration = Duration::from_secs(30);
const COMMAND_WAIT: Duration = Duration::from_secs(25);

pub struct Playing {
    pub state: String,
    pub title: String,
    pub artist: String,
    pub album: String,
    pub source: String,
    position_s: f64,
    duration_s: f64,
    at: Instant,
    art: Option<Art>,
}

struct Art {
    version: u64,
    track: String,
    cover: Bytes,
    backdrop: Bytes,
}

impl Default for Playing {
    fn default() -> Self {
        Playing {
            state: "stopped".into(),
            title: String::new(),
            artist: String::new(),
            album: String::new(),
            source: String::new(),
            position_s: 0.0,
            duration_s: 0.0,
            at: Instant::now(),
            art: None,
        }
    }
}

impl Playing {
    fn track(&self) -> String {
        format!("{}\u{1f}{}\u{1f}{}", self.title, self.artist, self.album)
    }

    /// The message for a frame, with the position advanced to this instant.
    pub fn message(&self) -> Value {
        let mut position = self.position_s;
        if self.state == "playing" {
            position += self.at.elapsed().as_secs_f64();
        }
        if self.duration_s > 0.0 {
            position = position.min(self.duration_s);
        }
        json!({
            "t": "now_playing", "state": self.state, "title": self.title, "artist": self.artist,
            "album": self.album, "source": self.source, "position_s": position,
            "duration_s": self.duration_s, "art": self.art.as_ref().map(|a| a.version),
        })
    }
}

#[derive(Deserialize)]
pub struct Input {
    state: String,
    #[serde(default)]
    title: String,
    #[serde(default)]
    artist: String,
    #[serde(default)]
    album: String,
    #[serde(default)]
    source: String,
    #[serde(default)]
    position_s: f64,
    #[serde(default)]
    duration_s: f64,
    /// Original artwork bytes (JPEG or PNG). Send it when the track changes or when the
    /// previous reply said `have_art: false`.
    art_b64: Option<String>,
}

pub async fn post(State(hub): State<Shared>, Json(input): Json<Input>) -> Response {
    if !STATES.contains(&input.state.as_str()) {
        let error = format!("state must be one of {}", STATES.join(", "));
        return (StatusCode::BAD_REQUEST, Json(json!({ "error": error }))).into_response();
    }
    let prepared = match input.art_b64.as_deref().map(|b| base64::engine::general_purpose::STANDARD.decode(b)) {
        Some(Ok(bytes)) => tokio::task::spawn_blocking(move || prepare_art(&bytes)).await.ok().flatten(),
        _ => None,
    };

    let (msg, have_art, new_track) = {
        let mut p = hub.now_playing.lock().unwrap();
        let before = p.track();
        p.state = input.state;
        p.title = input.title;
        p.artist = input.artist;
        p.album = input.album;
        p.source = input.source;
        p.position_s = input.position_s;
        p.duration_s = input.duration_s;
        p.at = Instant::now();
        let track = p.track();
        if let Some((cover, backdrop)) = prepared {
            p.art = Some(Art { version: hub.id(), track: track.clone(), cover, backdrop });
        } else if p.art.as_ref().is_some_and(|a| a.track != track) {
            p.art = None;
        }
        (p.message(), p.art.is_some(), track != before && !p.title.is_empty())
    };
    if new_track {
        hub.log(format!("now playing: {} · {}", msg["title"].as_str().unwrap_or(""), msg["artist"].as_str().unwrap_or("")));
    }
    hub.send_all(&msg);
    Json(json!({ "have_art": have_art })).into_response()
}

/// Cover: a square the frame draws as is. Backdrop: tiny and pre-blurred, so the frame only
/// has to stretch it.
fn prepare_art(bytes: &[u8]) -> Option<(Bytes, Bytes)> {
    let img = image::load_from_memory(bytes).ok()?;
    let mut cover = Vec::new();
    let square = img.resize_to_fill(800, 800, FilterType::CatmullRom).to_rgb8();
    JpegEncoder::new_with_quality(&mut cover, 88).encode_image(&square).ok()?;
    let mut backdrop = Vec::new();
    img.resize_to_fill(160, 100, FilterType::Triangle)
        .blur(7.0)
        .to_rgb8()
        .write_to(&mut Cursor::new(&mut backdrop), ImageFormat::Png)
        .ok()?;
    Some((cover.into(), backdrop.into()))
}

pub async fn art(State(hub): State<Shared>, Path((version, file)): Path<(u64, String)>) -> Response {
    let p = hub.now_playing.lock().unwrap();
    let found = p.art.as_ref().filter(|a| a.version == version).and_then(|a| match file.as_str() {
        "cover.jpg" => Some(("image/jpeg", a.cover.clone())),
        "backdrop.png" => Some(("image/png", a.backdrop.clone())),
        _ => None,
    });
    match found {
        Some((mime, bytes)) => ([(header::CONTENT_TYPE, mime), (header::CACHE_CONTROL, "max-age=31536000, immutable")], bytes).into_response(),
        None => StatusCode::NOT_FOUND.into_response(),
    }
}

#[derive(Deserialize)]
pub struct After {
    after: Option<u64>,
}

/// Long poll for the helper. Call once without `after` to learn the current `seq`, then keep
/// calling with the last `seq` seen; a reply with `command` is something to do.
pub async fn commands(State(hub): State<Shared>, Query(After { after }): Query<After>) -> Json<Value> {
    let mut rx = hub.commands.subscribe();
    let (seq, command) = rx.borrow_and_update().clone();
    match after {
        None => return Json(json!({ "seq": seq })),
        Some(after) if seq > after => return Json(json!({ "seq": seq, "command": command })),
        _ => {}
    }
    match tokio::time::timeout(COMMAND_WAIT, rx.changed()).await {
        Ok(Ok(())) => {
            let (seq, command) = rx.borrow().clone();
            Json(json!({ "seq": seq, "command": command }))
        }
        _ => Json(json!({ "seq": seq })),
    }
}

pub async fn expire_silent_helper(hub: Shared) {
    let mut tick = tokio::time::interval(Duration::from_secs(10));
    loop {
        tick.tick().await;
        let msg = {
            let mut p = hub.now_playing.lock().unwrap();
            if p.state != "playing" || p.at.elapsed() < HELPER_SILENCE {
                continue;
            }
            p.state = "stopped".into();
            p.message()
        };
        hub.log("now playing: helper went quiet".into());
        hub.send_all(&msg);
    }
}
