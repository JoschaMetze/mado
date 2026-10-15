# Outlook calendar on the frame: design

Drafted 2026-10-15. Account: a Microsoft 365 work tenant (hochfrequenz.de), events carry
attendees, Teams/Zoom links and bodies. The frame shows **time, title, location kind** only,
never attendees or bodies, and has a "busy blocks only" switch for guests in the room.

## Source: Microsoft Graph (preferred)

- App registration in the tenant: "Mado frame", single tenant, public client flows enabled,
  delegated `Calendars.Read`, `offline_access`, `User.Read`. No client secret: the hub uses the
  **device code flow** (hub page shows a code + link, you sign in once on any browser), keeps the
  refresh token in `MADO_DATA/calendar.json` (mode 0600), and refreshes silently.
- Poll `GET /me/calendarView?startDateTime=…&endDateTime=…` every 3 minutes for today + tomorrow
  (+ a cheap check for the week), header `Prefer: outlook.timezone="Europe/Berlin"`,
  `$select=subject,start,end,isAllDay,showAs,isCancelled,location,onlineMeeting,responseStatus,
  organizer,sensitivity,importance`, `$orderby=start/dateTime`, `$top=50`.
  calendarView expands recurring series, so no RRULE handling in the hub.
- Fallback if the tenant forbids app registrations: a published ICS link (Outlook → Settings →
  Calendar → Shared calendars → Publish). Needs RRULE expansion (`rrule` crate) and lags.

## Hub (`hub/src/calendar.rs`)

- Config (hub page, "Calendar" section): tenant id, client id, timezone, horizon (days),
  "show titles / busy only", "nudge N minutes before a meeting" (0 = off), include declined / 
  tentative (default: hide declined). Stored in `config.json` like the other settings.
- State: `agenda: Vec<Event>` with `{id, title, start, end, all_day, show_as, online, location,
  response, organizer_is_me, cancelled}`; `updated_at`; last error.
- Push `{ "t": "agenda", "events": [...], "updated": <epoch>, "titles": bool }` to frames on
  every change and on connect; `GET /api/agenda` for agents and a `frame_agenda` MCP tool.
- Reminders: when a non-all-day, non-declined event starts in N minutes, send a `nudge`
  ("In 5 min · Daily MAD · Teams") once per event.

## Frame (`frame-app`)

- Dashboard: a "next up" line near the clock (like the music line): "09:30 · Daily MAD · Teams",
  or "Free until 14:00". In the extra width (landscape) / height (portrait) an **agenda column**:
  today's timeline with the current meeting highlighted, all-day items as a header.
- Agenda page from the rail: today and tomorrow in full, tap an item for its details (still no
  body or attendees).
- Night mode unaffected. Busy-only mode replaces titles with "Busy".

## Steps

1. Owner: app registration (or ICS link). 2. Hub: calendar module + page section + device-code
sign-in. 3. Frame: agenda data model + next-up line + column + page. 4. Reminders. 5. Deploy,
sign in once on the hub page, verify on the frame.

## Status 2026-10-15 evening

Hub: done and deployed (Openship project mado-hub). Frame: done and installed (agenda scene +
next-up line). Untested end to end: the Microsoft sign-in, because no app registration exists
yet. Once the owner has a tenant id + client id: hub page → Calendar → Settings → save → "Sign in
with Microsoft" → enter the code → the frame's rail gains "Agenda" and the dashboard its line.
Later: busy-only switch on the frame's rail; a week view; declining/joining from the frame
(would need Calendars.ReadWrite, not planned).

## 2026-10-15, later: live

- App registration "Mado frame" created with `az ad app create` (single tenant, public client,
  delegated Calendars.Read/offline_access/User.Read; manifest in deploy/entra-graph-permissions.json,
  ids in deploy/entra-app.env). Hub configured through its own form endpoints, device-code sign-in
  completed by the owner; the hub reads 20 events for today + tomorrow and the first reminder
  nudge fired on its own. The frame shows the real agenda; `--es scene agenda|home|music`
  switches scenes over adb (non-debug).
