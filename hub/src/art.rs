//! Art of the day: one public-domain painting per day from the Art Institute of Chicago's
//! open API, prepared for the frame with a palette the dashboard takes its colours from.
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
use serde_json::{json, Value};

use crate::Shared;

const API: &str = "https://api.artic.edu/api/v1/search";
const IIIF: &str = "https://www.artic.edu/iiif/2";
/// The museum asks API users to identify themselves.
const AGENT: &str = "mado-hub (open-source photo-frame project)";
const MAX_IMAGE_BYTES: u64 = 20 << 20;
const TEXT_CHARS: usize = 460;

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
                hub.log(format!("art of the day: {}", art.meta["title"].as_str().unwrap_or("untitled")));
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
            let (mut meta, image) = fetch(day, salt)?;
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
    Ok(Daily { version: hub.id(), day: day.to_owned(), salt, meta, image: image.into(), qr: qr.into() })
}

fn fetch(day: &str, salt: u64) -> Result<(Value, Vec<u8>), String> {
    let mut hasher = DefaultHasher::new();
    (day, salt).hash(&mut hasher);
    let seed = hasher.finish() % 1_000_000_000;
    let query = json!({
        "resources": "artworks",
        "fields": ["id", "title", "artist_display", "date_display", "medium_display", "image_id",
                   "short_description", "description", "credit_line"],
        "boost": false,
        "limit": 1,
        "query": { "function_score": {
            "query": { "bool": { "filter": [
                { "term": { "is_public_domain": true } },
                { "exists": { "field": "image_id" } },
                { "exists": { "field": "description" } },
                { "terms": { "artwork_type_title.keyword": ["Painting"] } },
            ] } },
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
    let work = found["data"].get(0).ok_or("the museum returned no painting")?;
    let image_id = work["image_id"].as_str().ok_or("the painting has no image")?;

    let mut original = Vec::new();
    ureq::get(&format!("{IIIF}/{image_id}/full/!1280,1280/0/default.jpg"))
        .set("AIC-User-Agent", AGENT)
        .timeout(Duration::from_secs(30))
        .call()
        .map_err(|e| format!("museum image: {e}"))?
        .into_reader()
        .take(MAX_IMAGE_BYTES)
        .read_to_end(&mut original)
        .map_err(|e| format!("museum image: {e}"))?;
    let img = image::load_from_memory(&original).map_err(|e| format!("museum image: {e}"))?;
    let img = if img.width().max(img.height()) > 1280 { img.resize(1280, 1280, FilterType::CatmullRom) } else { img };
    let mut jpeg = Vec::new();
    JpegEncoder::new_with_quality(&mut jpeg, 88).encode_image(&img.to_rgb8()).map_err(|e| e.to_string())?;

    let text = work["short_description"].as_str().or(work["description"].as_str()).unwrap_or("");
    let id = work["id"].as_u64().map(|n| n.to_string()).or(work["id"].as_str().map(str::to_owned)).unwrap_or_default();
    let meta = json!({
        "title": work["title"].as_str().unwrap_or("Untitled"),
        // The museum follows the artist's name with nationality and dates, on a new line or in brackets.
        "artist": work["artist_display"].as_str().unwrap_or("").lines().next().unwrap_or("").split(" (").next().unwrap_or(""),
        "date": work["date_display"].as_str().unwrap_or(""),
        "medium": work["medium_display"].as_str().unwrap_or(""),
        "credit": "Art Institute of Chicago",
        "text": excerpt(&plain_text(text)),
        "url": format!("https://www.artic.edu/artworks/{id}"),
        "width": img.width(),
        "height": img.height(),
        "palette": palette(&img),
    });
    Ok((meta, jpeg))
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
    out.replace("&amp;", "&").replace("&nbsp;", " ").replace("&quot;", "\"").split_whitespace().collect::<Vec<_>>().join(" ")
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
    if max <= 0.0 { 0.0 } else { (max - min) / max }
}

fn mix(a: Rgb, b: Rgb, t: f32) -> Rgb {
    [a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t]
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
    format!("#{:02x}{:02x}{:02x}", c[0].round() as u8, c[1].round() as u8, c[2].round() as u8)
}

/// Five swatches by k-means, most common first, plus the colours the layouts need:
/// `wall` and `ink` (dark grounds), `paper` (light text) and `accent`.
fn palette(img: &DynamicImage) -> Value {
    const K: usize = 5;
    let small = img.resize_exact(48, 48, FilterType::Triangle).to_rgb8();
    let mut px: Vec<Rgb> = small.pixels().map(|p| [p[0] as f32, p[1] as f32, p[2] as f32]).collect();
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
                centers[k] = [sums[k][0] / counts[k] as f32, sums[k][1] / counts[k] as f32, sums[k][2] / counts[k] as f32];
            }
        }
    }
    let mut order: Vec<usize> = (0..K).collect();
    order.sort_by(|&a, &b| counts[b].cmp(&counts[a]));
    let swatches: Vec<Rgb> = order.iter().map(|&k| centers[k]).collect();

    let vivid = *swatches.iter().max_by(|a, b| (saturation(**a) * (0.3 + lum(**a))).total_cmp(&(saturation(**b) * (0.3 + lum(**b))))).unwrap();
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
    img.write_to(&mut Cursor::new(&mut png), ImageFormat::Png).map_err(|e| e.to_string())?;
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
        Some((mime, bytes)) => ([(header::CONTENT_TYPE, mime), (header::CACHE_CONTROL, "max-age=31536000, immutable")], bytes).into_response(),
        None => StatusCode::NOT_FOUND.into_response(),
    }
}
