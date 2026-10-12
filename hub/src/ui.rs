//! The web interface: status, a test alert, and all configuration. Server-rendered, no script.

use axum::{
    extract::{Query, State},
    http::{header::HOST, HeaderMap},
    response::{Html, Redirect},
    Form,
};
use serde::Deserialize;
use serde_json::Value;

use crate::{art, projects, Notify, Place, Shared};

const STYLE: &str = r#"
:root{--bg:#0d1217;--panel:#151c23;--fg:#e7edf2;--muted:#94a2b0;--line:#27323d;--accent:#ff7fa8;--ok:#4fd19c;--bad:#ff7b7b;color-scheme:dark}
@media (prefers-color-scheme:light){:root{--bg:#edf0f3;--panel:#fff;--fg:#15202b;--muted:#5b6876;--line:#d3dae1;--accent:#c2376b;--ok:#1f9d6b;--bad:#d24b4b;color-scheme:light}}
*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:var(--fg);font:16px/1.5 system-ui,sans-serif}
main{max-width:980px;margin:0 auto;padding:32px 20px 80px;display:grid;gap:28px}
header{display:flex;align-items:baseline;gap:14px;flex-wrap:wrap}
h1{margin:0;font-size:2.2rem;letter-spacing:-.02em}
h2{margin:0 0 12px;font-size:1.05rem}
.tag{font:500 .75rem ui-monospace,Menlo,monospace;letter-spacing:.1em;text-transform:uppercase;color:var(--muted)}
.cols{display:grid;grid-template-columns:repeat(auto-fit,minmax(300px,1fr));gap:28px}
section{background:var(--panel);border:1px solid var(--line);border-radius:10px;padding:20px;min-width:0}
.glass{display:block;width:100%;aspect-ratio:16/10;background:#000;border-radius:6px;border:6px solid #05070a;object-fit:cover;color:var(--muted)}
.dot{display:inline-block;width:.6em;height:.6em;border-radius:50%;margin-right:.5em;background:var(--bad)}
.dot.up{background:var(--ok)}
form{display:grid;gap:10px}
label{display:grid;gap:4px;font-size:.85rem;color:var(--muted)}
input,select,button{font:inherit;color:var(--fg);background:var(--bg);border:1px solid var(--line);border-radius:6px;padding:8px 10px;min-width:0}
button{cursor:pointer;background:var(--accent);border-color:var(--accent);color:var(--bg);font-weight:600;justify-self:start}
button.quiet{background:transparent;color:var(--muted);border-color:var(--line);font-weight:400;padding:2px 8px}
.row{display:grid;grid-template-columns:2fr 1fr 1fr;gap:10px}
ul{list-style:none;margin:0 0 14px;padding:0;display:grid;gap:6px}
li{display:flex;gap:10px;align-items:center;justify-content:space-between}
li form{display:inline}
.log li{justify-content:flex-start;font-size:.9rem}
.log span,.muted{color:var(--muted)}
.log span{flex:none;width:5.5em;font-variant-numeric:tabular-nums}
summary{cursor:pointer;margin:14px 0 10px}
code{font:.85em ui-monospace,Menlo,monospace;background:var(--bg);padding:.15em .4em;border-radius:4px;overflow-wrap:anywhere}
"#;

fn esc(s: &str) -> String {
    s.replace('&', "&amp;").replace('<', "&lt;").replace('>', "&gt;").replace('"', "&quot;")
}

fn ago(secs: u64) -> String {
    match secs {
        0..=59 => format!("{secs}s ago"),
        60..=3599 => format!("{}m ago", secs / 60),
        _ => format!("{}h ago", secs / 3600),
    }
}

#[derive(Deserialize)]
pub struct Search {
    city: Option<String>,
}

/// Look a city up by name with Open-Meteo's geocoder (free, no key).
fn find_cities(name: &str) -> Result<Vec<Value>, String> {
    let found: Value = ureq::get("https://geocoding-api.open-meteo.com/v1/search")
        .query("name", name)
        .query("count", "6")
        .query("language", "en")
        .query("format", "json")
        .timeout(std::time::Duration::from_secs(8))
        .call()
        .map_err(|e| e.to_string())?
        .into_json()
        .map_err(|e| e.to_string())?;
    Ok(found["results"].as_array().cloned().unwrap_or_default())
}

pub async fn index(State(hub): State<Shared>, headers: HeaderMap, Query(search): Query<Search>) -> Html<String> {
    let host = headers.get(HOST).and_then(|h| h.to_str().ok()).unwrap_or("HUB_ADDRESS:7878");

    let city = search.city.as_deref().map(str::trim).filter(|c| !c.is_empty()).map(str::to_owned);
    let matches = match city.clone() {
        None => String::new(),
        Some(name) => match tokio::task::spawn_blocking(move || find_cities(&name)).await {
            Ok(Ok(found)) if found.is_empty() => r#"<p class="muted">No city by that name. Try a different spelling.</p>"#.to_owned(),
            Ok(Ok(found)) => {
                let rows: String = found
                    .iter()
                    .map(|c| {
                        let name = c["name"].as_str().unwrap_or("");
                        let region: Vec<&str> = [c["admin1"].as_str(), c["country"].as_str()].into_iter().flatten().filter(|s| *s != name).collect();
                        format!(
                            r#"<li><span>{} <span class="muted">{}</span></span>
                            <form method="post" action="/ui/places/add"><input type="hidden" name="name" value="{}"><input type="hidden" name="lat" value="{}"><input type="hidden" name="lon" value="{}"><input type="hidden" name="timezone" value="{}"><button>Add</button></form></li>"#,
                            esc(name), esc(&region.join(", ")), esc(name),
                            c["latitude"].as_f64().unwrap_or(0.0), c["longitude"].as_f64().unwrap_or(0.0),
                            esc(c["timezone"].as_str().unwrap_or("")),
                        )
                    })
                    .collect();
                format!("<ul>{rows}</ul>")
            }
            _ => r#"<p class="muted">The city search is unreachable right now. You can still add a place by coordinates.</p>"#.to_owned(),
        },
    };

    let frames = hub.frames.lock().unwrap();
    let status = if frames.is_empty() {
        format!(
            r#"<p><i class="dot"></i>No frame connected.</p>
            <p class="muted">Point the frame at this hub once, over USB:</p>
            <p><code>adb shell am start -n jp.co.xenocode.mado/.MainActivity --es hub ws://{}/frame</code></p>"#,
            esc(host)
        )
    } else {
        frames
            .values()
            .map(|f| {
                format!(
                    r#"<p><i class="dot up"></i>{} · connected {}</p>"#,
                    esc(f.hello["model"].as_str().unwrap_or("Frame")),
                    ago(f.since.elapsed().as_secs())
                )
            })
            .collect()
    };
    let glass = if frames.is_empty() {
        r#"<div class="glass"></div>"#.to_owned()
    } else {
        r#"<img class="glass" src="/api/look" alt="What the frame is showing now"><p><a href="/">Refresh</a></p>"#.to_owned()
    };
    drop(frames);

    let places: String = hub
        .config
        .read()
        .unwrap()
        .places
        .iter()
        .enumerate()
        .map(|(i, p)| {
            format!(
                r#"<li><span>{}{} <span class="muted">{:.3}, {:.3}</span></span>
                <form method="post" action="/ui/places/remove"><input type="hidden" name="index" value="{i}"><button class="quiet">Remove</button></form></li>"#,
                esc(&p.name), if i == 0 { r#" <span class="tag">here</span>"# } else { "" }, p.lat, p.lon
            )
        })
        .collect();
    let places = if places.is_empty() { r#"<li class="muted">No places yet.</li>"#.to_owned() } else { places };

    let painting = match hub.art.lock().unwrap().as_ref() {
        Some(a) => format!(
            r#"<img class="glass" style="aspect-ratio:auto;max-height:260px;object-fit:contain" src="/daily/{}/image.jpg" alt="">
            <p><i>{}</i><br><span class="muted">{} · {}</span></p>
            <form method="post" action="/ui/art/another"><button>Another painting</button></form>"#,
            a.version,
            esc(a.meta["title"].as_str().unwrap_or("")),
            esc(a.meta["artist"].as_str().unwrap_or("")),
            esc(a.meta["date"].as_str().unwrap_or("")),
        ),
        None => r#"<p class="muted">The frame asks for a painting when it connects.</p>"#.to_owned(),
    };

    let project_rows: String = hub.projects_message()["items"]
        .as_array()
        .into_iter()
        .flatten()
        .map(|p| {
            let name = esc(p["name"].as_str().unwrap_or(""));
            format!(
                r#"<li><span><b>{name}</b> · {} <span class="muted">{}</span></span>
                <form method="post" action="/ui/projects"><input type="hidden" name="name" value="{name}"><input type="hidden" name="status" value="archived"><button class="quiet">Remove</button></form></li>"#,
                esc(&p["status"].as_str().unwrap_or("").replace('_', " ")),
                esc(p["note"].as_str().unwrap_or("")),
            )
        })
        .collect();
    let project_rows = if project_rows.is_empty() { r#"<li class="muted">No projects yet. Agents add them with frame_project.</li>"#.to_owned() } else { project_rows };

    let log: String = hub
        .log
        .lock()
        .unwrap()
        .iter()
        .map(|e| format!("<li><span>{}</span>{}</li>", ago(e.at.elapsed().as_secs()), esc(&e.line)))
        .collect();

    Html(format!(
        r#"<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Mado hub</title><style>{STYLE}</style>
<main>
<header><h1>Mado</h1><span class="tag">hub · {version}</span></header>
<div class="cols">
  <section><h2>On the glass</h2>{glass}{status}</section>
  <section><h2>Send an alert</h2>
    <form method="post" action="/ui/notify">
      <label>Message<input name="text" maxlength="280" required placeholder="Kettle's boiled"></label>
      <label>Level<select name="level"><option>whisper</option><option selected>nudge</option><option>urgent</option></select></label>
      <label>From<input name="source" placeholder="you"></label>
      <button>Send to frame</button>
    </form>
  </section>
</div>
<div class="cols">
  <section><h2>Art of the day</h2>{painting}</section>
  <section><h2>Projects</h2>
    <ul>{project_rows}</ul>
    <form method="post" action="/ui/projects">
      <label>Name<input name="name" maxlength="40" required></label>
      <label>Status<select name="status"><option value="on_track">on track</option><option value="needs_you">needs you</option><option value="blocked">blocked</option><option value="done">done</option></select></label>
      <label>Note<input name="note" maxlength="120"></label>
      <button>Save project</button>
    </form>
  </section>
</div>
<div class="cols">
  <section><h2>Weather places</h2>
    <ul>{places}</ul>
    <form method="get" action="/">
      <label>Find a city<input name="city" value="{city}" required placeholder="Kamakura"></label>
      <button>Search</button>
    </form>
    {matches}
    <details><summary class="muted">Add by coordinates instead</summary>
    <form method="post" action="/ui/places/add">
      <div class="row">
        <label>Name<input name="name" required placeholder="Mum &amp; Dad"></label>
        <label>Latitude<input name="lat" type="number" step="any" min="-90" max="90" required></label>
        <label>Longitude<input name="lon" type="number" step="any" min="-180" max="180" required></label>
      </div>
      <button>Add place</button>
    </form></details>
    <p class="muted">The first place is "here" on the dashboard. The frame fetches the forecast itself.</p>
  </section>
  <section><h2>Recent</h2><ul class="log">{log}</ul>
    <p class="muted">Agents connect to <code>http://{host}/mcp</code></p>
  </section>
</div>
</main></html>"#,
        version = env!("CARGO_PKG_VERSION"),
        host = esc(host),
        city = esc(city.as_deref().unwrap_or("")),
    ))
}

pub async fn notify(State(hub): State<Shared>, Form(n): Form<Notify>) -> Redirect {
    if let Err(e) = hub.notify(n) {
        hub.log(format!("alert not sent: {e}"));
    }
    Redirect::to("/")
}

#[derive(Deserialize)]
pub struct ProjectForm {
    name: String,
    status: String,
    #[serde(default)]
    note: String,
}

pub async fn project(State(hub): State<Shared>, Form(f): Form<ProjectForm>) -> Redirect {
    let update = projects::Update { name: f.name, status: f.status, note: f.note, progress: None };
    if let Err(e) = hub.update_project(update) {
        hub.log(format!("project not saved: {e}"));
    }
    Redirect::to("/")
}

pub async fn art_another(State(hub): State<Shared>) -> Redirect {
    let day = hub.art.lock().unwrap().as_ref().map(|a| a.day.clone());
    if let Some(day) = day {
        art::want(hub.clone(), day, true);
    }
    Redirect::to("/")
}

pub async fn place_add(State(hub): State<Shared>, Form(place): Form<Place>) -> Redirect {
    let valid = !place.name.trim().is_empty() && (-90.0..=90.0).contains(&place.lat) && (-180.0..=180.0).contains(&place.lon);
    if valid {
        let timezone = place.timezone.filter(|tz| !tz.is_empty());
        hub.config.write().unwrap().places.push(Place { name: place.name.trim().to_owned(), timezone, ..place });
        save(&hub);
    }
    Redirect::to("/")
}

#[derive(Deserialize)]
pub struct Index {
    index: usize,
}

pub async fn place_remove(State(hub): State<Shared>, Form(Index { index }): Form<Index>) -> Redirect {
    {
        let mut config = hub.config.write().unwrap();
        if index < config.places.len() {
            config.places.remove(index);
        }
    }
    save(&hub);
    Redirect::to("/")
}

fn save(hub: &Shared) {
    if let Err(e) = hub.config_changed() {
        hub.log(format!("could not save config: {e}"));
    }
}
