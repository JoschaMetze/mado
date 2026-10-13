//! The web interface: status, a test alert, and all configuration. Server-rendered, no script.

use std::time::{SystemTime, UNIX_EPOCH};

use axum::{
    extract::{Multipart, Query, State},
    http::HeaderMap,
    response::{Html, Redirect},
    Form,
};
use base64::Engine;
use serde::Deserialize;
use serde_json::Value;

use crate::{art, projects, Night, Notify, Place, Shared};

const STYLE: &str = include_str!("ui.css");
/// How often the picture of the glass reloads itself. Each reload asks the frame for a screenshot.
const GLASS_REFRESH_S: u32 = 30;
/// How many log lines the page shows before the rest are folded away.
const RECENT_SHOWN: usize = 6;
/// The looks the page can wear: the value saved in the config, and its name on the switch.
/// The first is the default.
const THEMES: [(&str, &str); 2] = [("gallery", "Gallery"), ("workbench", "Workbench")];

fn esc(s: &str) -> String {
    s.replace('&', "&amp;")
        .replace('<', "&lt;")
        .replace('>', "&gt;")
        .replace('"', "&quot;")
}

fn ago(secs: u64) -> String {
    match secs {
        0..=59 => format!("{secs}s ago"),
        60..=3599 => format!("{}m ago", secs / 60),
        3600..=172_799 => format!("{}h ago", secs / 3600),
        _ => format!("{}d ago", secs / 86_400),
    }
}

fn clock(secs: f64) -> String {
    let secs = secs.max(0.0) as u64;
    format!("{}:{:02}", secs / 60, secs % 60)
}

/// A colour from the painting's palette, or the fallback if it is not a plain `#rrggbb`.
fn colour<'a>(v: &'a Value, fallback: &'a str) -> &'a str {
    v.as_str()
        .filter(|s| s.len() == 7 && s.starts_with('#') && s[1..].bytes().all(|b| b.is_ascii_hexdigit()))
        .unwrap_or(fallback)
}

/// Where a form post sends the browser: back to the page, scrolled to the card that was
/// used, with a line saying what happened.
fn back_url(card: &str, outcome: Result<&str, String>) -> String {
    let (key, text) = match &outcome {
        Ok(text) => ("ok", *text),
        Err(text) => ("err", text.as_str()),
    };
    let mut url = format!("/?{key}=");
    for b in text.bytes() {
        match b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' => url.push(b as char),
            _ => url.push_str(&format!("%{b:02X}")),
        }
    }
    format!("{url}#{card}")
}

fn back(card: &str, outcome: Result<&str, String>) -> Redirect {
    Redirect::to(&back_url(card, outcome))
}

/// Which coloured dot a log line gets.
fn log_kind(line: &str) -> &'static str {
    let starts = |p: &str| line.starts_with(p);
    if line.contains(" failed") || line.contains(" not ") {
        "bad"
    } else if starts("urgent ") {
        "urgent"
    } else if starts("nudge ") {
        "nudge"
    } else if starts("whisper ") {
        "whisper"
    } else if starts("frame ") {
        "frame"
    } else if starts("project ") {
        "project"
    } else if starts("now playing") {
        "music"
    } else if starts("art ") {
        "art"
    } else {
        "other"
    }
}

#[derive(Deserialize)]
pub struct Search {
    city: Option<String>,
    ok: Option<String>,
    err: Option<String>,
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

async fn city_matches(name: String) -> String {
    match tokio::task::spawn_blocking(move || find_cities(&name)).await {
        Ok(Ok(found)) if found.is_empty() => {
            r#"<p class="muted">No city by that name. Try a different spelling.</p>"#.to_owned()
        }
        Ok(Ok(found)) => {
            let rows: String = found
                .iter()
                .map(|c| {
                    let name = c["name"].as_str().unwrap_or("");
                    let region: Vec<&str> = [c["admin1"].as_str(), c["country"].as_str()]
                        .into_iter()
                        .flatten()
                        .filter(|s| *s != name)
                        .collect();
                    format!(
                        r#"<li><span><b>{}</b> <span class="muted">{}</span></span>
<form method="post" action="/ui/places/add"><input type="hidden" name="name" value="{}"><input type="hidden" name="lat" value="{}"><input type="hidden" name="lon" value="{}"><input type="hidden" name="timezone" value="{}"><button>Add</button></form></li>"#,
                        esc(name),
                        esc(&region.join(", ")),
                        esc(name),
                        c["latitude"].as_f64().unwrap_or(0.0),
                        c["longitude"].as_f64().unwrap_or(0.0),
                        esc(c["timezone"].as_str().unwrap_or("")),
                    )
                })
                .collect();
            format!(r#"<ul class="found">{rows}</ul>"#)
        }
        _ => r#"<p class="muted">The city search is unreachable right now. You can still add a place by coordinates.</p>"#
            .to_owned(),
    }
}

fn status_options(selected: &str) -> String {
    projects::STATUSES
        .iter()
        .map(|s| {
            format!(
                r#"<option value="{s}"{}>{}</option>"#,
                if *s == selected { " selected" } else { "" },
                s.replace('_', " ")
            )
        })
        .collect()
}

fn project_rows(items: &Value) -> String {
    let now = SystemTime::now().duration_since(UNIX_EPOCH).map_or(0, |d| d.as_secs());
    let rows: String = items
        .as_array()
        .into_iter()
        .flatten()
        .map(|p| {
            let name = esc(p["name"].as_str().unwrap_or(""));
            let status = p["status"].as_str().unwrap_or("");
            let note = esc(p["note"].as_str().unwrap_or(""));
            let percent = p["progress"].as_f64().map(|f| (f * 100.0).round());
            let bar = percent.map_or(String::new(), |pc| {
                format!(r#"<div class="bar" title="{pc}%"><i style="width:{pc}%"></i></div>"#)
            });
            // The icon's name is the hub's own, but the page trusts nothing it did not write.
            let key = p["icon"].as_str().filter(|k| k.bytes().all(|b| b.is_ascii_hexdigit()));
            let icon = match key {
                Some(key) => format!(r#"<img class="icon" src="/icons/{key}.png" alt="">"#),
                None => format!(
                    r#"<span class="icon" style="background:{}">{}</span>"#,
                    colour(&p["tint"], "#5b6876"),
                    esc(p["initials"].as_str().unwrap_or(""))
                ),
            };
            let no_icon = if key.is_some() {
                r#"<label class="check"><input type="checkbox" name="no_icon" value="1">Go back to initials</label>"#
            } else {
                ""
            };
            let note_line = if note.is_empty() {
                String::new()
            } else {
                format!(r#"<p class="muted">{note}</p>"#)
            };
            format!(
                r#"<li class="project"><div class="line">{icon}<b class="grow">{name}</b><span class="badge {class}">{label}</span></div>
{note_line}{bar}
<details><summary>Updated {updated} · edit</summary><form method="post" action="/ui/projects" enctype="multipart/form-data"><input type="hidden" name="name" value="{name}">
<div class="row"><label>Status<select name="status">{options}</select></label>
<label>Progress, %<input name="progress" type="number" min="0" max="100" value="{percent}" placeholder="none"></label></div>
<label>Note<input name="note" maxlength="120" value="{note}"></label>
<label>Icon<input name="icon" type="file" accept="image/png,image/jpeg"></label>{no_icon}
<div class="actions"><button>Save</button><button class="quiet" name="remove" value="yes">Remove from the dashboard</button></div></form></details></li>"#,
                class = if projects::STATUSES.contains(&status) { status } else { "plain" },
                label = esc(&status.replace('_', " ")),
                updated = ago(now.saturating_sub(p["updated"].as_u64().unwrap_or(now))),
                options = status_options(status),
                percent = percent.map_or(String::new(), |pc| pc.to_string()),
            )
        })
        .collect();
    if rows.is_empty() {
        r#"<li class="muted">No projects yet. Agents add them with <code>frame_project</code>.</li>"#.to_owned()
    } else {
        rows
    }
}

fn place_rows(places: &[Place]) -> String {
    let rows: String = places
        .iter()
        .enumerate()
        .map(|(i, p)| {
            let role = if i == 0 {
                r#"<span class="badge needs_you">here</span>"#.to_owned()
            } else {
                format!(
                    r#"<form method="post" action="/ui/places/here"><input type="hidden" name="index" value="{i}"><button class="quiet">Make this here</button></form>"#
                )
            };
            format!(
                r#"<li><div class="line"><form class="rename" method="post" action="/ui/places/rename"><input type="hidden" name="index" value="{i}">
<input name="name" value="{}" maxlength="40" required aria-label="Name shown on the frame"><button class="quiet">Rename</button></form>{role}</div>
<div class="line"><span class="muted small grow">{:.2}, {:.2}{}</span>
<form method="post" action="/ui/places/remove"><input type="hidden" name="index" value="{i}"><button class="quiet">Remove</button></form></div></li>"#,
                esc(&p.name),
                p.lat,
                p.lon,
                p.timezone.as_deref().map_or(String::new(), |tz| format!(" · {}", esc(tz))),
            )
        })
        .collect();
    if rows.is_empty() {
        r#"<li class="muted">No places yet. Find a city below.</li>"#.to_owned()
    } else {
        rows
    }
}

fn now_playing_card(np: &Value) -> String {
    let title = np["title"].as_str().unwrap_or("");
    if np["state"] == "stopped" || title.is_empty() {
        return String::new();
    }
    let (backdrop, cover) = match np["art"].as_u64() {
        Some(v) => (
            format!(r#"<img class="back" src="/art/{v}/backdrop.png" alt="">"#),
            format!(r#"<img class="cover" src="/art/{v}/cover.jpg" alt="">"#),
        ),
        None => (String::new(), r#"<div class="cover"></div>"#.to_owned()),
    };
    let (position, duration) = (
        np["position_s"].as_f64().unwrap_or(0.0),
        np["duration_s"].as_f64().unwrap_or(0.0),
    );
    let progress = if duration > 0.0 {
        format!(
            r#"<div class="bar" title="{} of {}"><i style="width:{:.1}%"></i></div>"#,
            clock(position),
            clock(duration),
            (position / duration * 100.0).clamp(0.0, 100.0)
        )
    } else {
        String::new()
    };
    let source = np["source"].as_str().filter(|s| !s.is_empty());
    format!(
        r#"<section class="np">{backdrop}{cover}<div><p class="eyebrow">{}{}</p><h3>{}</h3><p>{}</p>{progress}</div></section>"#,
        esc(np["state"].as_str().unwrap_or("")),
        source.map_or(String::new(), |s| format!(" · {}", esc(s))),
        esc(title),
        esc(np["artist"].as_str().unwrap_or("")),
    )
}

/// The choices for which paintings the museum is asked for.
fn taste_form(prefs: &art::Prefs, pool: Option<u64>) -> String {
    let options = |choices: &[(&str, &str, &[&str])], chosen: &str| -> String {
        choices
            .iter()
            .map(|(key, label, _)| {
                format!(
                    r#"<option value="{key}"{}>{label}</option>"#,
                    if *key == chosen { " selected" } else { "" }
                )
            })
            .collect()
    };
    let periods: String = art::PERIODS
        .iter()
        .map(|(key, label)| {
            format!(
                r#"<option value="{key}"{}>{label}</option>"#,
                if *key == prefs.period { " selected" } else { "" }
            )
        })
        .collect();
    let avoid: String = art::AVOIDABLE
        .iter()
        .map(|(key, label, _)| {
            format!(
                r#"<label class="check"><input type="checkbox" name="avoid_{key}" value="1"{}>{label}</label>"#,
                if prefs.avoid.iter().any(|a| a == key) {
                    " checked"
                } else {
                    ""
                }
            )
        })
        .collect();
    let pool = match pool {
        Some(n) => format!("Today's painting was chosen from {n} that fit."),
        None => String::new(),
    };
    format!(
        r#"<form class="taste wide" method="post" action="/ui/art/prefs">
<div class="head"><p class="eyebrow">What to show</p></div>
<div class="pick"><label>Period<select name="period">{periods}</select></label>
<label>Subject<select name="subject">{subjects}</select></label></div>
<div class="checks"><span class="muted small">Leave out</span>{avoid}</div>
<div class="actions"><button>Save and pick again</button><span class="muted small">{pool}</span></div>
</form>"#,
        subjects = options(&art::SUBJECTS, &prefs.subject),
    )
}

fn painting_card(art: &Value) -> String {
    if art.is_null() {
        return r#"<p class="muted wide">The frame asks for a painting when it connects. Nothing has been asked for yet.</p>"#
            .to_owned();
    }
    let palette = &art["palette"];
    let swatches: String = palette["swatches"]
        .as_array()
        .into_iter()
        .flatten()
        .map(|s| format!(r#"<i style="background:{}"></i>"#, colour(s, "#888888")))
        .collect();
    let facts: Vec<&str> = ["artist", "date", "medium"]
        .iter()
        .filter_map(|k| art[*k].as_str().filter(|s| !s.is_empty()))
        .collect();
    format!(
        r#"<div class="canvas" style="--wall:{wall}"><img src="/daily/{version}/image.jpg" alt="{title}"></div>
<div class="about"><div class="head"><p class="eyebrow">Art of the day · {day}</p><h3>{title}</h3><p class="muted">{facts}</p></div>
<p>{text}</p>
<div class="swatches" title="The colours the dashboard takes from this painting">{swatches}</div>
<div class="actions"><form method="post" action="/ui/art/another"><button>Another painting</button></form>
<a href="{url}" rel="noreferrer">{credit}</a></div></div>"#,
        wall = colour(&palette["wall"], "#2a2f36"),
        version = art["version"].as_u64().unwrap_or(0),
        day = esc(art["day"].as_str().unwrap_or("")),
        title = esc(art["title"].as_str().unwrap_or("")),
        facts = esc(&facts.join(" · ")),
        text = esc(art["text"].as_str().unwrap_or("")),
        url = esc(art["url"]
            .as_str()
            .filter(|u| u.starts_with("https://"))
            .unwrap_or("https://www.artic.edu")),
        credit = esc(art["credit"].as_str().unwrap_or("Art Institute of Chicago")),
    )
}

pub async fn index(State(hub): State<Shared>, headers: HeaderMap, Query(search): Query<Search>) -> Html<String> {
    let header = |name: &str| headers.get(name).and_then(|h| h.to_str().ok());
    let host = header("host").unwrap_or("HUB_ADDRESS:7878");
    // Behind a reverse proxy the page is usually https; say so in the addresses it prints.
    let scheme = header("x-forwarded-proto").filter(|p| *p == "https").unwrap_or("http");

    let city = search
        .city
        .as_deref()
        .map(str::trim)
        .filter(|c| !c.is_empty())
        .map(str::to_owned);
    let matches = match city.clone() {
        Some(name) => city_matches(name).await,
        None => String::new(),
    };
    let flash = match (&search.ok, &search.err) {
        (_, Some(e)) => format!(r#"<p class="flash err" role="alert">{}</p>"#, esc(e)),
        (Some(ok), _) => format!(r#"<p class="flash" role="status">{}</p>"#, esc(ok)),
        _ => String::new(),
    };

    let (pill, glass_note) = {
        let frames = hub.frames.lock().unwrap();
        match frames.values().next() {
            Some(f) => (
                format!(
                    r#"<span class="pill up"><i class="dot"></i>{} · connected {}{}</span>"#,
                    esc(f.hello["model"].as_str().unwrap_or("Frame")),
                    ago(f.since.elapsed().as_secs()),
                    if frames.len() > 1 {
                        format!(" · {} frames", frames.len())
                    } else {
                        String::new()
                    },
                ),
                format!(
                    r#"<span>What the frame is showing, refreshed every {GLASS_REFRESH_S} seconds.</span><a href="/api/look">Full size</a>"#
                ),
            ),
            None => (
                r#"<span class="pill"><i class="dot"></i>No frame connected</span>"#.to_owned(),
                r##"<span>Waiting for a frame. <a href="#connect">How to point one at this hub</a></span>"##.to_owned(),
            ),
        }
    };

    let (places, theme, night) = {
        let config = hub.config.read().unwrap();
        let theme = THEMES.iter().find(|t| t.0 == config.theme).unwrap_or(&THEMES[0]).0;
        (place_rows(&config.places), theme, config.night.clone())
    };
    let night_off = if night.off { " checked" } else { "" };
    let (night_from, night_to) = (esc(&night.from), esc(&night.to));
    // What the frame last said, so the button offers the other thing.
    let (night_now, night_button) = match *hub.asleep.lock().unwrap() {
        Some(true) => (
            "The frame is asleep.",
            r#"<button name="asleep" value="false" class="quiet">Wake it now</button>"#,
        ),
        Some(false) => (
            "The frame is awake.",
            r#"<button name="asleep" value="true" class="quiet">Sleep now</button>"#,
        ),
        None => (
            "",
            r#"<button name="asleep" value="true" class="quiet">Sleep now</button> <button name="asleep" value="false" class="quiet">Wake now</button>"#,
        ),
    };
    let themes: String = THEMES
        .iter()
        .map(|(value, name)| {
            format!(
                r#"<button name="theme" value="{value}" aria-pressed="{}">{name}</button>"#,
                *value == theme
            )
        })
        .collect();
    let state = hub.state();
    let np = now_playing_card(&state["now_playing"]);
    let projects = project_rows(&state["projects"]);
    let painting = painting_card(&state["art"]);
    let taste = taste_form(&hub.config.read().unwrap().art, state["art"]["pool"].as_u64());
    let palette = &state["art"]["palette"];
    let tint = format!(
        "--p1:{};--p2:{};--p3:{}",
        colour(&palette["accent"], "#c2376b"),
        colour(&palette["swatches"][1], "#5b6876"),
        colour(&palette["swatches"][2], "#1f9d6b"),
    );

    let lines: Vec<String> = hub
        .log
        .lock()
        .unwrap()
        .iter()
        .map(|e| {
            format!(
                r#"<li class="k-{}"><i class="dot"></i><time>{}</time><span>{}</span></li>"#,
                log_kind(&e.line),
                ago(e.at.elapsed().as_secs()),
                esc(&e.line)
            )
        })
        .collect();
    // The newest few are always shown; the rest wait behind a disclosure.
    let (latest, earlier) = lines.split_at(lines.len().min(RECENT_SHOWN));
    let log = match (latest.is_empty(), earlier.is_empty()) {
        (true, _) => {
            r#"<ul class="log"><li class="muted">Nothing has happened since the hub started.</li></ul>"#.to_owned()
        }
        (false, true) => format!(r#"<ul class="log">{}</ul>"#, latest.concat()),
        (false, false) => format!(
            r#"<ul class="log">{}</ul><details><summary>{} earlier</summary><ul class="log">{}</ul></details>"#,
            latest.concat(),
            earlier.len(),
            earlier.concat()
        ),
    };

    Html(format!(
        r##"<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Mado hub</title><link rel="icon" href="/icon.svg" type="image/svg+xml"><style>{STYLE}</style>
<body class="t-{theme}" style="{tint}"><div class="wash"></div>
<main>
<header class="top"><a class="brand" href="/"><b lang="ja" aria-hidden="true">窓</b><h1>Mado</h1></a><span class="tag">hub · {version}</span><span class="grow"></span>{pill}
<form class="themes" method="post" action="/ui/theme" aria-label="How this page looks">{themes}</form></header>
{flash}
<div class="hero">
  <div class="stage">
    <div class="device"><iframe src="/ui/glass" title="What the frame is showing now"></iframe></div>
    <p class="caption muted small">{glass_note}</p>
  </div>
  <div class="side">
    <section id="alert"><div class="head"><p class="eyebrow">Say something</p><h2>Send an alert</h2></div>
      <form method="post" action="/ui/notify">
        <label>Message<textarea name="text" maxlength="280" required placeholder="Kettle's boiled"></textarea></label>
        <fieldset class="levels"><legend>How loudly</legend>
          <label class="whisper"><input type="radio" name="level" value="whisper"><span><i></i>Whisper<small>a bead at the edge</small></span></label>
          <label class="nudge"><input type="radio" name="level" value="nudge" checked><span><i></i>Nudge<small>one line, then gone</small></span></label>
          <label class="urgent"><input type="radio" name="level" value="urgent"><span><i></i>Urgent<small>stays until tapped</small></span></label>
        </fieldset>
        <label>From<input name="source" maxlength="40" placeholder="you"></label>
        <button>Send to the frame</button>
      </form>
    </section>
    {np}
  </div>
</div>
<div class="grid">
  <section class="art wide" id="art">{painting}{taste}</section>
  <section id="projects"><div class="head"><p class="eyebrow">On the dashboard</p><h2>Projects</h2></div>
    <ul class="rows">{projects}</ul>
    <details><summary>Add a project</summary>
    <form method="post" action="/ui/projects" enctype="multipart/form-data">
      <div class="row"><label>Name<input name="name" maxlength="40" required></label>
      <label>Status<select name="status">{statuses}</select></label></div>
      <label>Note<input name="note" maxlength="120"></label>
      <label>Icon, a PNG or JPEG; without one it gets its initials<input name="icon" type="file" accept="image/png,image/jpeg"></label>
      <button>Add project</button>
    </form></details>
  </section>
  <section id="places"><div class="head"><p class="eyebrow">Weather</p><h2>Places</h2></div>
    <ul class="rows">{places}</ul>
    <form method="get" action="/#places">
      <label>Find a city<input name="city" value="{city}" required placeholder="Kamakura"></label>
      <button>Search</button>
    </form>
    {matches}
    <details><summary>Add by coordinates instead</summary>
    <form method="post" action="/ui/places/add">
      <label>Name<input name="name" maxlength="40" required placeholder="Mum &amp; Dad"></label>
      <div class="row">
        <label>Latitude<input name="lat" type="number" step="any" min="-90" max="90" required></label>
        <label>Longitude<input name="lon" type="number" step="any" min="-180" max="180" required></label>
      </div>
      <button>Add place</button>
    </form></details>
    <p class="muted small">"Here" gets the full forecast on the dashboard; the others get a line each with their local time. The frame fetches the weather itself.</p>
  </section>
  <section id="night"><div class="head"><p class="eyebrow">After dark</p><h2>Night</h2></div>
    <form method="post" action="/ui/night">
      <div class="row"><label>Sleeps from<input name="from" type="time" value="{night_from}" required></label>
      <label>Wakes at<input name="to" type="time" value="{night_to}" required></label></div>
      <label class="check"><input type="checkbox" name="off"{night_off}> Stay awake all night</label>
      <button>Save hours</button>
    </form>
    <form method="post" action="/ui/sleep"><p>{night_now} {night_button}</p></form>
    <p class="muted small">Asleep, the frame shows a dim amber clock and keeps whispers and nudges for the morning; urgent alerts still show. A touch or two knocks wakes it for a moment. It also turns itself down between sunset and sunrise.</p>
  </section>
  <section id="recent"><div class="head"><p class="eyebrow">Since the hub started</p><h2>Recent</h2></div>
    {log}
  </section>
  <section id="connect"><div class="head"><p class="eyebrow">Plug things in</p><h2>Connect</h2></div>
    <p>Agents speak MCP at <code>{scheme}://{host}/mcp</code></p>
    <label>Anything with curl<pre>curl {scheme}://{host}/api/notify \
  -H 'content-type: application/json' \
  -d '{{"text":"Deploy finished","level":"nudge","source":"ci"}}'</pre></label>
    <label>Point a frame at this hub, once, over USB<pre>adb shell am start -n jp.co.xenocode.mado/.MainActivity \
  --es hub ws://HUB_ADDRESS:PORT/frame</pre></label>
    <p class="muted small">The frame needs an address it can reach on its own network, which may differ from the one in your browser. State as JSON: <a href="/api/state">/api/state</a></p>
  </section>
</div>
</main></body></html>"##,
        version = env!("CARGO_PKG_VERSION"),
        host = esc(host),
        city = esc(city.as_deref().unwrap_or("")),
        statuses = status_options("on_track"),
    ))
}

/// The picture inside the frame on the page. It is its own small document so it can reload
/// on a timer without a script and without disturbing a form someone is filling in.
pub async fn glass(State(hub): State<Shared>) -> Html<String> {
    let body = match hub.look().await {
        Ok(jpeg) => format!(
            r#"<img src="data:image/jpeg;base64,{}" alt="What the frame is showing now">"#,
            base64::engine::general_purpose::STANDARD.encode(jpeg)
        ),
        Err(e) => format!(r#"<p><b lang="ja">窓</b>{}</p>"#, esc(&e)),
    };
    Html(format!(
        r#"<!doctype html><html lang="en"><meta charset="utf-8"><meta http-equiv="refresh" content="{GLASS_REFRESH_S}"><title>The glass</title>
<style>html,body{{height:100%;margin:0;background:#000;color:#5b6876;font:14px system-ui,sans-serif;overflow:hidden}}
img{{display:block;width:100%;height:100%;object-fit:cover}}
p{{height:100%;margin:0;display:grid;place-content:center;justify-items:center;gap:10px;text-align:center}}
b{{font-size:64px;line-height:1;font-weight:400;color:#27323d}}</style>{body}</html>"#
    ))
}

pub async fn notify(State(hub): State<Shared>, Form(n): Form<Notify>) -> Redirect {
    back("alert", hub.notify(n).map(|_| "Sent to the frame."))
}

#[derive(Default)]
struct ProjectForm {
    name: String,
    status: String,
    note: String,
    /// A percentage; empty means the project has no progress bar.
    progress: String,
    /// Set by the Remove button, which shares a form with Save.
    remove: bool,
    /// The picture chosen as the icon; empty when none was.
    icon: Vec<u8>,
    no_icon: bool,
}

/// The project forms carry a file, so they arrive in parts rather than as one query string.
async fn project_form(mut parts: Multipart) -> Result<ProjectForm, String> {
    const UNREADABLE: &str = "That could not be read. An icon can be up to 4 MB.";
    let mut f = ProjectForm::default();
    while let Some(part) = parts.next_field().await.map_err(|_| UNREADABLE)? {
        let name = part.name().unwrap_or("").to_owned();
        if name == "icon" {
            f.icon = part.bytes().await.map_err(|_| UNREADABLE)?.to_vec();
            continue;
        }
        let value = part.text().await.map_err(|_| UNREADABLE)?;
        match name.as_str() {
            "name" => f.name = value,
            "status" => f.status = value,
            "note" => f.note = value,
            "progress" => f.progress = value,
            "remove" => f.remove = true,
            "no_icon" => f.no_icon = true,
            _ => {}
        }
    }
    Ok(f)
}

pub async fn project(State(hub): State<Shared>, parts: Multipart) -> Redirect {
    let f = match project_form(parts).await {
        Ok(f) => f,
        Err(e) => return back("projects", Err(e)),
    };
    let (status, done) = match f.remove {
        true => (projects::ARCHIVED.to_owned(), "Project removed."),
        false => (f.status, "Project saved."),
    };
    let icon = if f.no_icon || f.remove {
        projects::Icon::Clear
    } else if f.icon.is_empty() {
        projects::Icon::Keep
    } else {
        match tokio::task::spawn_blocking(move || projects::prepare_icon(&f.icon)).await {
            Ok(Ok(png)) => projects::Icon::Set(png),
            _ => return back("projects", Err("The icon must be a PNG or JPEG.".into())),
        }
    };
    let update = projects::Update {
        name: f.name,
        status,
        note: f.note,
        progress: f.progress.trim().parse::<f64>().ok().map(|percent| percent / 100.0),
        icon: None,
    };
    back("projects", hub.update_project(update, icon).map(|_| done))
}

pub async fn art_another(State(hub): State<Shared>) -> Redirect {
    let day = hub.art.lock().unwrap().as_ref().map(|a| a.day.clone());
    match day {
        Some(day) => {
            art::want(hub.clone(), day, true);
            back(
                "art",
                Ok("Asking the museum for another painting. Reload in a few seconds to see it."),
            )
        }
        None => back("art", Err("There is no painting to replace yet.".into())),
    }
}

#[derive(Deserialize)]
pub struct Taste {
    #[serde(default)]
    period: String,
    #[serde(default)]
    subject: String,
    avoid_religious: Option<String>,
    avoid_nudes: Option<String>,
    avoid_death: Option<String>,
}

/// Save what kinds of painting to show, then replace today's with one that fits.
pub async fn art_prefs(State(hub): State<Shared>, Form(t): Form<Taste>) -> Redirect {
    let avoid = [
        ("religious", t.avoid_religious),
        ("nudes", t.avoid_nudes),
        ("death", t.avoid_death),
    ]
    .into_iter()
    .filter(|(_, ticked)| ticked.is_some())
    .map(|(key, _)| key.to_owned())
    .collect();
    let prefs = match art::Prefs::chosen(&t.period, &t.subject, avoid) {
        Ok(prefs) => prefs,
        Err(e) => return back("art", Err(e)),
    };
    hub.config.write().unwrap().art = prefs;
    if let Err(e) = hub.config_changed() {
        return back("art", Err(format!("Could not save: {e}")));
    }
    let day = hub.art.lock().unwrap().as_ref().map(|a| a.day.clone());
    match day {
        Some(day) => {
            art::want(hub.clone(), day, true);
            back(
                "art",
                Ok("Saved. Asking the museum for a painting that fits; reload in a few seconds."),
            )
        }
        None => back("art", Ok("Saved. It applies from the next painting.")),
    }
}

#[derive(Deserialize)]
pub struct Theme {
    theme: String,
}

pub async fn theme(State(hub): State<Shared>, Form(Theme { theme }): Form<Theme>) -> Redirect {
    if !THEMES.iter().any(|t| t.0 == theme) {
        return back("", Err("There is no theme by that name.".into()));
    }
    hub.config.write().unwrap().theme = theme;
    if let Err(e) = hub.config_changed() {
        hub.log(format!("could not save config: {e}"));
    }
    Redirect::to("/")
}

#[derive(Deserialize)]
pub struct NightForm {
    from: String,
    to: String,
    off: Option<String>,
}

pub async fn night(State(hub): State<Shared>, Form(f): Form<NightForm>) -> Redirect {
    if !Night::is_time(&f.from) || !Night::is_time(&f.to) {
        return back("night", Err("Those hours need to be times of day, like 23:00.".into()));
    }
    hub.config.write().unwrap().night = Night {
        from: f.from,
        to: f.to,
        off: f.off.is_some(),
    };
    match hub.config_changed() {
        Ok(()) => back("night", Ok("Night hours saved.")),
        Err(e) => back("night", Err(format!("Changed, but not saved to disk: {e}"))),
    }
}

#[derive(Deserialize)]
pub struct SleepForm {
    asleep: bool,
}

pub async fn sleep(State(hub): State<Shared>, Form(SleepForm { asleep }): Form<SleepForm>) -> Redirect {
    let done = if asleep {
        "Asked the frame to sleep."
    } else {
        "Asked the frame to wake."
    };
    back("night", hub.sleep(asleep, Some("the hub page")).map(|_| done))
}

pub async fn place_add(State(hub): State<Shared>, Form(place): Form<Place>) -> Redirect {
    let valid =
        !place.name.trim().is_empty() && (-90.0..=90.0).contains(&place.lat) && (-180.0..=180.0).contains(&place.lon);
    if !valid {
        return back("places", Err("That place needs a name and real coordinates.".into()));
    }
    let timezone = place.timezone.filter(|tz| !tz.is_empty());
    hub.config.write().unwrap().places.push(Place {
        name: place.name.trim().chars().take(40).collect(),
        timezone,
        ..place
    });
    saved(&hub, "Place added.")
}

#[derive(Deserialize)]
pub struct Index {
    index: usize,
}

#[derive(Deserialize)]
pub struct Rename {
    index: usize,
    name: String,
}

/// The name is what the frame shows, so a city can be labelled "Mum & Dad".
pub async fn place_rename(State(hub): State<Shared>, Form(r): Form<Rename>) -> Redirect {
    let name: String = r.name.trim().chars().take(40).collect();
    let renamed = !name.is_empty()
        && hub
            .config
            .write()
            .unwrap()
            .places
            .get_mut(r.index)
            .map(|place| place.name = name)
            .is_some();
    if !renamed {
        return back("places", Err("That place could not be renamed.".into()));
    }
    saved(&hub, "Place renamed.")
}

/// The first place is "here", so moving a place to the front makes it here.
pub async fn place_here(State(hub): State<Shared>, Form(Index { index }): Form<Index>) -> Redirect {
    {
        let mut config = hub.config.write().unwrap();
        if index >= config.places.len() {
            return back("places", Err("That place is no longer in the list.".into()));
        }
        let place = config.places.remove(index);
        config.places.insert(0, place);
    }
    saved(&hub, "The frame's \"here\" has moved.")
}

pub async fn place_remove(State(hub): State<Shared>, Form(Index { index }): Form<Index>) -> Redirect {
    {
        let mut config = hub.config.write().unwrap();
        if index >= config.places.len() {
            return back("places", Err("That place is no longer in the list.".into()));
        }
        config.places.remove(index);
    }
    saved(&hub, "Place removed.")
}

/// Save the places and tell the frame, then go back to the places card.
fn saved(hub: &Shared, done: &str) -> Redirect {
    match hub.config_changed() {
        Ok(()) => back("places", Ok(done)),
        Err(e) => {
            hub.log(format!("could not save config: {e}"));
            back("places", Err(format!("Changed, but not saved to disk: {e}")))
        }
    }
}

/// The app icon, as the page's favicon.
pub async fn icon() -> impl axum::response::IntoResponse {
    (
        [
            (axum::http::header::CONTENT_TYPE, "image/svg+xml"),
            (axum::http::header::CACHE_CONTROL, "max-age=86400"),
        ],
        include_str!("icon.svg"),
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_way_back_carries_the_outcome_safely() {
        assert_eq!(back_url("alert", Ok("Sent.")), "/?ok=Sent%2E#alert");
        assert_eq!(
            back_url("places", Err("a&b=c #d é".into())),
            "/?err=a%26b%3Dc%20%23d%20%C3%A9#places"
        );
    }

    #[test]
    fn only_plain_hex_colours_reach_a_style_attribute() {
        let fallback = "#000000";
        assert_eq!(colour(&Value::from("#a7916f"), fallback), "#a7916f");
        assert_eq!(colour(&Value::from("red;background:url(x)"), fallback), fallback);
        assert_eq!(colour(&Value::from("#a7916"), fallback), fallback);
        assert_eq!(colour(&Value::Null, fallback), fallback);
    }

    #[test]
    fn log_lines_are_sorted_into_kinds() {
        assert_eq!(log_kind("urgent from ci: disk full"), "urgent");
        assert_eq!(log_kind("frame #3 connected"), "frame");
        assert_eq!(log_kind("art of the day failed: timeout"), "bad");
        assert_eq!(log_kind("now playing: helper went quiet"), "music");
    }
}
