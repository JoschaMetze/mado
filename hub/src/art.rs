//! Art of the day: one work per day from the sources the owner picked, prepared for the
//! frame with a palette the dashboard takes its colours from. Left alone it is a
//! public-domain painting from the Art Institute of Chicago's open API.
//!
//! The frame says which day it is (it knows the local date; the hub may be in another
//! timezone), and the same day always yields the same painting unless someone asks for another.

use std::{
    hash::{DefaultHasher, Hash, Hasher},
    io::{Cursor, Read},
    path::PathBuf,
    sync::Arc,
    time::Duration,
};

use axum::{
    body::Bytes,
    extract::{Path, State},
    http::{header, StatusCode},
    response::{IntoResponse, Response},
};
use image::{codecs::jpeg::JpegEncoder, imageops::FilterType, DynamicImage, GrayImage, ImageFormat, Luma};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};

use crate::Shared;

const API: &str = "https://api.artic.edu/api/v1/search";
const IIIF: &str = "https://www.artic.edu/iiif/2";
const CLEVELAND: &str = "https://openaccess-api.clevelandart.org/api/artworks/";
const WIKIART: &str = "https://www.wikiart.org/en";
/// WikiArt has no search to hand the choices to, so works are looked up one at a time until
/// one fits, and what is learnt is kept. This is how many new lookups a day's pick may cost.
const WIKIART_LOOKUPS: usize = 40;
/// The Art Institute asks API users to identify themselves.
const AGENT: &str = "mado-hub (open-source photo-frame project)";
const MAX_IMAGE_BYTES: u64 = 20 << 20;
const TEXT_CHARS: usize = 460;

/// Where the art of the day can come from: key and label. None chosen means the first.
pub const SOURCES: [(&str, &str); 3] = [
    ("artic", "Art Institute of Chicago"),
    ("cleveland", "Cleveland Museum of Art"),
    ("wikiart", "WikiArt's most viewed (many still in copyright)"),
];

/// Kinds of work: key, label, and what the Art Institute and Cleveland call them.
/// None chosen means paintings. WikiArt only has paintings.
pub const KINDS: [(&str, &str, &str, &str); 4] = [
    ("painting", "Paintings", "Painting", "Painting"),
    ("print", "Prints", "Print", "Print"),
    (
        "drawing",
        "Drawings and watercolours",
        "Drawing and Watercolor",
        "Drawing",
    ),
    ("photo", "Photographs", "Photograph", "Photograph"),
];

/// Periods the owner can limit the paintings to: key and label.
pub const PERIODS: [(&str, &str); 4] = [
    ("", "Any period"),
    ("old", "Before 1800"),
    ("c19", "The 1800s"),
    ("modern", "1860 onwards"),
];

/// Subjects to show only: key, label, and the museum's subject tags that count.
pub const SUBJECTS: [(&str, &str, &[&str]); 8] = [
    ("", "Anything", &[]),
    ("landscapes", "Landscapes", &["landscapes", "landscape"]),
    (
        "water",
        "Sea and water",
        &["water", "sea", "seascapes", "boats", "rivers", "ocean"],
    ),
    ("portraits", "Portraits", &["portraits", "portrait"]),
    ("still_life", "Still lifes", &["still life", "still lifes"]),
    ("flowers", "Flowers", &["flowers"]),
    ("animals", "Animals", &["animals"]),
    (
        "city",
        "Towns and buildings",
        &[
            "architecture",
            "building",
            "buildings",
            "urban life",
            "cityscapes",
            "city",
        ],
    ),
];

/// The same subjects elsewhere: key, the word Cleveland is searched for, and WikiArt's genres.
const SUBJECTS_ELSEWHERE: [(&str, &str, &[&str]); 7] = [
    ("landscapes", "landscape", &["landscape", "cloudscape"]),
    ("water", "sea", &["marina"]),
    ("portraits", "portrait", &["portrait", "self-portrait"]),
    ("still_life", "still life", &["still life"]),
    ("flowers", "flowers", &["flower painting"]),
    ("animals", "animals", &["animal painting", "wildlife painting"]),
    ("city", "city", &["cityscape", "veduta", "architecture"]),
];

/// Subjects to leave out: key, label, and the museum's subject tags that rule a work out.
/// The tags are matched exactly, so each list carries the spellings the museum uses.
pub const AVOIDABLE: [(&str, &str, &[&str]); 3] = [
    (
        "religious",
        "Religious scenes",
        &[
            "Christian subjects",
            "Christianity",
            "religion",
            "religious",
            "saints",
            "religious figures",
            "biblical",
            "religious scenes",
            "Jesus",
            "Virgin Mary",
            "cross",
            "Crucifixion",
            "Madonna and Child",
            "angels",
            "Buddhism",
            "Buddha",
            "gods",
            "deities",
        ],
    ),
    (
        "nudes",
        "Nudes",
        &["nudes", "nude", "female nudes", "male nudes", "nudity"],
    ),
    (
        "death",
        "Death and war",
        &["death", "Mourning", "war", "violence", "battles", "martyrdom", "skulls"],
    ),
];

/// Not every religious painting is tagged as one, so its title is checked as well.
const RELIGIOUS_TITLE_WORDS: &str = "Christ Madonna Crucifixion Annunciation Virgin Saint Holy";

/// Where there are no subject tags to rule a work out by, these words in what is known
/// about it (title, genre, description) do: key and words, matched whole and in lower case.
const AVOID_WORDS: [(&str, &[&str]); 3] = [
    (
        "religious",
        &[
            "religious",
            "christ",
            "jesus",
            "madonna",
            "virgin",
            "crucifixion",
            "annunciation",
            "saint",
            "saints",
            "holy",
            "biblical",
            "apostle",
            "angel",
            "angels",
            "altarpiece",
            "buddha",
            "bodhisattva",
            "god",
            "goddess",
            "deity",
        ],
    ),
    ("nudes", &["nude", "nudes", "naked", "nu"]),
    (
        "death",
        &[
            "death",
            "dead",
            "war",
            "battle",
            "martyrdom",
            "skull",
            "skulls",
            "vanitas",
            "execution",
            "massacre",
        ],
    ),
];

/// What the owner wants the art of the day chosen from. Empty means anything.
#[derive(Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct Prefs {
    #[serde(default)]
    pub period: String,
    #[serde(default)]
    pub subject: String,
    #[serde(default)]
    pub avoid: Vec<String>,
    /// Where to look. Empty means the Art Institute of Chicago alone.
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub sources: Vec<String>,
    /// Kinds of work. Empty means paintings.
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub kinds: Vec<String>,
    /// Let the Art Institute send works still in copyright, which it serves smaller.
    /// For a frame at home; leave it off for anything shown in public.
    #[serde(default, skip_serializing_if = "std::ops::Not::not")]
    pub copyrighted: bool,
}

impl Prefs {
    pub fn is_any(&self) -> bool {
        *self == Prefs::default()
    }

    /// Build from what a form sent, refusing anything that is not one of the choices.
    pub fn chosen(period: &str, subject: &str, avoid: Vec<String>) -> Result<Prefs, String> {
        if !PERIODS.iter().any(|p| p.0 == period) {
            return Err("There is no such period.".into());
        }
        if !SUBJECTS.iter().any(|s| s.0 == subject) {
            return Err("There is no such subject.".into());
        }
        if avoid.iter().any(|a| !AVOIDABLE.iter().any(|known| known.0 == a)) {
            return Err("There is no such thing to leave out.".into());
        }
        Ok(Prefs {
            period: period.to_owned(),
            subject: subject.to_owned(),
            avoid,
            ..Prefs::default()
        })
    }

    /// Add where to look and for what kinds of work, refusing anything that is not a choice.
    pub fn from(mut self, sources: Vec<String>, kinds: Vec<String>, copyrighted: bool) -> Result<Prefs, String> {
        if sources.iter().any(|s| !SOURCES.iter().any(|known| known.0 == s)) {
            return Err("There is no such source.".into());
        }
        if kinds.iter().any(|k| !KINDS.iter().any(|known| known.0 == k)) {
            return Err("There is no such kind of work.".into());
        }
        (self.sources, self.kinds, self.copyrighted) = (sources, kinds, copyrighted);
        Ok(self)
    }

    fn sources(&self) -> Vec<&str> {
        match self.sources.is_empty() {
            true => vec![SOURCES[0].0],
            false => self.sources.iter().map(String::as_str).collect(),
        }
    }

    /// The chosen kinds of work, as rows of `KINDS`.
    fn kinds(&self) -> Vec<&'static (&'static str, &'static str, &'static str, &'static str)> {
        let chosen: Vec<_> = KINDS.iter().filter(|k| self.kinds.iter().any(|c| c == k.0)).collect();
        match chosen.is_empty() {
            true => vec![&KINDS[0]],
            false => chosen,
        }
    }

    fn fits_year(&self, year: i64) -> bool {
        match self.period.as_str() {
            "old" => year <= 1799,
            "c19" => (1800..=1899).contains(&year),
            "modern" => year >= 1860,
            _ => true,
        }
    }

    /// Whether any of the words describing a work is one the owner wants left out.
    fn avoids(&self, described: &str) -> bool {
        let described = described.to_lowercase();
        let mut words = described.split(|c: char| !c.is_alphanumeric());
        words.any(|word| {
            AVOID_WORDS
                .iter()
                .any(|(key, avoided)| self.avoid.iter().any(|a| a == key) && avoided.contains(&word))
        })
    }

    /// The search clauses these choices add: what a work must match, and what rules it out.
    fn clauses(&self) -> (Vec<Value>, Vec<Value>) {
        let mut filter = Vec::new();
        let mut must_not = Vec::new();
        match self.period.as_str() {
            "old" => filter.push(json!({ "range": { "date_end": { "lte": 1799 } } })),
            "c19" => {
                filter.push(json!({ "range": { "date_start": { "gte": 1800 } } }));
                filter.push(json!({ "range": { "date_end": { "lte": 1899 } } }));
            }
            "modern" => filter.push(json!({ "range": { "date_start": { "gte": 1860 } } })),
            _ => {}
        }
        if let Some((_, _, tags)) = SUBJECTS.iter().find(|s| s.0 == self.subject && !s.2.is_empty()) {
            filter.push(json!({ "terms": { "subject_titles.keyword": tags } }));
        }
        for (key, _, tags) in AVOIDABLE
            .iter()
            .filter(|a| self.avoid.iter().any(|chosen| chosen == a.0))
        {
            must_not.push(json!({ "terms": { "subject_titles.keyword": tags } }));
            if *key == "religious" {
                must_not.push(json!({ "match": { "title": RELIGIOUS_TITLE_WORDS } }));
            }
        }
        (filter, must_not)
    }
}

pub struct Daily {
    pub version: u64,
    pub day: String,
    /// Reroll counter: 0 is the day's own painting, each "another" adds one.
    pub salt: u64,
    pub meta: Value,
    image: Bytes,
    qr: Bytes,
}

impl Daily {
    pub fn message(&self) -> Value {
        let mut msg = self.meta.clone();
        msg["t"] = json!("art");
        msg["version"] = json!(self.version);
        msg["day"] = json!(self.day);
        msg
    }
}

fn cache(hub: &Shared, day: &str) -> (PathBuf, PathBuf) {
    let dir = hub.data_dir.join("art");
    (dir.join(format!("{day}.json")), dir.join(format!("{day}.jpg")))
}

fn valid_day(day: &str) -> bool {
    day.len() == 10 && day.bytes().all(|b| b.is_ascii_digit() || b == b'-')
}

/// A frame asked for `day`'s painting. Answer from memory or disk, or go and get one.
pub fn want(hub: Shared, day: String, another: bool) {
    if !valid_day(&day) {
        return;
    }
    let current = hub.art.lock().unwrap().clone();
    let mut salt = 0;
    if let Some(art) = current.filter(|a| a.day == day) {
        if !another {
            hub.send_all(&art.message());
            return;
        }
        salt = art.salt + 1;
    }
    if hub.art_busy.swap(true, std::sync::atomic::Ordering::SeqCst) {
        return;
    }
    tokio::spawn(async move {
        let worker = hub.clone();
        let wanted = day.clone();
        let found = tokio::task::spawn_blocking(move || load_or_fetch(&worker, &wanted, salt, another)).await;
        hub.art_busy.store(false, std::sync::atomic::Ordering::SeqCst);
        match found {
            Ok(Ok(art)) => {
                hub.log(format!(
                    "art of the day: {}",
                    art.meta["title"].as_str().unwrap_or("untitled")
                ));
                let art = Arc::new(art);
                hub.send_all(&art.message());
                *hub.art.lock().unwrap() = Some(art);
            }
            Ok(Err(e)) => hub.log(format!("art of the day failed: {e}")),
            Err(_) => hub.log("art of the day failed".into()),
        }
    });
}

fn load_or_fetch(hub: &Shared, day: &str, salt: u64, another: bool) -> Result<Daily, String> {
    let (meta_path, image_path) = cache(hub, day);
    let cached = (!another)
        .then(|| {
            let meta: Value = serde_json::from_slice(&std::fs::read(&meta_path).ok()?).ok()?;
            Some((meta, std::fs::read(&image_path).ok()?))
        })
        .flatten();
    let (meta, image, salt) = match cached {
        Some((meta, image)) => {
            let salt = meta["salt"].as_u64().unwrap_or(0);
            (meta, image, salt)
        }
        None => {
            let prefs = hub.config.read().unwrap().art.clone();
            let (mut meta, image) = fetch(&hub.data_dir.join("art"), day, salt, &prefs)?;
            meta["salt"] = json!(salt);
            if let Some(dir) = meta_path.parent() {
                let _ = std::fs::create_dir_all(dir);
            }
            let _ = std::fs::write(&meta_path, serde_json::to_vec_pretty(&meta).unwrap());
            let _ = std::fs::write(&image_path, &image);
            (meta, image, salt)
        }
    };
    let qr = qr_png(meta["url"].as_str().unwrap_or("https://www.artic.edu"))?;
    Ok(Daily {
        version: hub.id(),
        day: day.to_owned(),
        salt,
        meta,
        image: image.into(),
        qr: qr.into(),
    })
}

/// A work a source offers, before its picture has been fetched.
struct Work {
    title: String,
    artist: String,
    date: String,
    medium: String,
    credit: &'static str,
    text: String,
    url: String,
    /// Where the picture is; the first that answers is used.
    images: Vec<String>,
    /// How many works fitted the owner's choices when this one was picked.
    pool: Value,
}

fn fetch(known: &std::path::Path, day: &str, salt: u64, prefs: &Prefs) -> Result<(Value, Vec<u8>), String> {
    let mut hasher = DefaultHasher::new();
    (day, salt).hash(&mut hasher);
    let seed = hasher.finish() % 1_000_000_000;
    // The day picks the source too; the others stand in if it has nothing or is down.
    let sources = prefs.sources();
    let mut failures = Vec::new();
    for turn in 0..sources.len() {
        let work = match sources[(seed as usize + turn) % sources.len()] {
            "cleveland" => cleveland(seed, prefs),
            "wikiart" => wikiart(seed, prefs, known),
            _ => artic(seed, prefs),
        };
        match work.and_then(prepare) {
            Ok(prepared) => return Ok(prepared),
            Err(e) => failures.push(e),
        }
    }
    Err(failures.join("; "))
}

fn get(url: &str) -> ureq::Request {
    ureq::get(url)
        .set("User-Agent", AGENT)
        .set("AIC-User-Agent", AGENT)
        .timeout(Duration::from_secs(30))
}

fn get_json(url: &str, whose: &str) -> Result<Value, String> {
    get(url)
        .call()
        .map_err(|e| format!("{whose}: {e}"))?
        .into_json()
        .map_err(|e| format!("{whose}: {e}"))
}

/// Fetch a work's picture and make what the frame needs of it.
fn prepare(work: Work) -> Result<(Value, Vec<u8>), String> {
    let mut original = Vec::new();
    let mut failure = String::from("the work has no image");
    for url in &work.images {
        original.clear();
        let read = get(url).call().map_err(|e| e.to_string()).and_then(|r| {
            r.into_reader()
                .take(MAX_IMAGE_BYTES)
                .read_to_end(&mut original)
                .map_err(|e| e.to_string())
        });
        match read {
            Ok(_) => break,
            Err(e) => failure = e,
        }
    }
    if original.is_empty() {
        return Err(format!("{} image: {failure}", work.credit));
    }
    let img = image::load_from_memory(&original).map_err(|e| format!("{} image: {e}", work.credit))?;
    let img = if img.width().max(img.height()) > 1280 {
        img.resize(1280, 1280, FilterType::CatmullRom)
    } else {
        img
    };
    let mut jpeg = Vec::new();
    JpegEncoder::new_with_quality(&mut jpeg, 88)
        .encode_image(&img.to_rgb8())
        .map_err(|e| e.to_string())?;
    let meta = json!({
        "title": work.title,
        "artist": work.artist,
        "date": work.date,
        "medium": work.medium,
        "credit": work.credit,
        "text": excerpt(&plain_text(&work.text)),
        "url": work.url,
        "width": img.width(),
        "height": img.height(),
        "palette": palette(&img),
        "pool": work.pool,
    });
    Ok((meta, jpeg))
}

fn text_of(value: &Value) -> String {
    value.as_str().unwrap_or("").trim().to_owned()
}

fn artic(seed: u64, prefs: &Prefs) -> Result<Work, String> {
    let (mut filter, must_not) = prefs.clauses();
    let kinds: Vec<&str> = prefs.kinds().iter().map(|k| k.2).collect();
    filter.extend([
        json!({ "exists": { "field": "image_id" } }),
        json!({ "exists": { "field": "description" } }),
        json!({ "terms": { "artwork_type_title.keyword": kinds } }),
    ]);
    if !prefs.copyrighted {
        filter.push(json!({ "term": { "is_public_domain": true } }));
    }
    let query = json!({
        "resources": "artworks",
        "fields": ["id", "title", "artist_display", "date_display", "medium_display", "image_id",
                   "short_description", "description", "credit_line", "is_public_domain"],
        "boost": false,
        "limit": 1,
        "query": { "function_score": {
            "query": { "bool": { "filter": filter, "must_not": must_not } },
            "boost_mode": "replace",
            "random_score": { "field": "id", "seed": seed },
        } },
    });
    let found: Value = ureq::post(API)
        .set("AIC-User-Agent", AGENT)
        .timeout(Duration::from_secs(20))
        .send_json(query)
        .map_err(|e| format!("museum search: {e}"))?
        .into_json()
        .map_err(|e| format!("museum search: {e}"))?;
    let work = found["data"]
        .get(0)
        .ok_or("no painting fits those choices; loosen them on the hub's page")?;
    let image_id = work["image_id"].as_str().ok_or("the painting has no image")?;
    // Works still in copyright are only served up to 843 pixels wide.
    let size = if work["is_public_domain"] == true {
        "!1280,1280"
    } else {
        "843,"
    };
    let text = work["short_description"]
        .as_str()
        .or(work["description"].as_str())
        .unwrap_or("");
    let id = work["id"]
        .as_u64()
        .map(|n| n.to_string())
        .or(work["id"].as_str().map(str::to_owned))
        .unwrap_or_default();
    Ok(Work {
        title: work["title"].as_str().unwrap_or("Untitled").to_owned(),
        // The museum follows the artist's name with nationality and dates, on a new line or in brackets.
        artist: first_part(work["artist_display"].as_str().unwrap_or("")),
        date: text_of(&work["date_display"]),
        medium: text_of(&work["medium_display"]),
        credit: "Art Institute of Chicago",
        text: text.to_owned(),
        url: format!("https://www.artic.edu/artworks/{id}"),
        images: vec![format!("{IIIF}/{image_id}/full/{size}/0/default.jpg")],
        pool: found["pagination"]["total"].clone(),
    })
}

/// A name without the nationality and dates that follow it on a new line or in brackets.
fn first_part(display: &str) -> String {
    let line = display.lines().next().unwrap_or("");
    line.split(" (").next().unwrap_or("").trim().to_owned()
}

/// Cleveland's open-access works. Its search takes a period, a kind and a word, but cannot
/// leave subjects out, so a handful are fetched from a random place and the first that fits
/// is taken.
fn cleveland(seed: u64, prefs: &Prefs) -> Result<Work, String> {
    let kinds = prefs.kinds();
    let kind = kinds[seed as usize % kinds.len()].3;
    let mut query = format!("{CLEVELAND}?has_image=1&cc0=1&type={kind}");
    match prefs.period.as_str() {
        "old" => query.push_str("&created_before=1799"),
        "c19" => query.push_str("&created_after=1800&created_before=1899"),
        "modern" => query.push_str("&created_after=1860"),
        _ => {}
    }
    if let Some((_, word, _)) = SUBJECTS_ELSEWHERE.iter().find(|s| s.0 == prefs.subject) {
        query.push_str(&format!("&q={}", word.replace(' ', "%20")));
    }
    let total = get_json(&format!("{query}&limit=0"), "Cleveland search")?["info"]["total"]
        .as_u64()
        .filter(|n| *n > 0)
        .ok_or("nothing at Cleveland fits those choices; loosen them on the hub's page")?;
    let found = get_json(&format!("{query}&limit=8&skip={}", seed % total), "Cleveland search")?;
    let fitting: Vec<&Value> = found["data"]
        .as_array()
        .into_iter()
        .flatten()
        .filter(|w| {
            let described = format!("{} {}", text_of(&w["title"]), text_of(&w["description"]));
            !prefs.avoids(&described)
        })
        .collect();
    // One with something to read about it, if there is one.
    let work = fitting
        .iter()
        .find(|w| !text_of(&w["description"]).is_empty())
        .or(fitting.first())
        .ok_or("nothing at Cleveland fitted this time")?;
    let text = ["description", "did_you_know", "tombstone"]
        .iter()
        .map(|field| text_of(&work[*field]))
        .find(|t| !t.is_empty())
        .unwrap_or_default();
    Ok(Work {
        title: work["title"].as_str().unwrap_or("Untitled").to_owned(),
        artist: first_part(work["creators"][0]["description"].as_str().unwrap_or("")),
        date: text_of(&work["creation_date"]),
        medium: text_of(&work["technique"]),
        credit: "Cleveland Museum of Art",
        text,
        url: text_of(&work["url"]),
        images: ["print", "web"]
            .iter()
            .filter_map(|size| work["images"][*size]["url"].as_str().map(str::to_owned))
            .collect(),
        pool: json!(total),
    })
}

/// WikiArt's most viewed paintings, well-known ones of every age, many still in copyright.
/// The list says little about each, so the choices are checked one painting at a time.
/// What is looked up is kept in `known`, so narrow choices get quicker to meet as days pass.
fn wikiart(seed: u64, prefs: &Prefs, known: &std::path::Path) -> Result<Work, String> {
    if !prefs.kinds().iter().any(|k| k.0 == "painting") {
        return Err("WikiArt only has paintings".into());
    }
    let genres = SUBJECTS_ELSEWHERE.iter().find(|s| s.0 == prefs.subject).map(|s| s.2);
    let list = get_json(&format!("{WIKIART}/App/Painting/MostViewedPaintings?json=2"), "WikiArt")?;
    let list = list
        .as_array()
        .filter(|l| !l.is_empty())
        .ok_or("WikiArt sent no list")?;
    let known = known.join("wikiart");
    let _ = std::fs::create_dir_all(&known);
    let mut lookups = 0;
    // Stepping by a number the list's length does not divide by visits every painting once,
    // and a different step each day keeps a rare subject from always landing on the same few.
    let stride = [7, 11, 13, 17, 19, 23, 29, 31][(seed / 600) as usize % 8];
    let stride = if list.len() % stride == 0 { 1 } else { stride };
    for step in 0..list.len() {
        let listed = &list[(seed as usize + step * stride) % list.len()];
        let year = listed["completitionYear"].as_i64();
        if !prefs.period.is_empty() && !year.is_some_and(|y| prefs.fits_year(y)) {
            continue;
        }
        let Some(id) = listed["contentId"].as_u64() else {
            continue;
        };
        let kept = known.join(format!("{id}.json"));
        let remembered = std::fs::read(&kept).ok().and_then(|b| serde_json::from_slice(&b).ok());
        let work: Value = match remembered {
            Some(work) => work,
            None if lookups == WIKIART_LOOKUPS => continue,
            None => {
                lookups += 1;
                let work = get_json(&format!("{WIKIART}/App/Painting/ImageJson/{id}"), "WikiArt")?;
                let _ = std::fs::write(&kept, work.to_string());
                work
            }
        };
        let genre = text_of(&work["genre"]);
        let described = format!("{} {genre} {}", text_of(&work["title"]), text_of(&work["tags"]));
        if prefs.avoids(&described) || genres.is_some_and(|wanted| !wanted.contains(&genre.as_str())) {
            continue;
        }
        let style = text_of(&work["style"]);
        let held = text_of(&work["galleryName"]);
        let mut text = wikiart_text(work["description"].as_str().unwrap_or(""));
        if text.is_empty() {
            // Nothing written about it: say what it is and where it hangs.
            text = [style.as_str(), genre.as_str(), held.as_str()]
                .iter()
                .filter(|part| !part.is_empty())
                .map(|part| format!("{part}. "))
                .collect();
        }
        // The list links a small picture, `name.jpg!Large.jpg`; the same name has bigger ones.
        let image = text_of(&listed["image"]);
        let image = image.split('!').next().unwrap_or("");
        return Ok(Work {
            title: text_of(&work["title"]),
            artist: text_of(&listed["artistName"]),
            date: text_of(&work["yearAsString"]),
            medium: [text_of(&work["technique"]), text_of(&work["material"]), style]
                .into_iter()
                .find(|m| !m.is_empty())
                .unwrap_or_default(),
            credit: "WikiArt",
            text,
            url: format!("{WIKIART}/{}/{}", text_of(&work["artistUrl"]), text_of(&work["url"])),
            images: vec![format!("{image}!HD.jpg"), image.to_owned()],
            pool: json!(list.len()),
        });
    }
    Err("nothing on WikiArt fitted those choices this time".into())
}

/// WikiArt marks its text up in square brackets: `[i]…[/i]`, `[url href=…]…[/url]`.
fn wikiart_text(marked: &str) -> String {
    let mut out = String::new();
    let mut in_tag = false;
    for c in marked.chars() {
        match c {
            '[' => in_tag = true,
            ']' => in_tag = false,
            c if !in_tag => out.push(c),
            _ => {}
        }
    }
    out
}

fn plain_text(html: &str) -> String {
    let mut out = String::new();
    let mut in_tag = false;
    for c in html.chars() {
        match c {
            '<' => in_tag = true,
            '>' => {
                in_tag = false;
                out.push(' ');
            }
            c if !in_tag => out.push(c),
            _ => {}
        }
    }
    out.replace("&amp;", "&")
        .replace("&nbsp;", " ")
        .replace("&quot;", "\"")
        .split_whitespace()
        .collect::<Vec<_>>()
        .join(" ")
}

/// Whole sentences up to about TEXT_CHARS, so the frame never shows a cut-off thought.
fn excerpt(text: &str) -> String {
    let mut out = String::new();
    for sentence in text.split_inclusive(". ") {
        if !out.is_empty() && out.chars().count() + sentence.chars().count() > TEXT_CHARS {
            break;
        }
        out.push_str(sentence);
    }
    out.trim().to_owned()
}

type Rgb = [f32; 3];

fn lum(c: Rgb) -> f32 {
    (0.299 * c[0] + 0.587 * c[1] + 0.114 * c[2]) / 255.0
}

fn saturation(c: Rgb) -> f32 {
    let max = c[0].max(c[1]).max(c[2]);
    let min = c[0].min(c[1]).min(c[2]);
    if max <= 0.0 {
        0.0
    } else {
        (max - min) / max
    }
}

fn mix(a: Rgb, b: Rgb, t: f32) -> Rgb {
    [
        a[0] + (b[0] - a[0]) * t,
        a[1] + (b[1] - a[1]) * t,
        a[2] + (b[2] - a[2]) * t,
    ]
}

/// Move a colour to a target lightness by mixing toward black or white, keeping its hue.
fn at_lum(c: Rgb, target: f32) -> Rgb {
    let l = lum(c);
    if l > target {
        mix(c, [0.0; 3], 1.0 - target / l.max(0.001))
    } else {
        mix(c, [255.0; 3], (target - l) / (1.0 - l).max(0.001))
    }
}

fn hex(c: Rgb) -> String {
    format!(
        "#{:02x}{:02x}{:02x}",
        c[0].round() as u8,
        c[1].round() as u8,
        c[2].round() as u8
    )
}

/// Five swatches by k-means, most common first, plus the colours the layouts need:
/// `wall` and `ink` (dark grounds), `paper` (light text) and `accent`.
fn palette(img: &DynamicImage) -> Value {
    const K: usize = 5;
    let small = img.resize_exact(48, 48, FilterType::Triangle).to_rgb8();
    let mut px: Vec<Rgb> = small
        .pixels()
        .map(|p| [p[0] as f32, p[1] as f32, p[2] as f32])
        .collect();
    px.sort_by(|a, b| lum(*a).total_cmp(&lum(*b)));
    let mut centers: Vec<Rgb> = (0..K).map(|i| px[px.len() * (2 * i + 1) / (2 * K)]).collect();
    let mut counts = [0usize; K];
    for _ in 0..12 {
        let mut sums = [[0f32; 3]; K];
        counts = [0; K];
        for p in &px {
            let nearest = (0..K)
                .min_by(|&a, &b| dist(*p, centers[a]).total_cmp(&dist(*p, centers[b])))
                .unwrap();
            counts[nearest] += 1;
            for ch in 0..3 {
                sums[nearest][ch] += p[ch];
            }
        }
        for k in 0..K {
            if counts[k] > 0 {
                centers[k] = [
                    sums[k][0] / counts[k] as f32,
                    sums[k][1] / counts[k] as f32,
                    sums[k][2] / counts[k] as f32,
                ];
            }
        }
    }
    let mut order: Vec<usize> = (0..K).collect();
    order.sort_by(|&a, &b| counts[b].cmp(&counts[a]));
    let swatches: Vec<Rgb> = order.iter().map(|&k| centers[k]).collect();

    let vivid = *swatches
        .iter()
        .max_by(|a, b| (saturation(**a) * (0.3 + lum(**a))).total_cmp(&(saturation(**b) * (0.3 + lum(**b)))))
        .unwrap();
    let darkest = *swatches.iter().min_by(|a, b| lum(**a).total_cmp(&lum(**b))).unwrap();
    let lightest = *swatches.iter().max_by(|a, b| lum(**a).total_cmp(&lum(**b))).unwrap();
    json!({
        "swatches": swatches.iter().map(|c| hex(*c)).collect::<Vec<_>>(),
        "wall": hex(at_lum(mix(swatches[0], vivid, 0.4), 0.17)),
        "ink": hex(at_lum(darkest, 0.07)),
        "paper": hex(at_lum(lightest, 0.9)),
        "accent": hex(at_lum(vivid, lum(vivid).max(0.58))),
    })
}

fn dist(a: Rgb, b: Rgb) -> f32 {
    (a[0] - b[0]).powi(2) + (a[1] - b[1]).powi(2) + (a[2] - b[2]).powi(2)
}

fn qr_png(url: &str) -> Result<Vec<u8>, String> {
    let code = qrcode::QrCode::new(url.as_bytes()).map_err(|e| e.to_string())?;
    let n = code.width() as u32;
    const QUIET: u32 = 2;
    const SCALE: u32 = 6;
    let colors = code.to_colors();
    let size = (n + 2 * QUIET) * SCALE;
    let img = GrayImage::from_fn(size, size, |x, y| {
        let (mx, my) = (x / SCALE, y / SCALE);
        let inside = mx >= QUIET && my >= QUIET && mx < n + QUIET && my < n + QUIET;
        let dark = inside && colors[((my - QUIET) * n + (mx - QUIET)) as usize] == qrcode::Color::Dark;
        Luma([if dark { 0 } else { 255 }])
    });
    let mut png = Vec::new();
    img.write_to(&mut Cursor::new(&mut png), ImageFormat::Png)
        .map_err(|e| e.to_string())?;
    Ok(png)
}

pub async fn file(State(hub): State<Shared>, Path((version, file)): Path<(u64, String)>) -> Response {
    let art = hub.art.lock().unwrap().clone();
    let found = art.filter(|a| a.version == version).and_then(|a| match file.as_str() {
        "image.jpg" => Some(("image/jpeg", a.image.clone())),
        "qr.png" => Some(("image/png", a.qr.clone())),
        _ => None,
    });
    match found {
        Some((mime, bytes)) => (
            [
                (header::CONTENT_TYPE, mime),
                (header::CACHE_CONTROL, "max-age=31536000, immutable"),
            ],
            bytes,
        )
            .into_response(),
        None => StatusCode::NOT_FOUND.into_response(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn plain_text_strips_markup_and_entities() {
        assert_eq!(
            plain_text("<p>Oil &amp; tempera,<br>on <em>panel</em>.</p>"),
            "Oil & tempera, on panel ."
        );
    }

    #[test]
    fn excerpt_keeps_whole_sentences() {
        let long = "One sentence here. ".repeat(40);
        let cut = excerpt(&long);
        assert!(cut.chars().count() <= TEXT_CHARS);
        assert!(cut.ends_with("here."));
        assert_eq!(excerpt("Short."), "Short.");
    }

    #[test]
    fn excerpt_keeps_a_single_overlong_sentence() {
        let long = "word ".repeat(200);
        assert_eq!(excerpt(&long), long.trim());
    }

    #[test]
    fn at_lum_reaches_the_target_from_either_side() {
        for colour in [[200.0, 40.0, 40.0], [10.0, 20.0, 60.0], [128.0, 128.0, 128.0]] {
            for target in [0.07, 0.17, 0.58, 0.9] {
                assert!((lum(at_lum(colour, target)) - target).abs() < 0.01);
            }
        }
    }

    #[test]
    fn palette_has_five_swatches_and_readable_grounds() {
        let img = DynamicImage::ImageRgb8(image::RgbImage::from_fn(64, 64, |x, y| {
            image::Rgb([(x * 4) as u8, (y * 4) as u8, 120])
        }));
        let p = palette(&img);
        assert_eq!(p["swatches"].as_array().unwrap().len(), 5);
        let parse = |name: &str| {
            let hex = p[name].as_str().unwrap();
            let n = u32::from_str_radix(&hex[1..], 16).unwrap();
            lum([(n >> 16) as f32, (n >> 8 & 255) as f32, (n & 255) as f32])
        };
        assert!(parse("ink") < 0.12 && parse("wall") < 0.25);
        assert!(parse("paper") > 0.85 && parse("accent") > 0.5);
    }

    #[test]
    fn qr_is_a_png() {
        let png = qr_png("https://www.artic.edu/artworks/1").unwrap();
        assert_eq!(&png[1..4], b"PNG");
    }

    #[test]
    fn no_choices_add_no_clauses() {
        let (filter, must_not) = Prefs::default().clauses();
        assert!(filter.is_empty() && must_not.is_empty());
        assert!(Prefs::default().is_any());
    }

    #[test]
    fn choices_become_search_clauses() {
        let prefs = Prefs::chosen("modern", "landscapes", vec!["religious".into(), "nudes".into()]).unwrap();
        let (filter, must_not) = prefs.clauses();
        assert_eq!(filter[0], json!({ "range": { "date_start": { "gte": 1860 } } }));
        assert_eq!(
            filter[1]["terms"]["subject_titles.keyword"],
            json!(["landscapes", "landscape"])
        );
        // Religious adds a title check on top of its tags; nudes is tags only.
        assert_eq!(must_not.len(), 3);
        assert!(must_not[1]["match"]["title"].as_str().unwrap().contains("Madonna"));
    }

    #[test]
    fn unknown_choices_are_refused() {
        assert!(Prefs::chosen("future", "", vec![]).is_err());
        assert!(Prefs::chosen("", "cats", vec![]).is_err());
        assert!(Prefs::chosen("", "", vec!["mondays".into()]).is_err());
    }

    #[test]
    fn sources_and_kinds_default_to_the_art_institutes_paintings() {
        let prefs = Prefs::default();
        assert_eq!(prefs.sources(), ["artic"]);
        assert_eq!(prefs.kinds()[0].0, "painting");
        let prefs = prefs
            .from(vec!["wikiart".into(), "cleveland".into()], vec!["print".into()], true)
            .unwrap();
        assert_eq!(prefs.sources(), ["wikiart", "cleveland"]);
        assert_eq!(prefs.kinds()[0].2, "Print");
        assert!(!prefs.is_any());
        assert!(Prefs::default().from(vec!["louvre".into()], vec![], false).is_err());
        assert!(Prefs::default().from(vec![], vec!["sculpture".into()], false).is_err());
    }

    #[test]
    fn avoided_words_are_matched_whole() {
        let prefs = Prefs::chosen("", "", vec!["religious".into(), "death".into()]).unwrap();
        assert!(prefs.avoids("The Holy Family"));
        assert!(prefs.avoids("Still life battle painting"));
        assert!(!prefs.avoids("Warm afternoon, Goddard's farm"));
        assert!(!prefs.avoids("Nude descending a staircase"));
        assert!(!Prefs::default().avoids("The Holy Family"));
    }

    #[test]
    fn periods_judge_a_year() {
        let in_period = |period: &str, year| Prefs::chosen(period, "", vec![]).unwrap().fits_year(year);
        assert!(in_period("old", 1650) && !in_period("old", 1850));
        assert!(in_period("c19", 1850) && !in_period("c19", 1900));
        assert!(in_period("modern", 1931) && !in_period("modern", 1859));
        assert!(in_period("", 1200));
    }

    #[test]
    fn wikiart_markup_is_dropped() {
        assert_eq!(
            wikiart_text("[i]Title[/i] is [url href=https://example.org/a]famous[/url]."),
            "Title is famous."
        );
    }

    /// Talks to every source, so it only runs when asked: `cargo test -- --ignored --nocapture`.
    #[test]
    #[ignore]
    fn every_source_answers() {
        let wide = Prefs::default().from(vec![], vec![], true).unwrap();
        let narrow = Prefs::chosen("modern", "landscapes", vec!["religious".into(), "nudes".into()])
            .unwrap()
            .from(vec![], vec!["painting".into(), "print".into()], true)
            .unwrap();
        let known = std::env::temp_dir().join("mado-test-art");
        let wikiart = |seed, prefs: &Prefs| wikiart(seed, prefs, &known);
        let sources: [&dyn Fn(u64, &Prefs) -> Result<Work, String>; 3] = [&artic, &cleveland, &wikiart];
        for source in sources {
            for (prefs, seed) in [(&wide, 11), (&wide, 4242), (&narrow, 11), (&narrow, 4242)] {
                let (meta, image) = source(seed, prefs).and_then(prepare).unwrap();
                println!(
                    "{} | {} | {} | {} | {}x{} | from {} | {} | {:.60}",
                    meta["credit"],
                    meta["title"],
                    meta["artist"],
                    meta["date"],
                    meta["width"],
                    meta["height"],
                    meta["pool"],
                    meta["url"],
                    meta["text"].as_str().unwrap()
                );
                assert!(!image.is_empty() && meta["url"].as_str().unwrap().starts_with("https://"));
            }
        }
    }

    /// Talks to the museum, so it only runs when asked: `cargo test -- --ignored --nocapture`.
    #[test]
    #[ignore]
    fn the_museum_honours_the_choices() {
        let nowhere = std::env::temp_dir();
        let everything = fetch(&nowhere, "2026-01-01", 0, &Prefs::default()).unwrap().0;
        let prefs = Prefs::chosen("modern", "landscapes", vec!["religious".into()]).unwrap();
        for salt in 0..3 {
            let (meta, image) = fetch(&nowhere, "2026-01-01", salt, &prefs).unwrap();
            println!(
                "{} | {} | {} | from {}",
                meta["title"], meta["artist"], meta["date"], meta["pool"]
            );
            assert!(!image.is_empty());
            assert!(meta["pool"].as_u64().unwrap() < everything["pool"].as_u64().unwrap());
        }
        let nothing = Prefs::chosen(
            "old",
            "flowers",
            vec!["religious".into(), "nudes".into(), "death".into()],
        );
        println!(
            "narrowest: {:?}",
            fetch(&nowhere, "2026-01-01", 0, &nothing.unwrap()).map(|f| f.0["pool"].clone())
        );
    }

    #[test]
    fn only_dates_are_accepted_as_days() {
        assert!(valid_day("2026-10-12"));
        assert!(!valid_day("../../etc/passwd"));
        assert!(!valid_day("2026-10-1"));
    }
}
