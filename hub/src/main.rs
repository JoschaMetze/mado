//! mado-hub: the brain for a Mado frame.
//!
//! The frame dials `/frame` (WebSocket) and stays connected. Senders talk to the hub over
//! plain HTTP (`/api/*`), MCP (`/mcp`) or the web interface (`/`); the hub relays to the frame.

mod art;
mod calendar;
mod discover;
mod mcp;
mod nowplaying;
mod projects;
mod ui;

use std::{
    collections::{BTreeMap, HashMap, VecDeque},
    path::PathBuf,
    sync::{
        atomic::{AtomicBool, AtomicU64, Ordering},
        Arc, Mutex, RwLock,
    },
    time::{Duration, Instant},
};

use axum::{
    extract::{
        ws::{Message, WebSocket, WebSocketUpgrade},
        DefaultBodyLimit, State,
    },
    http::{header, StatusCode},
    response::{IntoResponse, Response},
    routing::{get, post},
    Json, Router,
};
use base64::Engine;
use futures_util::{SinkExt, StreamExt};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use tokio::sync::{mpsc, oneshot, watch};

pub type Shared = Arc<Hub>;

const LEVELS: [&str; 3] = ["whisper", "nudge", "urgent"];
const LOG_LINES: usize = 40;
const LOOK_TIMEOUT: Duration = Duration::from_secs(8);
/// The frame pings every 20 s; three missed pings means it is gone.
const FRAME_SILENCE: Duration = Duration::from_secs(65);

#[derive(Clone, Default, Serialize, Deserialize)]
pub struct Config {
    #[serde(default)]
    pub places: Vec<Place>,
    /// How the hub's own page looks; the frame ignores it. Empty means the default.
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub theme: String,
    /// Which paintings the art of the day is chosen from.
    #[serde(default, skip_serializing_if = "art::Prefs::is_any")]
    pub art: art::Prefs,
    /// When the frame sleeps. The frame keeps these hours itself, hub or no hub.
    #[serde(default)]
    pub night: Night,
    /// The owner's Outlook calendar, shown as an agenda.
    #[serde(default)]
    pub calendar: calendar::Settings,
}

/// The hours the frame shows only its night clock, as "HH:MM" on the frame's own clock.
#[derive(Clone, PartialEq, Serialize, Deserialize)]
pub struct Night {
    pub from: String,
    pub to: String,
    /// Set to keep the frame awake all night; it can still be told to sleep.
    #[serde(default)]
    pub off: bool,
}

impl Default for Night {
    fn default() -> Self {
        Night {
            from: "23:00".into(),
            to: "07:00".into(),
            off: false,
        }
    }
}

impl Night {
    /// A time of day as the frame reads it: two digits, a colon, two digits.
    pub fn is_time(s: &str) -> bool {
        let digits = |part: &str, most: u32| part.len() == 2 && part.parse::<u32>().is_ok_and(|n| n <= most);
        matches!(s.split_once(':'), Some((h, m)) if digits(h, 23) && digits(m, 59))
    }
}

/// A place the frame shows weather for. The frame fetches the forecast itself.
#[derive(Clone, Serialize, Deserialize)]
pub struct Place {
    pub name: String,
    pub lat: f64,
    pub lon: f64,
    /// IANA name, e.g. "Asia/Tokyo"; known when the place came from the city search.
    #[serde(default)]
    pub timezone: Option<String>,
}

#[derive(Deserialize)]
pub struct Notify {
    pub text: String,
    pub level: Option<String>,
    pub source: Option<String>,
    pub ttl_s: Option<u64>,
}

/// Something asked of Tama, the frame's companion.
#[derive(Deserialize)]
pub struct Companion {
    pub action: String,
    pub ttl_s: Option<u64>,
    pub source: Option<String>,
}

/// What Tama can be asked for: hobbies, gestures, moods that last a while, "calm" to drop a mood,
/// and care: something to eat, or medicine when it is poorly.
pub const COMPANION_ACTIONS: [&str; 15] = [
    "wave",
    "nod",
    "celebrate",
    "happy",
    "worried",
    "calm",
    "hide",
    "fog",
    "pebbles",
    "mote",
    "nap",
    "peek",
    "perch",
    "feed",
    "medicine",
];

pub struct FrameConn {
    tx: mpsc::UnboundedSender<String>,
    pub hello: Value,
    pub since: Instant,
}

pub struct LogEntry {
    pub at: Instant,
    pub line: String,
}

pub struct Hub {
    pub frames: Mutex<BTreeMap<u64, FrameConn>>,
    pub config: RwLock<Config>,
    pub log: Mutex<VecDeque<LogEntry>>,
    pub now_playing: Mutex<nowplaying::Playing>,
    /// Latest media command for the now-playing helper, numbered so none is missed or repeated.
    pub commands: watch::Sender<(u64, String)>,
    pub art: Mutex<Option<Arc<art::Daily>>>,
    pub art_busy: AtomicBool,
    pub projects: Mutex<Vec<projects::Project>>,
    /// What Tama was last seen doing, as the frame reported it.
    pub tama: Mutex<Option<String>>,
    /// How Tama's quest is going, in a sentence, as the frame reported it.
    pub quest: Mutex<Option<String>>,
    /// Whether the frame said it is in night mode; None until one has said.
    pub asleep: Mutex<Option<bool>>,
    pub calendar: Mutex<calendar::Calendar>,
    pub data_dir: PathBuf,
    looks: Mutex<HashMap<u64, oneshot::Sender<String>>>,
    next_id: AtomicU64,
    config_path: PathBuf,
}

impl Hub {
    fn load(data_dir: PathBuf) -> Self {
        let config_path = data_dir.join("config.json");
        let config = std::fs::read_to_string(&config_path)
            .ok()
            .and_then(|s| serde_json::from_str(&s).ok())
            .unwrap_or_default();
        let projects = std::fs::read(data_dir.join("projects.json"))
            .ok()
            .and_then(|bytes| serde_json::from_slice(&bytes).ok())
            .unwrap_or_default();
        let calendar = calendar::load(&data_dir);
        Hub {
            frames: Mutex::default(),
            config: RwLock::new(config),
            log: Mutex::default(),
            now_playing: Mutex::default(),
            commands: watch::channel((0, String::new())).0,
            art: Mutex::default(),
            art_busy: AtomicBool::new(false),
            projects: Mutex::new(projects),
            tama: Mutex::default(),
            quest: Mutex::default(),
            asleep: Mutex::default(),
            calendar: Mutex::new(calendar),
            data_dir,
            looks: Mutex::default(),
            next_id: AtomicU64::new(1),
            config_path,
        }
    }

    pub fn id(&self) -> u64 {
        self.next_id.fetch_add(1, Ordering::Relaxed)
    }

    pub fn log(&self, line: String) {
        println!("{line}");
        let mut log = self.log.lock().unwrap();
        if log.len() == LOG_LINES {
            log.pop_back();
        }
        log.push_front(LogEntry {
            at: Instant::now(),
            line,
        });
    }

    /// Persist the config and push it to every connected frame.
    pub fn config_changed(&self) -> std::io::Result<()> {
        let config = self.config.read().unwrap().clone();
        if let Some(dir) = self.config_path.parent() {
            std::fs::create_dir_all(dir)?;
        }
        std::fs::write(&self.config_path, serde_json::to_vec_pretty(&config).unwrap())?;
        self.send_all(&json!({ "t": "config", "config": config }));
        Ok(())
    }

    /// Returns how many frames the message was queued for.
    pub fn send_all(&self, msg: &Value) -> usize {
        let text = msg.to_string();
        let frames = self.frames.lock().unwrap();
        frames.values().filter(|f| f.tx.send(text.clone()).is_ok()).count()
    }

    pub fn notify(&self, n: Notify) -> Result<Value, String> {
        let text = n.text.trim();
        if text.is_empty() {
            return Err("text is empty".into());
        }
        if text.chars().count() > 280 {
            return Err("text is longer than 280 characters".into());
        }
        let level = n.level.as_deref().unwrap_or("nudge");
        if !LEVELS.contains(&level) {
            return Err(format!("level must be one of {}", LEVELS.join(", ")));
        }
        // Urgent stays until someone taps it on the frame, whatever the sender asked for.
        let ttl_s = match level {
            "urgent" => 0,
            "whisper" => n.ttl_s.unwrap_or(60),
            _ => n.ttl_s.unwrap_or(20),
        };
        let source = n.source.as_deref().map(str::trim).filter(|s| !s.is_empty());
        let id = self.id();
        let frames = self.send_all(&json!({
            "t": "notify", "id": id, "level": level, "text": text, "source": source, "ttl_s": ttl_s,
        }));
        self.log(format!("{level} from {}: {text}", source.unwrap_or("someone")));
        if frames == 0 {
            return Err("no frame is connected, so nothing was shown".into());
        }
        Ok(json!({ "id": id, "level": level, "shown_on_frames": frames }))
    }

    /// Tama acts on it in its own way, and not at all if the owner has hidden it.
    pub fn companion(&self, c: Companion) -> Result<Value, String> {
        let action = c.action.trim();
        if !COMPANION_ACTIONS.contains(&action) {
            return Err(format!("action must be one of {}", COMPANION_ACTIONS.join(", ")));
        }
        let source = c.source.as_deref().map(str::trim).filter(|s| !s.is_empty());
        let frames = self.send_all(&json!({ "t": "companion", "action": action, "ttl_s": c.ttl_s.unwrap_or(0) }));
        self.log(format!("{} asked Tama to {action}", source.unwrap_or("someone")));
        if frames == 0 {
            return Err("no frame is connected, so Tama did not hear".into());
        }
        Ok(json!({ "action": action, "sent_to_frames": frames, "tama_was": *self.tama.lock().unwrap() }))
    }

    /// Night mode from now, or day from now. It holds until the frame's own hours next change over.
    pub fn sleep(&self, asleep: bool, source: Option<&str>) -> Result<Value, String> {
        let frames = self.send_all(&json!({ "t": "night", "asleep": asleep }));
        let source = source.map(str::trim).filter(|s| !s.is_empty()).unwrap_or("someone");
        self.log(format!(
            "{source} {}",
            if asleep {
                "put the frame to sleep"
            } else {
                "woke the frame"
            }
        ));
        if frames == 0 {
            return Err("no frame is connected, so nothing changed".into());
        }
        Ok(json!({ "asleep": asleep, "sent_to_frames": frames }))
    }

    /// Ask the first connected frame for a JPEG of what is on its screen.
    pub async fn look(&self) -> Result<Vec<u8>, String> {
        let req = self.id();
        let (tx, rx) = oneshot::channel();
        {
            let frames = self.frames.lock().unwrap();
            let frame = frames.values().next().ok_or("no frame is connected")?;
            self.looks.lock().unwrap().insert(req, tx);
            let _ = frame.tx.send(json!({ "t": "look", "req": req }).to_string());
        }
        let reply = tokio::time::timeout(LOOK_TIMEOUT, rx).await;
        self.looks.lock().unwrap().remove(&req);
        let b64 = reply
            .map_err(|_| "the frame did not answer in time")?
            .map_err(|_| "the frame disconnected")?;
        base64::engine::general_purpose::STANDARD
            .decode(b64)
            .map_err(|_| "the frame sent an unreadable image".into())
    }

    pub fn state(&self) -> Value {
        let frames: Vec<Value> = self
            .frames
            .lock()
            .unwrap()
            .iter()
            .map(|(id, f)| json!({ "id": id, "connected_s": f.since.elapsed().as_secs(), "info": f.hello }))
            .collect();
        let recent: Vec<Value> = self
            .log
            .lock()
            .unwrap()
            .iter()
            .take(10)
            .map(|e| json!({ "ago_s": e.at.elapsed().as_secs(), "line": e.line }))
            .collect();
        let now_playing = self.now_playing.lock().unwrap().message();
        let art = self.art.lock().unwrap().as_ref().map(|a| a.message());
        let projects = self.projects_message()["items"].take();
        let tama = self.tama.lock().unwrap().clone();
        let quest = self.quest.lock().unwrap().clone();
        let asleep = *self.asleep.lock().unwrap();
        json!({
            "frames": frames, "tama": tama, "quest": quest, "asleep": asleep, "now_playing": now_playing, "art": art, "projects": projects,
            "config": *self.config.read().unwrap(), "recent": recent, "calendar": calendar::status(self),
        })
    }

    fn on_frame_msg(&self, id: u64, msg: Value) {
        match msg["t"].as_str() {
            Some("hello") => {
                self.log(format!("frame #{id} is {}", msg["model"].as_str().unwrap_or("unknown")));
                if let Some(f) = self.frames.lock().unwrap().get_mut(&id) {
                    f.hello = msg;
                }
            }
            Some("look") => {
                let pending = msg["req"].as_u64().and_then(|r| self.looks.lock().unwrap().remove(&r));
                if let (Some(tx), Some(jpeg)) = (pending, msg["jpeg"].as_str()) {
                    let _ = tx.send(jpeg.to_owned());
                }
            }
            Some("event") if msg["kind"] == "media" => {
                let action = msg["action"].as_str().unwrap_or("");
                if nowplaying::COMMANDS.contains(&action) {
                    self.commands.send_modify(|c| *c = (c.0 + 1, action.to_owned()));
                }
            }
            Some("event") if msg["kind"] == "tama" => {
                *self.tama.lock().unwrap() = msg["doing"].as_str().map(str::to_owned);
                if let Some(quest) = msg["quest"].as_str() {
                    *self.quest.lock().unwrap() = Some(quest.to_owned());
                }
            }
            Some("event") if msg["kind"] == "night" => {
                *self.asleep.lock().unwrap() = msg["asleep"].as_bool();
            }
            Some("event") => self.log(format!(
                "frame #{id}: {} {}",
                msg["kind"].as_str().unwrap_or("event"),
                msg["id"]
            )),
            _ => {}
        }
    }
}

async fn frame_ws(ws: WebSocketUpgrade, State(hub): State<Shared>) -> Response {
    ws.max_message_size(8 << 20)
        .on_upgrade(move |socket| frame_session(socket, hub))
}

async fn frame_session(socket: WebSocket, hub: Shared) {
    let id = hub.id();
    let (mut sink, mut stream) = socket.split();
    let (tx, mut rx) = mpsc::unbounded_channel::<String>();
    // Says what this hub understands, so a newer frame does not send an older hub things it would only log.
    let _ =
        tx.send(json!({ "t": "hub", "version": env!("CARGO_PKG_VERSION"), "tama": true, "night": true }).to_string());
    let _ = tx.send(json!({ "t": "config", "config": *hub.config.read().unwrap() }).to_string());
    let _ = tx.send(hub.now_playing.lock().unwrap().message().to_string());
    let _ = tx.send(hub.projects_message().to_string());
    let _ = tx.send(calendar::hello(&hub));
    hub.frames.lock().unwrap().insert(
        id,
        FrameConn {
            tx,
            hello: Value::Null,
            since: Instant::now(),
        },
    );
    hub.log(format!("frame #{id} connected"));

    let writer = tokio::spawn(async move {
        while let Some(text) = rx.recv().await {
            if sink.send(Message::Text(text.into())).await.is_err() {
                break;
            }
        }
    });
    while let Ok(Some(Ok(msg))) = tokio::time::timeout(FRAME_SILENCE, stream.next()).await {
        match msg {
            Message::Text(text) => {
                match serde_json::from_str::<Value>(text.as_str()) {
                    // The frame names the day it wants a painting for; `another` asks for a different one.
                    Ok(value) if value["t"] == "want_art" => {
                        let day = value["day"].as_str().unwrap_or("").to_owned();
                        art::want(hub.clone(), day, value["another"] == true);
                    }
                    Ok(value) => hub.on_frame_msg(id, value),
                    Err(_) => {}
                }
            }
            Message::Close(_) => break,
            _ => {}
        }
    }
    writer.abort();
    let mut frames = hub.frames.lock().unwrap();
    frames.remove(&id);
    if frames.is_empty() {
        *hub.tama.lock().unwrap() = None;
        *hub.quest.lock().unwrap() = None;
        *hub.asleep.lock().unwrap() = None;
    }
    drop(frames);
    hub.log(format!("frame #{id} disconnected"));
}

async fn api_notify(State(hub): State<Shared>, Json(n): Json<Notify>) -> Response {
    match hub.notify(n) {
        Ok(v) => Json(v).into_response(),
        Err(e) => (StatusCode::BAD_REQUEST, Json(json!({ "error": e }))).into_response(),
    }
}

async fn api_companion(State(hub): State<Shared>, Json(c): Json<Companion>) -> Response {
    match hub.companion(c) {
        Ok(v) => Json(v).into_response(),
        Err(e) => (StatusCode::BAD_REQUEST, Json(json!({ "error": e }))).into_response(),
    }
}

/// Who is asking, for the log. The body may be left out altogether.
#[derive(Default, Deserialize)]
pub struct Asker {
    pub source: Option<String>,
}

fn slept(outcome: Result<Value, String>) -> Response {
    match outcome {
        Ok(v) => Json(v).into_response(),
        Err(e) => (StatusCode::BAD_REQUEST, Json(json!({ "error": e }))).into_response(),
    }
}

async fn api_sleep(State(hub): State<Shared>, asker: Option<Json<Asker>>) -> Response {
    slept(hub.sleep(true, asker.unwrap_or_default().source.as_deref()))
}

async fn api_wake(State(hub): State<Shared>, asker: Option<Json<Asker>>) -> Response {
    slept(hub.sleep(false, asker.unwrap_or_default().source.as_deref()))
}

async fn api_state(State(hub): State<Shared>) -> Json<Value> {
    Json(hub.state())
}

async fn api_look(State(hub): State<Shared>) -> Response {
    match hub.look().await {
        Ok(jpeg) => (
            [
                (header::CONTENT_TYPE, "image/jpeg"),
                (header::CACHE_CONTROL, "no-store"),
            ],
            jpeg,
        )
            .into_response(),
        Err(e) => (StatusCode::SERVICE_UNAVAILABLE, e).into_response(),
    }
}

#[tokio::main]
async fn main() {
    let data_dir = std::env::var("MADO_DATA").unwrap_or_else(|_| "data".into());
    let bind = std::env::var("MADO_BIND").unwrap_or_else(|_| "0.0.0.0:7878".into());
    let hub: Shared = Arc::new(Hub::load(data_dir.into()));

    // What a frame needs and nothing else: its socket and the images it fetches.
    let frame_routes = Router::new()
        .route("/frame", get(frame_ws))
        .route("/art/{version}/{file}", get(nowplaying::art))
        .route("/daily/{version}/{file}", get(art::file))
        .route("/icons/{file}", get(projects::icon_file));

    let app = Router::new()
        .route("/", get(ui::index))
        .route("/icon.svg", get(ui::icon))
        .route("/ui/glass", get(ui::glass))
        .route("/ui/notify", post(ui::notify))
        .route("/ui/places/add", post(ui::place_add))
        .route("/ui/places/remove", post(ui::place_remove))
        .route("/ui/places/rename", post(ui::place_rename))
        .route("/ui/places/here", post(ui::place_here))
        .route("/ui/projects", post(ui::project).layer(DefaultBodyLimit::max(6 << 20)))
        .route("/ui/art/another", post(ui::art_another))
        .route("/ui/art/prefs", post(ui::art_prefs))
        .route("/ui/theme", post(ui::theme))
        .route("/ui/night", post(ui::night))
        .route("/ui/sleep", post(ui::sleep))
        .route("/ui/calendar", post(ui::calendar_settings))
        .route("/ui/calendar/signin", post(ui::calendar_signin))
        .route("/ui/calendar/signout", post(ui::calendar_signout))
        .route("/api/agenda", get(calendar::api_agenda))
        .route("/api/calendar/refresh", post(calendar::api_refresh))
        .route("/api/notify", post(api_notify))
        .route("/api/companion", post(api_companion))
        .route("/api/sleep", post(api_sleep))
        .route("/api/wake", post(api_wake))
        .route("/api/state", get(api_state))
        .route("/api/look", get(api_look))
        .route(
            "/api/now_playing",
            post(nowplaying::post).layer(DefaultBodyLimit::max(12 << 20)),
        )
        .route("/api/now_playing/commands", get(nowplaying::commands))
        .route(
            "/api/project",
            post(projects::post).layer(DefaultBodyLimit::max(6 << 20)),
        )
        .route("/mcp", post(mcp::handle))
        .merge(frame_routes.clone())
        .with_state(hub.clone());
    tokio::spawn(nowplaying::expire_silent_helper(hub.clone()));
    tokio::spawn(calendar::run(hub.clone()));

    // MADO_FRAME_BIND opens a second port that serves only the frame's routes. It lets the
    // frame reach the hub on a network where the API, MCP and web page must not be exposed.
    let frame_bind = std::env::var("MADO_FRAME_BIND").ok();
    if let Some(frame_bind) = &frame_bind {
        let listener = listen(frame_bind).await;
        println!("mado-hub listening for frames only on {frame_bind}");
        let frame_app = frame_routes.with_state(hub);
        tokio::spawn(async move { axum::serve(listener, frame_app).await });
    }

    let listener = listen(&bind).await;
    println!("mado-hub listening on http://{bind}");
    // Frames find the hub by its announcement. MADO_ADVERTISE=off keeps it quiet, for a hub
    // behind a container's own network, where the announcement would not reach anyone.
    let announcement = if std::env::var("MADO_ADVERTISE").is_ok_and(|v| v == "off" || v == "0") {
        None
    } else {
        let port = discover::port_of(frame_bind.as_deref().unwrap_or(&bind));
        let announced = port
            .ok_or_else(|| "no port in the bind address".to_string())
            .and_then(|port| discover::announce(port, std::env::var("MADO_NAME").ok()));
        // A hub that cannot announce itself still works for a frame given its address.
        announced
            .map_err(|e| eprintln!("not announced on the local network: {e}"))
            .ok()
    };
    // Frames hold their socket open for good, so waiting for connections to drain would
    // only mean waiting to be killed. Nothing here needs a slow goodbye.
    tokio::spawn(async move {
        stop_signal().await;
        // Say goodbye, so frames looking for a hub stop being offered this one.
        if let Some(Ok(done)) = announcement.map(|a| a.shutdown()) {
            let _ = done.recv_timeout(std::time::Duration::from_secs(1));
        }
        std::process::exit(0);
    });
    axum::serve(listener, app).await.unwrap();
}

async fn listen(bind: &str) -> tokio::net::TcpListener {
    tokio::net::TcpListener::bind(bind).await.unwrap_or_else(|e| {
        eprintln!("cannot listen on {bind}: {e}");
        std::process::exit(1);
    })
}

/// Ctrl-C, or the SIGTERM a container runtime sends on stop.
async fn stop_signal() {
    #[cfg(unix)]
    {
        let mut term = tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate()).unwrap();
        tokio::select! {
            _ = tokio::signal::ctrl_c() => {}
            _ = term.recv() => {}
        }
    }
    #[cfg(not(unix))]
    let _ = tokio::signal::ctrl_c().await;
}

#[cfg(test)]
mod tests {
    use super::*;

    fn hub() -> Hub {
        Hub::load(std::env::temp_dir().join(format!("mado-test-{}", std::process::id())))
    }

    fn alert(text: &str, level: Option<&str>) -> Notify {
        Notify {
            text: text.into(),
            level: level.map(str::to_owned),
            source: None,
            ttl_s: None,
        }
    }

    #[test]
    fn alerts_are_validated_before_anything_is_sent() {
        let hub = hub();
        assert!(hub.notify(alert("  ", None)).unwrap_err().contains("empty"));
        assert!(hub.notify(alert(&"x".repeat(281), None)).unwrap_err().contains("280"));
        assert!(hub.notify(alert("hi", Some("shout"))).unwrap_err().contains("level"));
    }

    #[test]
    fn tama_is_only_asked_for_things_it_knows() {
        let hub = hub();
        let ask = |action: &str| Companion {
            action: action.into(),
            ttl_s: None,
            source: None,
        };
        assert!(hub.companion(ask("dance")).unwrap_err().contains("action must be"));
        assert!(hub.companion(ask("wave")).unwrap_err().contains("no frame"));
        assert_eq!(hub.state()["tama"], Value::Null);
    }

    #[test]
    fn night_hours_are_times_of_day() {
        assert!(Night::is_time("23:00") && Night::is_time("00:59"));
        for bad in ["24:00", "7:00", "07:60", "0700", "", "ab:cd"] {
            assert!(!Night::is_time(bad), "{bad}");
        }
        // A config saved before night mode gets the usual hours.
        let old: Config = serde_json::from_str(r#"{"places":[]}"#).unwrap();
        assert!(old.night == Night::default());
        assert!(hub().sleep(true, None).unwrap_err().contains("no frame"));
        assert_eq!(hub().state()["asleep"], Value::Null);
    }

    #[test]
    fn a_valid_alert_with_no_frame_says_so() {
        assert!(hub()
            .notify(alert("hi", Some("urgent")))
            .unwrap_err()
            .contains("no frame"));
    }

    #[test]
    fn projects_are_listed_with_what_needs_the_owner_first() {
        let hub = hub();
        let update = |name: &str, status: &str| projects::Update {
            name: name.into(),
            status: status.into(),
            note: String::new(),
            progress: Some(7.0),
            icon: None,
        };
        let report = |name: &str, status: &str| hub.update_project(update(name, status), projects::Icon::Keep);
        report("b", "on_track").unwrap();
        report("a", "done").unwrap();
        report("c", "needs_you").unwrap();
        let items = hub.projects_message()["items"].take();
        let names: Vec<&str> = items
            .as_array()
            .unwrap()
            .iter()
            .map(|p| p["name"].as_str().unwrap())
            .collect();
        assert_eq!(names, ["c", "b", "a"]);
        assert_eq!(items[0]["progress"], 1.0);

        report("C", "archived").unwrap();
        assert_eq!(hub.projects_message()["items"].as_array().unwrap().len(), 2);
        assert!(report("b", "paused").is_err());
        let _ = std::fs::remove_dir_all(&hub.data_dir);
    }

    #[test]
    fn a_project_keeps_its_icon_until_told_otherwise() {
        let hub = Hub::load(std::env::temp_dir().join(format!("mado-test-icons-{}", std::process::id())));
        let report = |icon| {
            let update = projects::Update {
                name: "Mado".into(),
                status: "on_track".into(),
                note: String::new(),
                progress: None,
                icon: None,
            };
            hub.update_project(update, icon).unwrap();
            hub.projects_message()["items"][0].take()
        };
        let first = report(projects::Icon::Set(b"one".to_vec()));
        let key = first["icon"].as_str().unwrap().to_owned();
        let file = |key: &str| hub.data_dir.join("icons").join(format!("{key}.png"));
        assert!(file(&key).exists());
        assert_eq!(first["initials"], "Ma");

        assert_eq!(report(projects::Icon::Keep)["icon"], key.as_str());
        let second = report(projects::Icon::Set(b"two".to_vec()));
        assert_ne!(second["icon"], key.as_str());
        assert!(!file(&key).exists());
        assert!(report(projects::Icon::Clear)["icon"].is_null());
        assert!(!file(second["icon"].as_str().unwrap()).exists());
        let _ = std::fs::remove_dir_all(&hub.data_dir);
    }
}
