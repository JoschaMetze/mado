# Origin

Started 2026-10-15 as a copy of `frame-app/` from https://github.com/abradburne/mado
(commit 5999bd1, MIT licence, see LICENSE.mado). Changes for our frame are tracked here:

- MainActivity: the 1280x800 view tree is placed on a scaled stage so it fills any screen
  (1920x1080 on the Digital Picture Frame 159K): uniform scale = screen height / 800, the
  stage's logical width = screen width / scale (1422 at 1920x1080), extra width stays
  available to the layouts via `w`.
- Orientation option: the manifest no longer fixes landscape. A saved preference (`orientation`
  in the `mado` prefs: landscape, portrait, landscape-flipped, portrait-flipped; default
  landscape) is applied with `requestedOrientation`; the rail has an Orientation row that
  cycles through them, and `am start … --es orientation portrait` sets it over adb. The stage
  is refitted on configuration changes: scale = min(w/1280, h/800), so standing up the canvas
  is 1280 wide and ~2275 tall.
- Agenda: `Agenda.kt` (model, parsing of the hub's `agenda` message, next-up logic, a demo day)
  and `AgendaView.kt` (a third scene: clock, a sentence about what is next, today's timeline with
  the current meeting marked and past ones struck through, tomorrow beside it lying down or
  under it standing up). The dashboard shows the next meeting on the music's line while nothing
  plays (`DashboardView.drawAgendaLine`); tapping it opens the scene. The rail lists "Agenda"
  once the hub is signed in. Debug builds: `am start … --es agenda_demo scene|home` fakes a day.
- `--es scene home|music|agenda` switches scenes over adb (any build).
