//! MCP over streamable HTTP, stateless: every POST carries JSON-RPC and gets JSON back.

use axum::{
    extract::State,
    http::StatusCode,
    response::{IntoResponse, Response},
    Json,
};
use base64::Engine;
use serde_json::{json, Value};

use crate::{projects, Notify, Shared};

const PROTOCOL: &str = "2025-06-18";

pub async fn handle(State(hub): State<Shared>, Json(body): Json<Value>) -> Response {
    let replies = match body {
        Value::Array(batch) => {
            let mut out = Vec::new();
            for msg in batch {
                out.extend(rpc(&hub, msg).await);
            }
            if out.is_empty() { None } else { Some(Value::Array(out)) }
        }
        msg => rpc(&hub, msg).await,
    };
    match replies {
        Some(v) => Json(v).into_response(),
        // Notifications and client responses get no body.
        None => StatusCode::ACCEPTED.into_response(),
    }
}

async fn rpc(hub: &Shared, msg: Value) -> Option<Value> {
    let method = msg.get("method")?.as_str()?;
    let id = msg.get("id")?.clone();
    let params = &msg["params"];
    let result = match method {
        "initialize" => Ok(json!({
            "protocolVersion": params["protocolVersion"].as_str().unwrap_or(PROTOCOL),
            "capabilities": { "tools": {} },
            "serverInfo": { "name": "mado-hub", "version": env!("CARGO_PKG_VERSION") },
            "instructions": "Mado is a 10 inch always-on display in the owner's home. It has no sound. \
                Use it sparingly: whisper for things that can wait, nudge for things worth a glance, \
                urgent only when the owner must act soon.",
        })),
        "ping" => Ok(json!({})),
        "tools/list" => Ok(json!({ "tools": tools() })),
        "tools/call" => Ok(call(hub, params).await),
        _ => Err(json!({ "code": -32601, "message": format!("unknown method {method}") })),
    };
    Some(match result {
        Ok(result) => json!({ "jsonrpc": "2.0", "id": id, "result": result }),
        Err(error) => json!({ "jsonrpc": "2.0", "id": id, "error": error }),
    })
}

fn tools() -> Value {
    json!([
        {
            "name": "frame_notify",
            "description": "Show a short message on the Mado frame as a liquid alert. \
                whisper: a small bead at the edge, no text shown until tapped. \
                nudge: one line that leaves by itself. \
                urgent: covers a third of the screen until the owner dismisses it.",
            "inputSchema": {
                "type": "object",
                "properties": {
                    "text": { "type": "string", "description": "The message, at most 280 characters. Shorter reads better from across a room." },
                    "level": { "type": "string", "enum": ["whisper", "nudge", "urgent"], "default": "nudge" },
                    "source": { "type": "string", "description": "Who is speaking, shown above the message, e.g. your agent name." },
                    "ttl_s": { "type": "integer", "minimum": 0, "description": "Seconds on screen. 0 means until dismissed. Defaults: whisper 60, nudge 20, urgent 0." }
                },
                "required": ["text"]
            }
        },
        {
            "name": "frame_project",
            "description": "Report where a project stands. The frame's dashboard lists the latest report per project, \
                so call this when a piece of work finishes, gets blocked, or needs the owner. \
                Reuse the exact project name to update it; status archived takes it off the dashboard.",
            "inputSchema": {
                "type": "object",
                "properties": {
                    "name": { "type": "string", "description": "Short project name, at most 40 characters." },
                    "status": { "type": "string", "enum": ["on_track", "needs_you", "blocked", "done", "archived"] },
                    "note": { "type": "string", "description": "One short line on where it stands, at most 120 characters." },
                    "progress": { "type": "number", "minimum": 0, "maximum": 1, "description": "Optional fraction complete." }
                },
                "required": ["name", "status"]
            }
        },
        {
            "name": "frame_look",
            "description": "Return a picture of what the frame is showing right now (1280x800 JPEG).",
            "inputSchema": { "type": "object", "properties": {} }
        },
        {
            "name": "frame_state",
            "description": "Return which frames are connected, the hub's config and recent activity.",
            "inputSchema": { "type": "object", "properties": {} }
        }
    ])
}

async fn call(hub: &Shared, params: &Value) -> Value {
    let args = params.get("arguments").cloned().unwrap_or_else(|| json!({}));
    let outcome: Result<Value, String> = match params["name"].as_str().unwrap_or("") {
        "frame_notify" => serde_json::from_value::<Notify>(args)
            .map_err(|e| e.to_string())
            .and_then(|n| hub.notify(n))
            .map(text),
        "frame_project" => serde_json::from_value::<projects::Update>(args)
            .map_err(|e| e.to_string())
            .and_then(|u| hub.update_project(u))
            .map(text),
        "frame_look" => hub.look().await.map(|jpeg| {
            json!({ "type": "image", "mimeType": "image/jpeg",
                    "data": base64::engine::general_purpose::STANDARD.encode(jpeg) })
        }),
        "frame_state" => Ok(text(hub.state())),
        other => Err(format!("unknown tool {other}")),
    };
    match outcome {
        Ok(content) => json!({ "content": [content], "isError": false }),
        Err(e) => json!({ "content": [{ "type": "text", "text": e }], "isError": true }),
    }
}

fn text(v: Value) -> Value {
    json!({ "type": "text", "text": v.to_string() })
}
