//! Project status: a short list agents keep current, shown on the frame's dashboard.

use std::{
    hash::{DefaultHasher, Hash, Hasher},
    io::{Cursor, Read},
    time::{Duration, SystemTime, UNIX_EPOCH},
};

use axum::{
    extract::{Path, State},
    http::{header, StatusCode},
    response::{IntoResponse, Response},
    Json,
};
use base64::Engine;
use image::{imageops::FilterType, ImageFormat};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};

use crate::{Hub, Shared};

/// In the order the dashboard lists them: what needs the owner comes first.
pub const STATUSES: [&str; 4] = ["needs_you", "blocked", "on_track", "done"];
/// Not a status to show: reporting it takes the project off the dashboard.
pub const ARCHIVED: &str = "archived";
/// Icons are kept as PNGs that fit a square this size; the frame draws them at half that.
const ICON_PX: u32 = 96;
const MAX_ICON_BYTES: u64 = 4 << 20;
/// What a project without an icon is given instead: its initials on one of these, chosen
/// by its name so it keeps the same colour. All are dark enough for white letters.
const TINTS: [&str; 8] = [
    "#c2476b", "#d0672f", "#a8892a", "#3f9a66", "#2f8f9d", "#3f74c4", "#7a5cc7", "#8a6a58",
];

#[derive(Clone, Serialize, Deserialize)]
pub struct Project {
    pub name: String,
    pub status: String,
    #[serde(default)]
    pub note: String,
    pub progress: Option<f64>,
    #[serde(default)]
    pub updated: u64,
    /// Names the picture in the data directory's `icons`, and the file the frame fetches.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub icon: Option<String>,
}

#[derive(Deserialize)]
pub struct Update {
    pub name: String,
    pub status: String,
    #[serde(default)]
    pub note: String,
    pub progress: Option<f64>,
    /// A PNG or JPEG, as an http(s) URL or in base64. Left out, the project keeps the icon
    /// it has; empty takes it away.
    pub icon: Option<String>,
}

/// What a report does to the project's icon.
pub enum Icon {
    Keep,
    Clear,
    /// A picture already through `prepare_icon`.
    Set(Vec<u8>),
}

/// Up to two letters to stand for a project that has no icon.
fn initials(name: &str) -> String {
    let words: Vec<&str> = name
        .split(|c: char| !c.is_alphanumeric())
        .filter(|w| !w.is_empty())
        .collect();
    let first = |w: &str| {
        w.chars()
            .next()
            .map(|c| c.to_uppercase().to_string())
            .unwrap_or_default()
    };
    match words.as_slice() {
        [] => name.chars().take(1).collect(),
        // One word gives two letters, unless it is written in characters that say enough alone.
        [one] => {
            let second = one.chars().nth(1).filter(|c| c.is_ascii_alphanumeric());
            let alone = !one.is_ascii();
            first(one)
                + &second
                    .filter(|_| !alone)
                    .map(|c| c.to_lowercase().to_string())
                    .unwrap_or_default()
        }
        [a, b, ..] => first(a) + &first(b),
    }
}

fn tint(name: &str) -> &'static str {
    // FNV-1a, so a project's colour does not change when the compiler does.
    let hash = name
        .to_lowercase()
        .bytes()
        .fold(0x811c_9dc5u32, |h, b| (h ^ b as u32).wrapping_mul(0x0100_0193));
    TINTS[hash as usize % TINTS.len()]
}

/// Read what a sender gave as an icon: a URL to fetch, or the picture itself in base64.
fn icon_source(icon: &str) -> Result<Vec<u8>, String> {
    if icon.starts_with("http://") || icon.starts_with("https://") {
        let mut bytes = Vec::new();
        ureq::get(icon)
            .timeout(Duration::from_secs(15))
            .call()
            .map_err(|e| format!("icon: {e}"))?
            .into_reader()
            .take(MAX_ICON_BYTES)
            .read_to_end(&mut bytes)
            .map_err(|e| format!("icon: {e}"))?;
        return Ok(bytes);
    }
    // A data: URL is base64 with a label in front.
    let b64 = icon.rsplit_once("base64,").map_or(icon, |(_, rest)| rest);
    base64::engine::general_purpose::STANDARD
        .decode(b64.trim())
        .map_err(|_| "icon must be an http(s) URL or a base64 PNG or JPEG".into())
}

/// Shrink a picture to icon size, keeping its shape and any transparency.
pub fn prepare_icon(original: &[u8]) -> Result<Vec<u8>, String> {
    let img = image::load_from_memory(original).map_err(|_| "icon must be a PNG or JPEG")?;
    let img = if img.width().max(img.height()) > ICON_PX {
        img.resize(ICON_PX, ICON_PX, FilterType::CatmullRom)
    } else {
        img
    };
    let mut png = Vec::new();
    img.to_rgba8()
        .write_to(&mut Cursor::new(&mut png), ImageFormat::Png)
        .map_err(|e| e.to_string())?;
    Ok(png)
}

/// Take a report from the API or MCP: fetch and shrink its icon off the async threads, then
/// record it.
pub async fn report(hub: &Shared, mut update: Update) -> Result<Value, String> {
    let icon = match update.icon.take() {
        None => Icon::Keep,
        Some(given) if given.trim().is_empty() => Icon::Clear,
        Some(given) => {
            let prepared =
                tokio::task::spawn_blocking(move || icon_source(given.trim()).and_then(|bytes| prepare_icon(&bytes)));
            Icon::Set(prepared.await.map_err(|_| "icon could not be read")??)
        }
    };
    hub.update_project(update, icon)
}

impl Hub {
    pub fn projects_message(&self) -> Value {
        let mut items = self.projects.lock().unwrap().clone();
        let rank = |p: &Project| STATUSES.iter().position(|s| *s == p.status).unwrap_or(STATUSES.len());
        items.sort_by(|a, b| rank(a).cmp(&rank(b)).then(b.updated.cmp(&a.updated)));
        let items: Vec<Value> = items
            .iter()
            .map(|p| {
                let mut item = json!(p);
                item["initials"] = json!(initials(&p.name));
                item["tint"] = json!(tint(&p.name));
                item
            })
            .collect();
        json!({ "t": "projects", "items": items })
    }

    /// Save an icon under a name made from its contents, so a new picture is a new file to
    /// the frame and the same picture is stored once.
    fn keep_icon(&self, png: &[u8]) -> Result<String, String> {
        let mut hasher = DefaultHasher::new();
        png.hash(&mut hasher);
        let key = format!("{:016x}", hasher.finish());
        let dir = self.data_dir.join("icons");
        std::fs::create_dir_all(&dir)
            .and_then(|_| std::fs::write(dir.join(format!("{key}.png")), png))
            .map_err(|e| format!("could not save the icon: {e}"))?;
        Ok(key)
    }

    pub fn update_project(&self, u: Update, icon: Icon) -> Result<Value, String> {
        let name = u.name.trim();
        if name.is_empty() || name.chars().count() > 40 {
            return Err("name must be 1 to 40 characters".into());
        }
        let note = u.note.trim();
        if note.chars().count() > 120 {
            return Err("note is longer than 120 characters".into());
        }
        let archived = u.status == ARCHIVED;
        if !archived && !STATUSES.contains(&u.status.as_str()) {
            return Err(format!("status must be one of {}, {ARCHIVED}", STATUSES.join(", ")));
        }
        {
            let mut projects = self.projects.lock().unwrap();
            let existing = projects.iter().position(|p| p.name.eq_ignore_ascii_case(name));
            let updated = SystemTime::now().duration_since(UNIX_EPOCH).map_or(0, |d| d.as_secs());
            let before = existing.and_then(|i| projects[i].icon.clone());
            let icon = match icon {
                Icon::Keep => before.clone(),
                Icon::Clear => None,
                Icon::Set(_) if archived => None,
                Icon::Set(png) => Some(self.keep_icon(&png)?),
            };
            let project = Project {
                name: name.to_owned(),
                status: u.status.clone(),
                note: note.to_owned(),
                progress: u.progress.map(|p| p.clamp(0.0, 1.0)),
                updated,
                icon,
            };
            match (existing, archived) {
                (Some(i), true) => drop(projects.remove(i)),
                (Some(i), false) => projects[i] = project,
                (None, true) => return Err(format!("there is no project called {name}")),
                (None, false) => projects.push(project),
            }
            // A picture no project wears any more is not worth keeping.
            if let Some(old) = before.filter(|old| !projects.iter().any(|p| p.icon.as_ref() == Some(old))) {
                let _ = std::fs::remove_file(self.data_dir.join("icons").join(format!("{old}.png")));
            }
            let _ = std::fs::create_dir_all(&self.data_dir);
            if let Err(e) = std::fs::write(
                self.data_dir.join("projects.json"),
                serde_json::to_vec_pretty(&*projects).unwrap(),
            ) {
                self.log(format!("could not save projects: {e}"));
            }
        }
        self.log(format!(
            "project {name}: {}{}",
            u.status.replace('_', " "),
            if note.is_empty() {
                String::new()
            } else {
                format!(" · {note}")
            }
        ));
        let frames = self.send_all(&self.projects_message());
        Ok(json!({ "name": name, "status": u.status, "shown_on_frames": frames }))
    }
}

pub async fn post(State(hub): State<Shared>, Json(update): Json<Update>) -> Response {
    match report(&hub, update).await {
        Ok(v) => Json(v).into_response(),
        Err(e) => (StatusCode::BAD_REQUEST, Json(json!({ "error": e }))).into_response(),
    }
}

/// A project's icon, for the frame and the hub's page. The name comes from `keep_icon`.
pub async fn icon_file(State(hub): State<Shared>, Path(file): Path<String>) -> Response {
    let named_by_us = file
        .strip_suffix(".png")
        .is_some_and(|key| key.len() == 16 && key.bytes().all(|b| b.is_ascii_hexdigit()));
    match named_by_us
        .then(|| std::fs::read(hub.data_dir.join("icons").join(&file)).ok())
        .flatten()
    {
        Some(png) => (
            [
                (header::CONTENT_TYPE, "image/png"),
                (header::CACHE_CONTROL, "max-age=31536000, immutable"),
            ],
            png,
        )
            .into_response(),
        None => StatusCode::NOT_FOUND.into_response(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn initials_come_from_the_first_two_words_or_letters() {
        assert_eq!(initials("Mado hub"), "MH");
        assert_eq!(initials("mado"), "Ma");
        assert_eq!(initials("snow-report.jp site"), "SR");
        assert_eq!(initials("X"), "X");
        assert_eq!(initials("窓プロジェクト"), "窓");
        assert_eq!(initials("!!"), "!");
    }

    #[test]
    fn a_project_keeps_its_colour_however_its_name_is_typed() {
        assert_eq!(tint("Mado"), tint("mado"));
        assert!(TINTS.contains(&tint("anything")));
    }

    #[test]
    fn icons_are_shrunk_to_fit_and_keep_their_shape() {
        let mut big = Vec::new();
        image::RgbaImage::from_pixel(400, 200, image::Rgba([200, 40, 40, 128]))
            .write_to(&mut Cursor::new(&mut big), ImageFormat::Png)
            .unwrap();
        let icon = image::load_from_memory(&prepare_icon(&big).unwrap()).unwrap();
        assert_eq!((icon.width(), icon.height()), (96, 48));
        assert_eq!(icon.to_rgba8().get_pixel(10, 10)[3], 128);
        assert!(prepare_icon(b"<svg/>").is_err());
        assert!(icon_source("not base64!").is_err());
        assert_eq!(icon_source("data:image/png;base64,aGk=").unwrap(), b"hi");
    }
}
