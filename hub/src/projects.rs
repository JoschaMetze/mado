//! Project status: a short list agents keep current, shown on the frame's dashboard.

use std::time::{SystemTime, UNIX_EPOCH};

use axum::{
    extract::State,
    http::StatusCode,
    response::{IntoResponse, Response},
    Json,
};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};

use crate::{Hub, Shared};

/// In the order the dashboard lists them: what needs the owner comes first.
pub const STATUSES: [&str; 4] = ["needs_you", "blocked", "on_track", "done"];
/// Not a status to show: reporting it takes the project off the dashboard.
pub const ARCHIVED: &str = "archived";

#[derive(Clone, Serialize, Deserialize)]
pub struct Project {
    pub name: String,
    pub status: String,
    #[serde(default)]
    pub note: String,
    pub progress: Option<f64>,
    #[serde(default)]
    pub updated: u64,
}

#[derive(Deserialize)]
pub struct Update {
    pub name: String,
    pub status: String,
    #[serde(default)]
    pub note: String,
    pub progress: Option<f64>,
}

impl Hub {
    pub fn projects_message(&self) -> Value {
        let mut items = self.projects.lock().unwrap().clone();
        let rank = |p: &Project| STATUSES.iter().position(|s| *s == p.status).unwrap_or(STATUSES.len());
        items.sort_by(|a, b| rank(a).cmp(&rank(b)).then(b.updated.cmp(&a.updated)));
        json!({ "t": "projects", "items": items })
    }

    pub fn update_project(&self, u: Update) -> Result<Value, String> {
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
            let project = Project {
                name: name.to_owned(),
                status: u.status.clone(),
                note: note.to_owned(),
                progress: u.progress.map(|p| p.clamp(0.0, 1.0)),
                updated,
            };
            match (existing, archived) {
                (Some(i), true) => drop(projects.remove(i)),
                (Some(i), false) => projects[i] = project,
                (None, true) => return Err(format!("there is no project called {name}")),
                (None, false) => projects.push(project),
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
    match hub.update_project(update) {
        Ok(v) => Json(v).into_response(),
        Err(e) => (StatusCode::BAD_REQUEST, Json(json!({ "error": e }))).into_response(),
    }
}
