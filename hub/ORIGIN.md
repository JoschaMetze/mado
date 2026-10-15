# Origin

Started 2026-10-15 as a copy of `hub/` from https://github.com/abradburne/mado (commit 5999bd1,
MIT licence, see LICENSE.mado). Our changes:

- `calendar.rs`: the owner's Outlook calendar via Microsoft Graph. Device-code sign-in from the
  hub page (public client app registration, delegated Calendars.Read + offline_access), refresh
  token in `MADO_DATA/calendar.json` (0600), `calendarView` polled every 3 minutes for today +
  N days with `Prefer: outlook.timezone`, pushed to frames as `{"t":"agenda", events:[…]}` on
  change and on connect. Only time, title (or "Busy"), kind of place and response reach the
  frame. Nudges N minutes before a meeting. `GET /api/agenda`, `POST /api/calendar/refresh`,
  MCP tool `frame_agenda`, and a Calendar card on the page (settings, sign in/out, status).
  Dependencies added: chrono, chrono-tz.
