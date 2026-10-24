# Changelog

All notable changes to this project are listed here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow
[Semantic Versioning](https://semver.org/).

## [Unreleased]

## [0.5.5] - 2026-10-24

### Fixed
- With the dish's GNSS jammed (its position then comes from Starlink alone) the mount offset was never learned, and
  without it a dish fix that jumped sideways started a 25 s sideways hold on the filter's own heading: apps were
  served positions up to about 250 m off for tens of seconds. Without a mount offset the filter now follows the dish
  after such a jump (as after an along-track jump); the sideways correction acts only once the mount offset is known.
- The mount offset is now learned from a noisier dish position too: from the longest straight stretch of up to 15 s
  (a less noisy course than 3-6 s) and taken once the median of the samples is precise to 0.7°, instead of requiring
  the samples to agree within 1.5°. Replays of a 29-minute drive with the dish's GNSS jammed throughout: learned
  (before: never); served positions more than 100 m from the dish's 80 s → 6 s (the moments the dish itself jumped).
  Replays of earlier drives with GNSS: the same accuracy, the same learned offset within 0.2°.
- No mount-offset samples while reversing (the course is then opposite the heading).

### Removed
- The drive-analysis tools are no longer part of this repository: `slnav report`, `slnav inertial`,
  `inertial-sweep` and `inertial-review`, and the Python scripts in `analysis/`. `slnav` keeps `probe`, `debugdata`,
  `dump`, `index`, `csv`, `export` and `fake-dish`. The app's own accuracy report (Sessions page) is unchanged.

## [0.5.4] - 2026-10-24

### Changed
- Every screen is built from one set of shared parts, for a calmer and more consistent look without hiding any
  number: the same spacing everywhere, Material type roles instead of ad-hoc font sizes, monospace for every value
  that is data (coordinates, distances, ages, rates, versions, addresses), and a state shown with an icon and words,
  never by colour alone. Loading, empty and "no answer" states look the same on every page. Buttons follow one
  ladder: one filled button for a card's main action, outlined for the others, text buttons for small nudges; a button
  that opens a destructive confirmation (factory resets, bypass mode, deleting a session) is outlined in red. Switches
  and choices have 48 dp touch rows, and a choice of a few options turns into radio rows at a large font size. Pages
  and Starlink sub-tabs fade in over 150 ms; there is no other animation.
- In a label-and-value row the value keeps one line and the label wraps (for example "rates status/meas/nmea/nav" in
  Diagnostics), unless the value alone is too long for the row.

### Fixed
- Mock GPS "apps receive" showed the first tick twice ("✓ gps ✓"); each source keeps its own ✓ or ✗.
- The session report's "Tap or drag on a chart" hint was set in monospace like a reading.

## [0.5.3] - 2026-10-19

### Added
- "Enable via my Starlink account" (Mock GPS > "How to enable it", Starlink > Controls > Location on the local
  network): an opt-in way to switch on the dish's "location on the local network" when the dish refuses the
  change from the phone (as current firmware is expected to). After a consent screen the app opens Starlink's own
  sign-in page in a WebView, takes the session once you are signed in, has Starlink's cloud set the dish's
  `location_request_mode` to LOCAL (one request addressed by the dish's own id), asks the dish over the local
  network what it reports now, and forgets the sign-in. It is the app's only contact with Starlink's servers: never
  at startup, never automatic, nothing stored (the cookies live in memory and are wiped, the WebView's data is
  cleared), no "stay signed in". The WebView has no bridge, runs no script of ours, reads no field and opens only
  starlink.com pages. Starlink publishes no API for this: it may stop working, and its terms may not allow it.
  PRIVACY.md describes it. Not yet tried against the real Starlink (the sign-in, the cookie hand-over and the cloud
  call are untested on a real account).
- The setting is also read back from the official diagnostics' `location.enabled` where neither the status config nor
  `dish_get_config` can be read.

## [0.5.2] - 2026-10-18

### Changed
- The dish's "location on the local network" setting, which the app cannot work without, is read from the status'
  own config first (so it is known even where `dish_get_config` is refused) and shows as on, off, "available (the
  dish answers anyway)" or "can't tell". Mock GPS > "How to enable it" asks the dish to switch it on with an "Enable
  on the dish" button (a refusal greys it out until "Try again") and Starlink > Controls shows the same state and
  last outcome. The dish's answer is given in plain words: on a refusal the text says it may need the Starlink app,
  signed in, to turn it on once (whether a Mini accepts this from a phone on its Wi-Fi is not verified).
- The enable command from the Setup page now goes through the command bus like every other command: it is logged
  into a running session and remembered in the capability book.

### Fixed
- A VPN running over Wi-Fi (Tailscale and others) was taken for the Wi-Fi network: after the Wi-Fi reconnected, the
  Connection page read "Wi-Fi gateway now: none found", and the router address, the Wi-Fi binding and the Setup step
  "Phone on the dish's Wi-Fi" looked at the VPN.
- "Bind to Wi-Fi network" with a VPN that apps may not bypass (Tailscale, most work VPNs): Android refuses the binding
  and the dish did not answer. The dish and the router are now asked over the default network then (it reaches them
  while the VPN leaves local addresses alone), and the Connection page, the "dish does not answer" card and the Setup
  step say so; Diagnostics shows the route as "default network (a VPN refuses the Wi-Fi binding)".
- play: Google fused mock mode did not work where the Play Store is hidden from apps (Android 11+ with GApps that are
  not force-queryable, such as MindTheGapps on LineageOS 18.1: SERVICE_INVALID). The app now declares the Play Store
  and Play services as packages it looks for. When Play services are unavailable, the Mock GPS card names the reason.
- play: Play services too old for the Google Location Accuracy read (20.45 lacks it) posted "Update Google Play
  services" ("Star Nav won't run unless you update") at every app start. The read is now tried once per Play services
  version; without it Setup reads Android's network location, as the foss build does.

## [0.5.1] - 2026-10-17

### Changed
- Runs on Android 8.0 and later (it was Android 14 and later), in the same build. What is missing or different on the
  older versions:
  - Below Android 10 (8.0 and 9): Android has no system dark theme, so "System" in the theme choice means Light
    there (Settings says so); the Quick Settings tile shows its label only, without the mode subtitle (the tile's
    state says on or off); the Setup step "Phone on the dish's Wi-Fi" opens the full Wi-Fi settings screen instead of
    the Wi-Fi panel; recordings have no elapsed-realtime fields in the GNSS clock, no signal code type in the raw
    measurements and no elapsed-realtime uncertainty in phone locations (these Android versions cannot tell). The
    Setup step "Location turned on" reads the location mode setting. Android 8 only: no "background restricted"
    state (Android 9 added it) and no chipset model name or year of hardware in recordings and the Diagnostics page
    (Android 9 added them).
  - Below Android 12: no full GNSS tracking in recordings. The raw measurements are requested the one way those
    versions have, so the chipset may duty-cycle them and leave gaps; a recording's measurement batches say
    `full_tracking` false (Android 12 and 13 do ask for full tracking but cannot report whether they got it, so it is
    reported only from Android 14). The Light / Dark / System choice applies inside the app only: the app's own look,
    the system bar icons and the window background follow it, while the first frame at a cold start and Android's own
    dialogs follow the system theme. Android has no "fused" location provider to replace on Android 8 to 11
    (apps cannot ask for it): the mock replaces gps and network, and Google's fused location in the play build. The
    Setup step "Battery: unrestricted" has no such choice in the app's own battery page: it says "not optimised" and
    opens the list of battery optimisation instead.
  - Below Android 11: no baseband C/N0 or inter-signal biases in raw GNSS; the recording's `gnss_capabilities` event
    lists no capabilities (Android 11: antenna info only; Android 12 and 13: measurements, navigation messages and
    antenna info; Android 14 and later: all of them).
  - Below Android 13: notifications need no permission, so the Setup step is done unless notifications are switched off
    for the app (its button then opens the app's notification settings); no per-event AGC list in raw GNSS (the
    per-measurement AGC level is used instead); the clipboard copy of the probe report is not marked sensitive.
  - Below Android 14: no mean-sea-level altitude in recorded phone locations.

## [0.5.0] - 2026-10-15

### Changed
- The app has five pages in the bottom bar: Position (start page), Mock GPS, Starlink, Sessions and
  Settings. The old Home, Setup, Details and Live map pages are gone; their content moved by topic:
  - Mock GPS: what the mock GPS is and why, the Setup checklist as "How to enable it" (live status and
    buttons to the exact settings pages; only the open steps once everything required is done), the
    switch with its status line and delivery check, what it serves (Auto, Raw, Starlink only), the
    inertial correction and network fallback switches, the mount angle (Learned or Manual, from Settings),
    what apps receive, and the "How the correction is doing" card. The page opens first while something
    required is missing, and its tab carries the count.
  - Starlink: the Dish tab's sub-tabs plus *Diagnostics* (the old Details: cut-offs, restarts, the dish's
    status and locations, the phone's GNSS and sensors) and *Connection* (dish address, transport, Wi-Fi
    binding and the router address, which were in Settings, and the Probe, which replaces the Probe sub-tab).
  - Sessions: "Record this drive" and "Record whenever the mock GPS is on" moved here from Home.
  - Settings: theme, logging, the sensor rate and About.
  - Position: the map (with the live map's sources behind an "All sources" toggle), the mock switch with a
    status line, a card each for Starlink and the phone's GPS, "Center" and "Open in Google Maps". The GPS is
    distrusted by automatic checks (far from the dish position for 5 s, impossible jumps, or both far from the
    Starlink-only position) and its card says why; Center and Maps use the GPS while it is trusted, else Starlink,
    and never a GPS that is distrusted, still being checked, or a mock fix (with no Starlink position they stay
    off and say why).
    The page keeps the location service running while it is open; satellites and interference come from the
    phone's GNSS status without recording.
- Back from a page returns to Position; from a Starlink sub-tab it returns to Overview first. The
  notification and the Quick Settings tile open the Position page.

### Added
- Logging switch (Settings > Logging, "Record sessions (logging)", on by default). Off, the app writes no
  session log at all: no recording starts from the Sessions page, the notification (its record action is
  gone), the Quick Settings tile or the mock GPS ("Record whenever the mock GPS is on" is ignored), and a
  recording in progress ends. Sessions already on the phone stay listed to open, export or delete, with a
  notice and a button to Settings. Alerts, the mock and the delivery check work as before. The retention
  period and the storage cap are enforced when the app starts, when Sessions opens and when logging is
  switched off, not only when a recording starts, so they apply with logging off too.
- About in Settings: version, licence, a summary of the privacy policy and a disclaimer; buttons to the
  privacy policy and the source appear once the project page is set.
- "Open in Google Maps" hands the shown position to the maps app only when tapped (see PRIVACY.md).

## [0.4.0] - 2026-10-15

### Added
- Dish tab with sub-tabs (Overview, History, Sky, Controls, Router, Probe), polling only while
  it is on screen. Overview: dish state, alerts in plain words (every dish alert, the
  diagnostics' alerts and the start-up hardware self-test), latency, packet loss, throughput,
  signal quality, power draw, obstructions, software and update state with "Install now" (a
  restart, tracked as one the app sent), GNSS, orientation against the best view, how long
  each stage of this power-on took, plan limits and bandwidth restrictions, battery when the
  dish has one. History: 15-minute charts of latency, packet loss, download, upload and power
  with the outages shaded, the outage list and the event log in plain words (English and
  Ukrainian for every reason the dish sends). Sky: the obstruction map as a polar picture with
  north up (a map in the dish's own frame is turned and marked approximate), blocked share, eight
  compass sectors, the largest blocked patch, data freshness, and clearing the map.
- Controls: restart, sleep schedule (local time, converted to the dish's UTC with the phone's
  zone), snow melt, update window and 3-day deferral, location on the local network, GNSS and
  radio inhibit, stow and unstow (only where the dish says it has motors), check for update and
  factory reset. Each asks first and says what will happen; a destructive one needs a word typed;
  each shows its last outcome in plain words, including a refusal. A control the dish does not
  implement or refuses this client is greyed out with the dish's own answer (kept per device in
  the app's private storage) until "Forget what the dish refused".
- Command bus: every command goes through one place. While the location service runs it sends the
  command, logs it into the session as a dish call and feeds the restart tracker, so a restart
  sent from the app is reported as "Restart sent from Star Nav"; without the service the command
  is sent directly.
- Router tab: *Status* (link to the dish, WAN and public address, pings to the internet, the
  satellite network and the dish, Wi-Fi channel load, alerts in plain words, software update),
  *Devices* (name, address, band, signal, link rates, usage; this phone marked by its address on the
  Wi-Fi; rename, pause and resume a device, never this phone), *Wi-Fi* (networks per band, hidden
  and guest networks, passwords masked with a "Show" button that closes when the app leaves the
  screen; name, one name or one per band, password, hide the name, add or remove a guest network),
  *History* (ping latency and loss charts, drop rates to the satellite network, Google, Cloudflare and
  the DNS servers, the router's event log in plain words) and *Controls* (restart, check for update,
  DNS and content filter, LAN address, an opt-in speed test (Star Nav asks the router not to send its result to Starlink), bypass
  mode, factory reset). A Wi-Fi change always starts from a fresh read of the router's settings; those
  that can drop the phone (name, password, hiding, DNS, subnet, bypass) say so, need a word typed where
  they cannot be undone from the app (name, password, subnet, bypass with a tick box as well, reset), and
  show a reminder to join the network again. The router's answers stay in memory while the tab is on
  screen, are dropped when it is left and kept without passwords and keys while the app is in the background;
  they are never stored. The answer to a command you send is logged
  into a running recording with passwords and keys removed. While a Wi-Fi password is shown or typed, the
  window cannot be captured (no screenshots, a blank preview in the recent apps).
- Router address: the Wi-Fi network's default gateway, or one typed in Settings (the Router
  tab shows it); the Probe tab now also probes the router with its read-only calls and what
  answers on port 9005, and has a switch for identifiers (ids, MAC, WAN and public addresses, network names) apart
  from the one for coordinates. Router replies are never written to a recording, an export or the
  capability memory with passwords.
- Debug data export (Overview): one JSON file with the dish's and router's answers through the
  share sheet. Identifiers (device ids, network names, client names, MAC and public IP addresses) are left
  out unless included, the private addresses of your local network stay; Wi-Fi passwords and any position
  are never in it (the diagnostics' location is now cleared even when identifiers are kept).
- Inertial correction in Auto mode (on by default; a switch on Home turns it off for A/B
  comparisons, Raw and Starlink only are unchanged): the dish position goes through a filter on
  the phone's accelerometer and gyroscope and the dish heading. It holds the car on its track
  when the dish position drifts off to the side, dead-reckons through dish outages (cut-offs,
  restarts) with an honest accuracy until it passes about 300 m (or its sideways uncertainty about 50 m), and uses the network
  fallback's cell/Wi-Fi positions as a weak input during long outages. Home, the notification,
  the cut-off alert, Details (new Inertial card) and the live map show its state; its events go
  into the session log, and the session report shows its dead-reckoning and sideways lanes and
  its error next to the plain dish fix's.
- Mount offset: Learned (by the inertial correction while driving, kept with the time it was
  learned) or Manual. An offset entered before is kept as Manual. Session headers record the
  mode, both values and the correction switch.
- Light and dark themes: System (default), Light or Dark, chosen at the top of Settings. The
  window, status and navigation bars, map tiles (OpenFreeMap dark or positron), the plain map,
  the accuracy ramp and the live map's source colours follow the theme.

### Changed
- With the inertial correction on, the network fallback's positions go into the correction
  (straight to apps only while it has nothing to serve), and only pushes based on a dish fix
  hold the fallback back. While the correction has nothing to serve at a dish fix (starting
  up, after a filter reset) apps get the plain dish fix as before.
- The phone's accelerometer and gyroscope are also read while the mock output is on in Auto
  mode with the correction on (one registration shared with the recorder, unbatched only
  then); a recording without the correction reads them as before.
- Manual mount offset starts empty (or from the learned value) instead of 0°.

### Fixed
- Settings' number fields no longer jump while typing (typing −176.5 over −177.0 stored −170).
- The session report's last time label is no longer cut off at the right edge.
- No "Handler on a dead thread" warning when the recorder's sensors stop.

## [0.3.0] - unreleased

### Added
- Map backgrounds for session tracks: Plain (as before, no network), Map (OpenFreeMap street
  map from OpenStreetMap data) or Satellite (EOX Sentinel-2 cloudless 2017, 10 m per pixel).
  Off until you pick one, because tile servers see the area on screen. A full-screen view
  can be moved and zoomed. MapLibre Native draws the tiles; the app draws its tracks on top.
- Live map: every position source at once (dish Auto, GPS and Starlink-only; the phone's GPS,
  network, Android fused and Google fused; what apps get), each with its accuracy circle,
  the distance to what apps get, course arrows and the dish heading, the last two minutes of
  track, and a panel with accuracy, speed, course, age and the satellites/jamming state of
  dish and phone. Follows the car until moved. Works with the mock off too.
- Network fallback (Auto mode, on by default): after 5 s without a dish position, apps get
  Android's own network location (cell towers and Wi-Fi) until the dish answers again. Shown
  on Home and in the notification, logged,
  and a lane in the session report. Setup then recommends Google Location Accuracy on.

- `slnav report ... --map regular|satellite`: the HTML page draws the track over OpenFreeMap or
  EOX tiles with MapLibre GL JS (pinned, integrity-checked, from jsDelivr), keeping the cursor,
  classes and cut-offs; such a page holds real coordinates and says so. Without `--map` the
  page is unchanged (metres from the start only).

### Changed
- APKs per ABI (arm64-v8a, armeabi-v7a, x86_64) plus a universal one.

## [0.2.1] - unreleased

### Added
- Dish restart detection: the app notices when the dish restarts (its uptime goes back after a
  stretch without answers), tells why — an installed software update with its new version, a
  restart requested from an app, a power cut, overheating, lost network, a fault, or the dish's
  own reason code — and how long there was no dish position. Shown on Home and Details, as a
  notification, logged as a `dish_reboot` event, and in the session report (timeline lane and
  list, also in `slnav report`); older recordings get it too.
- Details shows the dish's software update state (e.g. waiting for a restart, with the planned
  time); Home warns when a restart for an update is less than an hour away.
- `slnav fake-dish` restarts like a real dish (silent for `--reboot-down` seconds, installs its
  waiting update; `--reboot-every` for periodic restarts); `slnav dump` and the probe show
  uptime, boot count, update state and reboot reasons.

## [0.2.0] - unreleased

### Added
- Home page with the mock GPS switch and its mode: Auto (recommended; the dish's fix moved
  forward by its age, later fusion), Raw (the dish's position as reported) and Starlink only
  (the dish's GNSS-free source, with a warning when it stops changing). The Status page lives
  on as Details and keeps its own switch.
- The mock runs without recording; "Record whenever the mock GPS is on" (default on) starts
  and ends a recording with it.
- Permanent notification while the service runs: mode, position age and accuracy, whether
  apps receive it, cut-offs; buttons for mock on/off, recording and stop. Its icon stays in
  the status bar (silent channel at default importance).
- Quick Settings tile "Mock GPS" (on/off, shows the mode).
- Setup checks Google Location Accuracy: on the dish's moving Wi-Fi, Google's Wi-Fi
  positioning can pin the phone to wherever that network was seen before.
- Setup page: every setting the app needs with its live state and a button that opens the
  exact settings page (location permission, location on, developer options, mock location
  app, dish Wi-Fi, dish location sharing — which the app can switch on —, notifications,
  battery, and a check that apps receive the dish position).
- The mock replaces Android's network and fused providers as well as GPS; the `play` build
  also puts Google Play services' fused location (used by Google Maps) into mock mode.
- Delivery check: every second the app compares what each location source hands to apps
  with the pushed position, shows it on Status and Setup, logs it, and the session report
  sums it up per source.
- Two builds: `foss` (no Google libraries) and `play`.
- English and Ukrainian user interface; the app appears under Android's per-app language
  settings.
- Bottom navigation: Home, Dish, Sessions, Setup, Settings.
- Dish commands in the core library (reboot, stow, GNSS/RF inhibit, local location access,
  config, history, obstruction map) for the coming dish screens.
- Open-source release preparation: GPL-3.0-or-later license, README, privacy policy,
  store listing texts, GitHub Actions for builds and releases.

### Changed
- App name: Star Nav (was Starlink Nav).
- No permission prompt at launch; the Setup page asks when you choose to.

## [0.1.4] - 2026-10-14

### Added
- Speed-policy cut-off detection: Status card, silent heads-up notification, `cutoff`
  events in the recording.
- Accuracy report for every session (map coloured by error, share by class, conditions,
  speed and error charts, cut-off list); `slnav report` writes it as an HTML page.
- App version on the Config screen.

### Changed
- The mock output moves each dish fix forward along the course by its age (about 0.45 s).

## [0.1.3] - 2026-10-14

### Added
- Recording of GNSS navigation messages.

### Fixed
- No false spoofing warning from the frozen Starlink-only source.

### Removed
- The STARLINK source as a mock source (it stays frozen during a drive).

## [0.1.2] - 2026-10-14

### Fixed
- The dish poller no longer backs off for a minute after the speed-policy disablement.

## [0.1.1] - 2026-10-14

### Changed
- Fixes from the first real dish probe (firmware 2026.05.13).

## [0.1.0] - 2026-10-13

### Added
- First version: dish gRPC client, probe, recorder, session list with track view,
  trimmed and redacted export, mock GPS pass-through.
