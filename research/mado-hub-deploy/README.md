# Mado hub on Openship (VM <vm-lan-ip>)

Deploy unit = the `hub/` folder of abradburne/mado, built from its own Dockerfile.
`openship.json` below is kept next to a checkout of the hub; Openship's docs (2026-10-15) do
not document a top-level `dockerfile` key, so the hub is declared as one compose-style
service with `build` + `dockerfile`, which the reference does document.

Ports on the VM:
- 7878 -> hub web page, HTTP API, MCP. Reach it over the tailnet only (ufw rule + tailscale serve).
- 7879 -> frame-only socket (MADO_FRAME_BIND). Reach it from the LAN (the frame).

Persistent data: the hub's `/data` (config, projects, painting cache).
