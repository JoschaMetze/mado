//! Lets frames find this hub by themselves: it is announced on the local network over
//! mDNS/DNS-SD as a `_mado._tcp` service, the way printers and speakers are.

use mdns_sd::{IfKind, ServiceDaemon, ServiceInfo};

pub const SERVICE: &str = "_mado._tcp.local.";

/// The port from a bind address such as `0.0.0.0:7878` or `[::]:7878`.
pub fn port_of(bind: &str) -> Option<u16> {
    bind.rsplit(':').next()?.parse().ok()
}

/// What this machine calls itself, without a domain: `kitchen` for `kitchen.local`.
fn machine() -> String {
    let name = std::process::Command::new("hostname")
        .output()
        .ok()
        .and_then(|o| String::from_utf8(o.stdout).ok())
        .unwrap_or_default();
    label(&name)
}

/// One DNS label out of whatever a machine reports as its name.
fn label(name: &str) -> String {
    let first = name.trim().split('.').next().unwrap_or("");
    let clean: String = first
        .chars()
        .filter(|c| c.is_ascii_alphanumeric() || *c == '-')
        .take(40)
        .collect();
    if clean.is_empty() {
        "hub".into()
    } else {
        clean
    }
}

/// Announce the hub for as long as the returned daemon lives. `frame_port` is the port frames
/// dial; `name` is what a person sees in a frame's list of hubs (`MADO_NAME`).
pub fn announce(frame_port: u16, name: Option<String>) -> Result<ServiceDaemon, String> {
    let machine = machine();
    let name = name
        .filter(|n| !n.trim().is_empty())
        .unwrap_or_else(|| format!("Mado on {machine}"));
    let daemon = ServiceDaemon::new().map_err(|e| e.to_string())?;
    // IPv4 only: a frame is an old Android, and a link-local IPv6 address is no use to it.
    daemon.disable_interface(IfKind::IPv6).map_err(|e| e.to_string())?;
    // A host name of its own, so it never argues with the machine's own `name.local`.
    let host = format!("mado-hub-{machine}.local.");
    let properties = [("path", "/frame"), ("version", env!("CARGO_PKG_VERSION"))];
    let info = ServiceInfo::new(SERVICE, &name, &host, "", frame_port, &properties[..])
        .map_err(|e| e.to_string())?
        // Every address this machine has, each announced on the network it belongs to.
        .enable_addr_auto();
    daemon.register(info).map_err(|e| e.to_string())?;
    println!("mado-hub announced on the local network as \"{name}\" (frames dial port {frame_port})");
    Ok(daemon)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ports_come_from_bind_addresses() {
        assert_eq!(port_of("0.0.0.0:7878"), Some(7878));
        assert_eq!(port_of("[::]:7879"), Some(7879));
        assert_eq!(port_of("nonsense"), None);
    }

    #[test]
    fn machine_names_become_one_label() {
        assert_eq!(label("kitchen.local\n"), "kitchen");
        assert_eq!(label("my_server 2"), "myserver2");
        assert_eq!(label(""), "hub");
    }
}
