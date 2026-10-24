# Star Nav

**A GPS your phone can trust when satellite navigation is jammed or spoofed.**

[Українською](README.uk.md)

When GNSS is jammed or spoofed, a phone's GPS freezes, drifts or jumps to another
continent, and every navigation app follows it. A Starlink dish on the car roof keeps
knowing where it is. Star Nav reads the dish's position over its local network and serves
it to all apps on the phone as Android's GPS, using Android's mock location provider.

Star Nav is an independent open-source project. It is not affiliated with, endorsed by or
supported by SpaceX. Starlink is a trademark of Space Exploration Technologies Corp.

## Screens

The bottom bar has five pages.

- **Position** (start page). A map with the dish's position and the phone's GPS, a card for
  each (position, accuracy, age, and its state in plain words; for the GPS also why it is
  distrusted when it looks spoofed), a "Center" button (on the GPS while it is trusted, else on
  Starlink) and "Open in Google Maps" (the position goes to your maps app only when you tap
  it). Tapping coordinates copies them. A small line with a switch shows and sets the mock GPS;
  a toggle puts every position source on the map. While the page is open the location service runs
  (with its notification), so positions update without the mock GPS.
- **Mock GPS.** What the mock GPS is, how to enable it (the checklist with live status and
  buttons to the exact settings pages), the switch and its settings: what it serves (Auto, Raw,
  Starlink only), the inertial correction, the network fallback, the mount angle, what apps
  receive and how the correction is doing.
- **Starlink.** Everything about the dish and its router: Overview, History, Sky, Controls,
  Router, Diagnostics, and Connection (dish and router address, transport, Wi-Fi binding, and the
  probe).
- **Sessions.** The recording controls and the list of recordings, each with its accuracy
  report, trimming and export.
- **Settings.** Theme, logging on or off, how long recordings are kept, the sensor rate, and
  About (version, licence, privacy, source).

## What it does

- **Dish position as the phone's GPS.** Passes the dish's best position (`AUTO`, about
  1.6 m reported, 4–8 m typical against the phone's own GNSS while driving) to Android as
  the GPS provider. Each fix is moved forward along the course by its age (about 0.45 s on
  arrival), which cuts the error at highway speed.
- **Inertial correction (Auto mode, on by default).** The dish position goes through a filter
  on the phone's motion sensors (accelerometer and gyroscope) and the dish's own heading: when
  the dish position drifts off to the side of the road, as it does under jamming, the filter
  holds the car on its track; when the dish gives no position (speed-policy cut-off, restart,
  lost Wi-Fi), it carries the position on by dead reckoning and tells apps how accurate it
  still is, until its accuracy would pass about 300 m or its sideways uncertainty about 50 m. On the recorded drives, 10 s into an
  outage it was typically about 15 m off where holding the last position was about 100 m off.
  It learns how the phone sits in its mount and the dish's mount angle while driving (or
  takes the angle from the Mock GPS page). A switch there turns it off for comparisons.
- **Map of every source.** On the Position page, behind the "All sources" toggle: every position source on one map (dish Auto, GPS and Starlink-only, the
  phone's GPS, network and fused locations, what apps get) with accuracy circles, distances
  between them, course and dish heading, short tracks and the satellite and jamming state.
- **Map backgrounds.** Tracks and the live map on a street map (OpenFreeMap) or Sentinel-2
  satellite imagery (EOX); off by default, since tile servers see the area on screen.
- **Network fallback.** When the dish gives no position, Android's own network location
  (cell towers and Wi-Fi) is used until the dish answers again: with the inertial correction
  it is a weak input of the dead reckoning (apps get it as it is only while the correction
  has nothing to serve), without it apps get it as it is.
- **Dish restart detection.** Notices when the dish restarts and tells why (an installed
  software update, power cut, overheating, lost network, a fault...) and how long there was
  no dish position; also warns before a planned restart for a software update.
- **Speed-policy cut-off detection.** Some Starlink plans switch the dish's location off
  for about 30 s when the car is "moving too fast for the plan". Star Nav shows it on the
  Position page, raises a silent heads-up notification and logs it.
- **One switch, three modes.** Auto (recommended), Raw (the dish's position as reported) and
  Starlink only (the dish's GNSS-free source). Also from a Quick Settings tile and from the
  permanent notification, which shows whether apps really receive the position.
- **Setup checklist** (Mock GPS page). Every setting the phone needs (location permission, developer
  options, mock location app, battery, Wi-Fi, dish location access) with its current state
  and a button that opens the exact settings page.
- **Recorder.** (Sessions page; Settings can switch logging off completely, and then nothing is
  recorded.) Logs every dish answer, the phone's GNSS (fixes, satellites, raw
  measurements, navigation messages, AGC) and motion sensors into a session file on the
  phone.
- **Accuracy report per session.** The track coloured by the error of the served position,
  cut-offs, interference and speed on one time axis, and the inertial correction replayed over
  the drive: where it dead-reckoned or held the dish position off to the side, and its error
  next to the plain dish fix's.
- **Private export.** You choose the start and end of what leaves the phone; dish and
  router identifiers are removed.
- **Starlink page.** Everything the dish's local API offers, in plain words, as sub-tabs:
  *Overview* (state, alerts, signal, latency, drop rate, throughput, power draw, obstructions,
  software and update with an "Install now", GNSS, orientation, how long the last start-up took,
  plan limits), *History* (the last 15 minutes as charts, outages and the dish's event log),
  *Sky* (the obstruction map, blocked share, eight compass sectors, clear the map), *Controls*
  (restart, sleep schedule, snow melt, update window, location on the local network, GNSS and
  radio inhibit, stow where the dish has motors, check for update, factory reset; each asks first
  and says what will happen, a destructive one needs a word typed, and a control the dish does
  not implement or refuses is greyed out with its own answer), *Router* (see below), *Diagnostics* (what the app sees of the dish, restarts and cut-offs,
  the phone's own GNSS and sensors) and *Connection* (the dish and router address, transport,
  Wi-Fi binding and the probe).
  It polls only while it is on screen.
- **Router sub-tab.** The router built into the Starlink Mini (the Wi-Fi network's gateway, or an
  address typed under Connection), in five parts: *Status* (link to the dish, WAN and public address,
  pings to the internet, the satellite network and the dish, Wi-Fi channel load, alerts in plain
  words, software update), *Devices* (name, address, band, signal, link rates and usage; this
  phone marked; rename or pause a device), *Wi-Fi* (networks per band, hidden and guest networks,
  the password masked behind a "Show" button; change the name, give each band its own name or
  one for both, the password, hide the name, add or remove a guest network), *History* (ping
  charts and the router's event log) and *Controls* (restart, check for update, DNS and content
  filter, LAN address, an opt-in speed test, bypass mode, factory reset). Every change asks first
  and says what will happen, including that the phone leaves the Wi-Fi and has to join again;
  Wi-Fi name and password, subnet, bypass mode and factory reset need a word typed. A change
  always starts from a fresh read of the router's settings. The router's answers hold the Wi-Fi
  password, so they stay in memory while the tab is on screen and are never stored; the password is
  never logged, and the answer to a command you send goes into a running recording with passwords and
  keys removed. While a Wi-Fi password is shown or typed the window cannot be captured.
- **Dish probe.** One tap shows which location sources, telemetry and features your dish,
  its router and your plan answer.
- **Debug data.** Saves what the dish and router answer as one JSON file for support. Identifiers
  (device ids, network names, client names, MAC and public IP addresses) are left out unless you
  include them; Wi-Fi passwords and any position are never in it.
- **Light and dark theme.** Follows Android's setting, or pick Light or Dark at the top of
  Settings; maps and charts follow it.
- English and Ukrainian, ready for more languages.

Planned: fusion with the phone's own GNSS when it can be trusted.

## Requirements

- Android 8.0 or newer. A few details need a newer Android (a dark theme that follows the system from Android 10, full
  GNSS tracking in recordings and the system-wide theme from Android 12, for example); [CHANGELOG.md](CHANGELOG.md)
  lists them.
- A Starlink dish that reports its location on the local network. Tested with a Gen 3 Mini
  (firmware 2026.05) on a Priority plan; other dishes may work but are untested.
- The phone connected to the dish's Wi-Fi (the dish answers at `192.168.100.1`).
- Developer options enabled and Star Nav selected as the mock location app; the in-app
  Mock GPS page walks you through it.

## Install

- **GitHub Releases:** download the APK from the [releases page](../../releases). There are
  two builds:
  - `foss`: uses only Android's own location services, no Google libraries.
  - `play`: also switches Google Play services' fused location into mock mode, which makes
    apps such as Google Maps follow the dish more reliably.
- **Google Play:** coming.
- **F-Droid:** planned for the `foss` build.

Builds from different sources are signed with different keys: to switch between them,
export the sessions you want to keep, uninstall, then install the other build.

The APKs on GitHub Releases are signed with the certificate
`75:94:08:AF:93:E5:57:FF:B3:C5:79:A8:CC:A4:CE:88:11:08:9F:06:8E:07:64:1B:9C:14:38:C1:7B:D4:FD:42` (SHA-256).

## Privacy

No accounts, analytics, ads or crash reporting. The app talks to the dish and router on your
local network; it downloads map tiles only after you pick a map background, and the tile
server then sees the area on screen. Recordings stay in the app's private storage until you
export a part of one yourself. The only exception, off until you tap it: if the dish refuses to switch on
"location on the local network" from the phone, Star Nav can sign you in to your own Starlink account, on
Starlink's own page and once, so Starlink switches it on; nothing is kept afterwards, Starlink publishes no API
for it (it may stop working, and its terms may not allow it) and it is at your own risk.
Details: [PRIVACY.md](PRIVACY.md).

## Safety

Use it as an aid, not as your only means of navigation. The dish's position can be off by
tens of metres in hard braking or under jamming, and it disappears during speed-policy
cut-offs; the inertial correction's dead reckoning grows less accurate with every second.
Do not operate the app while driving.

## Build from source

Requirements: JDK 21, Android SDK with platform 37.2 (`compileSdk 37.2`), build tools 36.1.

```bash
./gradlew :core:test                  # unit tests (JVM)
./gradlew :app:assembleFossDebug      # debug APK without Google libraries
./gradlew :app:assemblePlayRelease    # release APK with Play services
./gradlew :app:bundlePlayRelease      # Android App Bundle for Google Play
./gradlew :tools:installDist          # slnav desktop tools
```

Release signing reads `keystore.properties` in the repository root (never commit it):

```properties
storeFile=/path/to/release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

or the environment variables `STARNAV_STORE_FILE`, `STARNAV_STORE_PASSWORD`,
`STARNAV_KEY_ALIAS`, `STARNAV_KEY_PASSWORD`. Without them, release builds are signed with
the local debug key, which is fine for your own phone.

Modules:

| Module | What |
|---|---|
| `core/` | Plain Kotlin/JVM: Starlink dish gRPC client, session log format, exporter, cut-off tracker, latency compensation, inertial filter, accuracy report |
| `app/` | The Android app (Jetpack Compose) |
| `tools/` | `slnav` desktop CLI: probe a dish, dump/export/convert sessions, a fake dish and router for development |

## Translations

Strings live in `app/src/main/res/values/strings.xml` (English) and
`values-<language>/strings.xml`. Store texts live in `fastlane/metadata/android/<locale>/`.
See [CONTRIBUTING.md](CONTRIBUTING.md#translations).

## Credits

- [Star Debug](https://github.com/ertong/star-debug) by ertong, an open-source Starlink tool made
  for the volunteer initiative Narodnyi Starlink, inspired this app: much of what Star Nav shows and
  does with the dish and the router follows its lead. Thank you for building it in the open.
- Field numbers of the dish's local gRPC API come from reflection dumps published by
  [clarkzjw/starlink-grpc-golang](https://github.com/clarkzjw/starlink-grpc-golang) and the
  documentation of [sparky8512/starlink-grpc-tools](https://github.com/sparky8512/starlink-grpc-tools).
- Navigation icons from Google's Material icons (Apache-2.0).
- Maps drawn with [MapLibre Native](https://github.com/maplibre/maplibre-native) (BSD-2-Clause).
  Street map: [OpenFreeMap](https://openfreemap.org), © OpenMapTiles, data
  © OpenStreetMap contributors. Satellite: Sentinel-2 cloudless – https://s2maps.eu by EOX
  IT Services GmbH (contains modified Copernicus Sentinel data 2017), CC BY 4.0.

## License

[GNU General Public License v3.0 or later](LICENSE).
