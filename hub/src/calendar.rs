//! The owner's Outlook calendar, read from Microsoft Graph and shown on the frame as an agenda.
//!
//! Sign-in is the device code flow: the hub page shows a code, the owner enters it at
//! Microsoft's device-login page once, and the hub keeps the refresh token in
//! `MADO_DATA/calendar.json`. From then on it polls `calendarView` (which expands recurring
//! series) every few minutes and pushes `{ "t": "agenda", ... }` to every frame whenever the
//! list changes. Only times, titles and the kind of place reach the frame: never attendees
//! or bodies. A switch turns titles into plain "Busy" blocks for guests in the room.

use std::{
    collections::HashSet,
    time::{Duration, SystemTime, UNIX_EPOCH},
};

use axum::{extract::State, response::IntoResponse, Json};
use chrono::{Datelike, NaiveDateTime, TimeZone, Utc};
use chrono_tz::Tz;
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};

use crate::{Hub, Notify, Shared};

const SCOPES: &str = "offline_access User.Read Calendars.Read";
const GRAPH: &str = "https://graph.microsoft.com/v1.0";
/// How often the calendar is read while signed in.
const POLL: Duration = Duration::from_secs(180);
/// How often reminders are checked and the agenda is re-pushed after a change.
const TICK: Duration = Duration::from_secs(20);

/// What the owner sets on the hub page. Saved in the hub's config.
#[derive(Clone, Serialize, Deserialize)]
pub struct Settings {
    /// Entra tenant: an id, a domain, or "common"/"organizations" for multi-tenant apps.
    #[serde(default)]
    pub tenant: String,
    /// The app registration's application (client) id. Empty means the calendar is off.
    #[serde(default)]
    pub client_id: String,
    /// IANA zone the frame lives in; event times are wall-clock in it.
    #[serde(default = "default_tz")]
    pub timezone: String,
    /// How many days ahead to show, today included.
    #[serde(default = "default_days")]
    pub days: u32,
    /// Show meeting titles. Off, every meeting is a "Busy" block.
    #[serde(default = "yes")]
    pub titles: bool,
    /// Minutes before a meeting to nudge the frame; 0 is off.
    #[serde(default = "default_remind")]
    pub remind_min: u32,
    /// Keep meetings the owner declined.
    #[serde(default)]
    pub show_declined: bool,
}

fn default_tz() -> String {
    "Europe/Berlin".into()
}
fn default_days() -> u32 {
    2
}
fn default_remind() -> u32 {
    5
}
fn yes() -> bool {
    true
}

impl Default for Settings {
    fn default() -> Self {
        Settings {
            tenant: String::new(),
            client_id: String::new(),
            timezone: default_tz(),
            days: default_days(),
            titles: true,
            remind_min: default_remind(),
            show_declined: false,
        }
    }
}

impl Settings {
    pub fn configured(&self) -> bool {
        !self.client_id.trim().is_empty() && !self.tenant.trim().is_empty()
    }

    pub fn tz(&self) -> Tz {
        self.timezone.parse().unwrap_or(chrono_tz::Europe::Berlin)
    }

    fn authority(&self) -> String {
        format!("https://login.microsoftonline.com/{}/oauth2/v2.0", self.tenant.trim())
    }
}

/// What the hub keeps on disk after a sign-in. The refresh token is the secret.
#[derive(Clone, Default, Serialize, Deserialize)]
pub struct Tokens {
    pub access_token: String,
    pub refresh_token: String,
    /// Unix seconds when the access token stops working.
    pub expires_at: u64,
    /// Who signed in, for the page.
    #[serde(default)]
    pub account: String,
}

/// A device-code sign-in that is waiting for the owner.
#[derive(Clone)]
pub struct Pending {
    pub user_code: String,
    pub verification_uri: String,
    pub message: String,
    device_code: String,
    interval: u64,
    expires_at: SystemTime,
}

/// One meeting as the frame sees it. Times are wall-clock in the configured zone, "YYYY-MM-DDTHH:MM:SS".
#[derive(Clone, PartialEq, Serialize)]
pub struct Event {
    pub id: String,
    pub title: String,
    pub start: String,
    pub end: String,
    pub all_day: bool,
    /// busy, free, tentative, oof, workingElsewhere
    pub busy: String,
    pub online: bool,
    /// "Teams", "Zoom", "Meet", a room name, or empty.
    pub location: String,
    /// accepted, tentative, declined, none, organizer
    pub response: String,
    pub mine: bool,
}

#[derive(Default)]
pub struct Calendar {
    pub tokens: Option<Tokens>,
    pub pending: Option<Pending>,
    pub events: Vec<Event>,
    pub updated: Option<SystemTime>,
    pub error: Option<String>,
    reminded: HashSet<String>,
}

pub fn load(data_dir: &std::path::Path) -> Calendar {
    let tokens = std::fs::read(data_dir.join("calendar.json"))
        .ok()
        .and_then(|b| serde_json::from_slice(&b).ok());
    Calendar {
        tokens,
        ..Default::default()
    }
}

fn save_tokens(hub: &Hub, tokens: Option<&Tokens>) -> std::io::Result<()> {
    let path = hub.data_dir.join("calendar.json");
    match tokens {
        Some(t) => {
            std::fs::create_dir_all(&hub.data_dir)?;
            std::fs::write(&path, serde_json::to_vec_pretty(t).unwrap())?;
            #[cfg(unix)]
            {
                use std::os::unix::fs::PermissionsExt;
                let _ = std::fs::set_permissions(&path, std::fs::Permissions::from_mode(0o600));
            }
            Ok(())
        }
        None => match std::fs::remove_file(&path) {
            Err(e) if e.kind() != std::io::ErrorKind::NotFound => Err(e),
            _ => Ok(()),
        },
    }
}

fn now_secs() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs()
}

/// The message the frame gets; also `GET /api/agenda`.
pub fn message(hub: &Hub) -> Value {
    let settings = hub.config.read().unwrap().calendar.clone();
    let cal = hub.calendar.lock().unwrap();
    let updated = cal
        .updated
        .and_then(|t| t.duration_since(UNIX_EPOCH).ok())
        .map(|d| d.as_secs());
    json!({
        "t": "agenda",
        "configured": settings.configured(),
        "signed_in": cal.tokens.is_some(),
        "tz": settings.timezone,
        "titles": settings.titles,
        "days": settings.days,
        "updated": updated,
        "error": cal.error,
        "events": cal.events,
    })
}

pub async fn api_agenda(State(hub): State<Shared>) -> Json<Value> {
    Json(message(&hub))
}

/// Begin a device-code sign-in. Returns what to show the owner.
pub fn begin_sign_in(hub: &Shared) -> Result<Pending, String> {
    let settings = hub.config.read().unwrap().calendar.clone();
    if !settings.configured() {
        return Err("Enter the tenant and client id first.".into());
    }
    let v = post_form(
        &format!("{}/devicecode", settings.authority()),
        &[("client_id", settings.client_id.trim()), ("scope", SCOPES)],
    )?;
    let field = |k: &str| {
        v[k].as_str()
            .map(str::to_owned)
            .ok_or_else(|| format!("Microsoft did not send {k}"))
    };
    let pending = Pending {
        user_code: field("user_code")?,
        verification_uri: field("verification_uri")?,
        message: field("message").unwrap_or_default(),
        device_code: field("device_code")?,
        interval: v["interval"].as_u64().unwrap_or(5).max(1),
        expires_at: SystemTime::now() + Duration::from_secs(v["expires_in"].as_u64().unwrap_or(900)),
    };
    hub.calendar.lock().unwrap().pending = Some(pending.clone());
    hub.log(format!("calendar: sign-in started, code {}", pending.user_code));
    let hub = hub.clone();
    tokio::task::spawn_blocking(move || wait_for_sign_in(hub, settings));
    Ok(pending)
}

fn wait_for_sign_in(hub: Shared, settings: Settings) {
    loop {
        let pending = match hub.calendar.lock().unwrap().pending.clone() {
            Some(p) => p,
            None => return,
        };
        if SystemTime::now() > pending.expires_at {
            hub.calendar.lock().unwrap().pending = None;
            hub.log("calendar: the sign-in code expired before it was used".into());
            return;
        }
        std::thread::sleep(Duration::from_secs(pending.interval));
        let reply = post_form_any(
            &format!("{}/token", settings.authority()),
            &[
                ("grant_type", "urn:ietf:params:oauth:grant-type:device_code"),
                ("client_id", settings.client_id.trim()),
                ("device_code", &pending.device_code),
            ],
        );
        match reply {
            Ok(v) if v["access_token"].is_string() => {
                let tokens = tokens_from(&v, String::new());
                let account = whoami(&tokens.access_token).unwrap_or_default();
                let tokens = Tokens { account, ..tokens };
                hub.log(format!("calendar: signed in as {}", tokens.account));
                if let Err(e) = save_tokens(&hub, Some(&tokens)) {
                    hub.log(format!("calendar: could not save the sign-in: {e}"));
                }
                let mut cal = hub.calendar.lock().unwrap();
                cal.tokens = Some(tokens);
                cal.pending = None;
                cal.error = None;
                drop(cal);
                refresh_now(&hub);
                return;
            }
            Ok(v) => match v["error"].as_str().unwrap_or("") {
                "authorization_pending" => {}
                "slow_down" => std::thread::sleep(Duration::from_secs(5)),
                other => {
                    hub.calendar.lock().unwrap().pending = None;
                    hub.log(format!(
                        "calendar: sign-in failed: {}",
                        v["error_description"].as_str().unwrap_or(other)
                    ));
                    return;
                }
            },
            Err(e) => {
                hub.log(format!("calendar: sign-in check failed: {e}"));
                std::thread::sleep(Duration::from_secs(10));
            }
        }
    }
}

pub fn sign_out(hub: &Hub) -> Result<(), String> {
    save_tokens(hub, None).map_err(|e| e.to_string())?;
    let mut cal = hub.calendar.lock().unwrap();
    cal.tokens = None;
    cal.pending = None;
    cal.events.clear();
    cal.updated = None;
    cal.error = None;
    cal.reminded.clear();
    drop(cal);
    hub.log("calendar: signed out".into());
    hub.send_all(&message(hub));
    Ok(())
}

fn tokens_from(v: &Value, account: String) -> Tokens {
    Tokens {
        access_token: v["access_token"].as_str().unwrap_or("").to_owned(),
        refresh_token: v["refresh_token"].as_str().unwrap_or("").to_owned(),
        expires_at: now_secs() + v["expires_in"].as_u64().unwrap_or(3600).saturating_sub(60),
        account,
    }
}

fn whoami(access_token: &str) -> Result<String, String> {
    let v = get_json(
        &format!("{GRAPH}/me?$select=displayName,mail,userPrincipalName"),
        access_token,
    )?;
    Ok(v["mail"]
        .as_str()
        .or(v["userPrincipalName"].as_str())
        .or(v["displayName"].as_str())
        .unwrap_or("someone")
        .to_owned())
}

/// A usable access token, refreshed if it is about to run out.
fn access_token(hub: &Hub, settings: &Settings) -> Result<String, String> {
    let tokens = hub.calendar.lock().unwrap().tokens.clone().ok_or("not signed in")?;
    if tokens.expires_at > now_secs() + 30 {
        return Ok(tokens.access_token);
    }
    let v = post_form_any(
        &format!("{}/token", settings.authority()),
        &[
            ("grant_type", "refresh_token"),
            ("client_id", settings.client_id.trim()),
            ("refresh_token", &tokens.refresh_token),
            ("scope", SCOPES),
        ],
    )?;
    if !v["access_token"].is_string() {
        let why = v["error_description"]
            .as_str()
            .or(v["error"].as_str())
            .unwrap_or("no token");
        // interaction_required and invalid_grant mean the owner has to sign in again.
        return Err(format!("sign-in no longer valid: {why}"));
    }
    let mut fresh = tokens_from(&v, tokens.account.clone());
    if fresh.refresh_token.is_empty() {
        fresh.refresh_token = tokens.refresh_token;
    }
    if let Err(e) = save_tokens(hub, Some(&fresh)) {
        hub.log(format!("calendar: could not save the refreshed sign-in: {e}"));
    }
    hub.calendar.lock().unwrap().tokens = Some(fresh.clone());
    Ok(fresh.access_token)
}

/// Read the calendar now and push the agenda if it changed. Blocking; call from a thread.
pub fn refresh_now(hub: &Hub) {
    let settings = hub.config.read().unwrap().calendar.clone();
    if !settings.configured() || hub.calendar.lock().unwrap().tokens.is_none() {
        return;
    }
    let outcome = access_token(hub, &settings).and_then(|token| fetch(&settings, &token));
    let mut cal = hub.calendar.lock().unwrap();
    let changed = match outcome {
        Ok(events) => {
            let changed = events != cal.events || cal.error.is_some();
            cal.events = events;
            cal.updated = Some(SystemTime::now());
            cal.error = None;
            changed
        }
        Err(e) => {
            let changed = cal.error.as_deref() != Some(e.as_str());
            if changed {
                hub.log(format!("calendar: {e}"));
            }
            cal.error = Some(e);
            changed
        }
    };
    drop(cal);
    if changed {
        hub.send_all(&message(hub));
    }
}

fn fetch(settings: &Settings, token: &str) -> Result<Vec<Event>, String> {
    let tz = settings.tz();
    let now = Utc::now().with_timezone(&tz);
    let start_local = tz
        .with_ymd_and_hms(now.year(), now.month(), now.day(), 0, 0, 0)
        .single()
        .ok_or("bad local midnight")?;
    let end_local = start_local + chrono::Duration::days(settings.days.clamp(1, 14) as i64);
    let (start, end) = (start_local.with_timezone(&Utc), end_local.with_timezone(&Utc));
    let url = format!(
        "{GRAPH}/me/calendarView?startDateTime={}&endDateTime={}&$orderby=start/dateTime&$top=100&$select={}",
        start.to_rfc3339_opts(chrono::SecondsFormat::Secs, true),
        end.to_rfc3339_opts(chrono::SecondsFormat::Secs, true),
        "subject,start,end,isAllDay,showAs,isCancelled,location,isOnlineMeeting,onlineMeetingProvider,responseStatus,isOrganizer,sensitivity"
    );
    let mut events = Vec::new();
    let mut next = Some(url);
    while let Some(url) = next.take() {
        let v = ureq::get(&url)
            .set("Authorization", &format!("Bearer {token}"))
            .set("Prefer", &format!("outlook.timezone=\"{}\"", settings.timezone))
            .timeout(Duration::from_secs(20))
            .call()
            .map_err(describe)?
            .into_json::<Value>()
            .map_err(|e| e.to_string())?;
        for item in v["value"].as_array().into_iter().flatten() {
            if let Some(e) = event_from(item, settings) {
                events.push(e);
            }
        }
        next = v["@odata.nextLink"].as_str().map(str::to_owned);
    }
    // By start; an all-day item sorts before a timed one that starts at the same moment.
    events.sort_by(|a, b| a.start.cmp(&b.start).then(b.all_day.cmp(&a.all_day)));
    Ok(events)
}

fn event_from(item: &Value, settings: &Settings) -> Option<Event> {
    if item["isCancelled"] == true {
        return None;
    }
    let response = match item["responseStatus"]["response"].as_str().unwrap_or("none") {
        "organizer" => "organizer",
        "accepted" => "accepted",
        "tentativelyAccepted" => "tentative",
        "declined" => "declined",
        _ => "none",
    };
    if response == "declined" && !settings.show_declined {
        return None;
    }
    let clock = |v: &Value| {
        v["dateTime"]
            .as_str()
            .map(|s| s.chars().take(19).collect::<String>())
            .unwrap_or_default()
    };
    let private = item["sensitivity"].as_str() == Some("private");
    let subject = item["subject"].as_str().unwrap_or("").trim();
    let title = if !settings.titles {
        "Busy".to_owned()
    } else if private {
        "Private".to_owned()
    } else if subject.is_empty() {
        "(no title)".to_owned()
    } else {
        subject.chars().take(80).collect()
    };
    let online = item["isOnlineMeeting"] == true;
    let provider = item["onlineMeetingProvider"].as_str().unwrap_or("");
    let place = item["location"]["displayName"].as_str().unwrap_or("").trim();
    let location = if provider.contains("teams") || place.to_lowercase().contains("teams") {
        "Teams".to_owned()
    } else if place.to_lowercase().contains("zoom") {
        "Zoom".to_owned()
    } else if place.to_lowercase().contains("meet.google") {
        "Meet".to_owned()
    } else if online {
        "Online".to_owned()
    } else if private || !settings.titles {
        String::new()
    } else {
        place.chars().take(40).collect()
    };
    let raw_id = item["id"].as_str().unwrap_or("");
    // Graph ids are long; a short stable hash is enough to tell events apart.
    let id = format!("{:016x}", fnv(raw_id.as_bytes()));
    Some(Event {
        id,
        title,
        start: clock(&item["start"]),
        end: clock(&item["end"]),
        all_day: item["isAllDay"] == true,
        busy: item["showAs"].as_str().unwrap_or("busy").to_owned(),
        online,
        location,
        response: response.to_owned(),
        mine: item["isOrganizer"] == true,
    })
}

fn fnv(bytes: &[u8]) -> u64 {
    let mut h: u64 = 0xcbf2_9ce4_8422_2325;
    for b in bytes {
        h ^= *b as u64;
        h = h.wrapping_mul(0x0100_0000_01b3);
    }
    h
}

/// Runs for the life of the hub: reads the calendar on a timer and sends reminders.
pub async fn run(hub: Shared) {
    let mut last_poll: Option<SystemTime> = None;
    loop {
        let due = last_poll.is_none_or(|t| t.elapsed().unwrap_or(POLL) >= POLL);
        if due {
            let h = hub.clone();
            let _ = tokio::task::spawn_blocking(move || refresh_now(&h)).await;
            last_poll = Some(SystemTime::now());
        }
        remind(&hub);
        tokio::time::sleep(TICK).await;
    }
}

fn remind(hub: &Hub) {
    let settings = hub.config.read().unwrap().calendar.clone();
    if settings.remind_min == 0 {
        return;
    }
    let tz = settings.tz();
    let now = Utc::now().with_timezone(&tz).naive_local();
    let soon = now + chrono::Duration::minutes(settings.remind_min as i64);
    let mut due = Vec::new();
    {
        let mut cal = hub.calendar.lock().unwrap();
        let reminded = std::mem::take(&mut cal.reminded);
        let mut kept = HashSet::new();
        for e in &cal.events {
            if e.all_day || e.response == "declined" || e.busy == "free" {
                continue;
            }
            let Ok(start) = NaiveDateTime::parse_from_str(&e.start, "%Y-%m-%dT%H:%M:%S") else {
                continue;
            };
            if start <= now {
                continue;
            }
            if start <= soon {
                kept.insert(e.id.clone());
                if !reminded.contains(&e.id) {
                    due.push((e.clone(), (start - now).num_minutes().max(1)));
                }
            }
        }
        cal.reminded = kept;
    }
    for (e, minutes) in due {
        let place = if e.location.is_empty() {
            String::new()
        } else {
            format!(" · {}", e.location)
        };
        let _ = hub.notify(Notify {
            text: format!("In {minutes} min · {}{place}", e.title),
            level: Some("nudge".into()),
            source: Some("calendar".into()),
            ttl_s: Some(45),
        });
    }
}

/// Sends the agenda to one newly connected frame.
pub fn hello(hub: &Hub) -> String {
    message(hub).to_string()
}

/// A line for the page: who is signed in and how fresh the agenda is.
pub fn status(hub: &Hub) -> Value {
    let settings = hub.config.read().unwrap().calendar.clone();
    let cal = hub.calendar.lock().unwrap();
    let tz = settings.tz();
    let today = Utc::now().with_timezone(&tz).format("%Y-%m-%d").to_string();
    json!({
        "configured": settings.configured(),
        "account": cal.tokens.as_ref().map(|t| t.account.clone()),
        "pending": cal.pending.as_ref().map(|p| json!({ "code": p.user_code, "uri": p.verification_uri, "message": p.message })),
        "updated_ago_s": cal.updated.and_then(|t| t.elapsed().ok()).map(|d| d.as_secs()),
        "error": cal.error,
        "today": cal.events.iter().filter(|e| e.start.starts_with(&today)).count(),
        "total": cal.events.len(),
    })
}

fn describe(e: ureq::Error) -> String {
    match e {
        ureq::Error::Status(code, resp) => {
            let body = resp.into_string().unwrap_or_default();
            let v: Value = serde_json::from_str(&body).unwrap_or(Value::Null);
            let why = v["error"]["message"]
                .as_str()
                .or(v["error_description"].as_str())
                .or(v["error"].as_str())
                .unwrap_or(&body);
            format!("HTTP {code}: {}", why.chars().take(200).collect::<String>())
        }
        other => other.to_string(),
    }
}

fn post_form(url: &str, form: &[(&str, &str)]) -> Result<Value, String> {
    ureq::post(url)
        .timeout(Duration::from_secs(20))
        .send_form(form)
        .map_err(describe)?
        .into_json::<Value>()
        .map_err(|e| e.to_string())
}

/// Like `post_form`, but a 4xx answer with a JSON body is returned, not an error: the token
/// endpoint says "authorization_pending" that way.
fn post_form_any(url: &str, form: &[(&str, &str)]) -> Result<Value, String> {
    match ureq::post(url).timeout(Duration::from_secs(20)).send_form(form) {
        Ok(resp) => resp.into_json::<Value>().map_err(|e| e.to_string()),
        Err(ureq::Error::Status(_, resp)) => {
            let body = resp.into_string().map_err(|e| e.to_string())?;
            serde_json::from_str::<Value>(&body).map_err(|_| body.chars().take(200).collect())
        }
        Err(e) => Err(e.to_string()),
    }
}

fn get_json(url: &str, token: &str) -> Result<Value, String> {
    ureq::get(url)
        .set("Authorization", &format!("Bearer {token}"))
        .timeout(Duration::from_secs(20))
        .call()
        .map_err(describe)?
        .into_json::<Value>()
        .map_err(|e| e.to_string())
}

/// `POST /api/calendar/refresh`: read the calendar now.
pub async fn api_refresh(State(hub): State<Shared>) -> impl IntoResponse {
    let h = hub.clone();
    let _ = tokio::task::spawn_blocking(move || refresh_now(&h)).await;
    Json(status(&hub))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn graph_event_becomes_a_frame_event() {
        let settings = Settings::default();
        let item = json!({
            "id": "AAMkAG", "subject": "Daily MAD", "isAllDay": false, "showAs": "busy", "isCancelled": false,
            "start": { "dateTime": "2026-10-15T09:00:00.0000000", "timeZone": "Europe/Berlin" },
            "end": { "dateTime": "2026-10-15T09:30:00.0000000", "timeZone": "Europe/Berlin" },
            "location": { "displayName": "Microsoft Teams Meeting" }, "isOnlineMeeting": true,
            "onlineMeetingProvider": "teamsForBusiness", "responseStatus": { "response": "accepted" }, "isOrganizer": false
        });
        let e = event_from(&item, &settings).unwrap();
        assert_eq!(e.title, "Daily MAD");
        assert_eq!(e.start, "2026-10-15T09:00:00");
        assert_eq!(e.location, "Teams");
        assert_eq!(e.response, "accepted");
    }

    #[test]
    fn declined_and_cancelled_are_dropped_and_titles_can_be_hidden() {
        let mut settings = Settings::default();
        let declined = json!({ "id": "x", "subject": "Skip", "responseStatus": { "response": "declined" }, "start": {}, "end": {} });
        assert!(event_from(&declined, &settings).is_none());
        let cancelled = json!({ "id": "y", "subject": "Gone", "isCancelled": true, "start": {}, "end": {} });
        assert!(event_from(&cancelled, &settings).is_none());
        settings.titles = false;
        let item = json!({ "id": "z", "subject": "Secret plans", "location": { "displayName": "Room 4" }, "start": {}, "end": {} });
        let e = event_from(&item, &settings).unwrap();
        assert_eq!(e.title, "Busy");
        assert_eq!(e.location, "");
    }
}
