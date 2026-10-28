# Star Nav

**A GPS your phone can trust when satellite navigation is jammed or spoofed.**

[Українською](README.uk.md)

When GNSS is jammed or spoofed, the phone's GPS freezes, drifts or jumps to another continent, and every
navigation app follows it. A Starlink dish on the car roof still knows where it is. Star Nav reads the dish's
position over its local network and gives it to every app on the phone as Android's GPS, through the mock
location provider. Emergency services can use it in their vehicles to keep navigating while GPS is spoofed.

**Starlink Priority plans only** (Local Priority or Global Priority): since May 2026 Starlink gives the dish's
location to the local network only on these plans. What they are:
[Starlink Help Center](https://starlink.com/support/article/1124df77-fdec-91e7-bed9-ba489cffda25).

Star Nav is an independent open-source project. It is not affiliated with, endorsed by or supported by SpaceX.
Starlink is a trademark of Space Exploration Technologies Corp.

## What it does

Five pages in the bottom bar: Position, Mock GPS, Starlink, Sessions and Settings.

- **Dish position as the phone's GPS.** The dish's best position goes to Android as the GPS provider, moved
  forward along the course by its age so it keeps up at highway speed (typically 4–8 m off while driving).
- **Inertial correction** (Auto mode, on by default). The phone's motion sensors and the dish's heading hold
  the car on its track when the dish position drifts sideways under jamming, and carry the position on by dead
  reckoning when the dish gives none, telling apps how accurate it still is. On recorded drives, 10 s into an
  outage it was about 15 m off, where holding the last position was about 100 m off.
- **Three modes.** Auto (recommended), Raw (the dish's position as reported) and Starlink only (the dish's
  GNSS-free source). Switch from the app, a Quick Settings tile or the notification.
- **Network fallback.** Android's network location (cell towers, Wi-Fi) while the dish gives no position.
- **Cut-offs and restarts.** Shows when the dish switches its location off ("moving too fast for the plan",
  about 30 s) or restarts, and why.
- **Setup checklist.** Every setting the phone needs, with its state and a button to the exact settings page.
- **Recorder and accuracy reports.** Records the dish, the phone's GNSS and motion sensors; each session gets a
  report with the track coloured by error. You export only the part you choose, without dish and router
  identifiers. Logging can be switched off completely.
- **Dish and router.** Everything the dish's local API offers, in plain words: state, alerts, charts,
  obstruction map, controls, and the router's status, devices and Wi-Fi. Every change asks first; the Wi-Fi
  password is never stored or logged. A probe shows what your dish and plan answer.
- **Maps.** Every position source on one map; street (OpenFreeMap) or Sentinel-2 satellite background, off by
  default because the tile server sees the area on screen.
- Light and dark theme, English and Ukrainian.

Planned: fusion with the phone's own GNSS when it can be trusted.

## Requirements

- A Starlink Priority plan (see above) and a dish that reports its location on the local network. Tested with
  a Gen 3 Mini; other dishes may work but are untested.
- Android 8.0 or newer ([CHANGELOG.md](CHANGELOG.md) lists the details that need a newer one).
- The phone on the dish's Wi-Fi (the dish answers at `192.168.100.1`).
- Developer options on and Star Nav chosen as the mock location app; the Mock GPS page walks you through it.

## Install

Download the APK from [GitHub Releases](../../releases/latest); the release page says which file fits your
phone. The `play` build also puts Google Play services' fused location into mock mode, so map apps follow the
dish more reliably; the `foss` build has no Google libraries. Google Play: coming. F-Droid: planned for `foss`.

Builds from different sources are signed with different keys: to switch, export the sessions you want to keep,
uninstall, then install the other build. GitHub Releases are signed with the certificate (SHA-256)
`75:94:08:AF:93:E5:57:FF:B3:C5:79:A8:CC:A4:CE:88:11:08:9F:06:8E:07:64:1B:9C:14:38:C1:7B:D4:FD:42`.

## Privacy

No accounts, analytics, ads or crash reporting. The app talks to the dish and router on your local network,
and to a map tile server only after you pick a map background. Recordings stay on the phone until you export a
part yourself. One opt-in exception: if the dish refuses to switch on its local-network location, Star Nav can
sign you in once to your own Starlink account, on Starlink's page, so Starlink switches it on; nothing is kept.
Starlink publishes no API for this: it may stop working or go against Starlink's terms, so it is at your own
risk. Details: [PRIVACY.md](PRIVACY.md).

## Safety

Use it as an aid, not as your only means of navigation. The dish's position can be off by tens of metres in
hard braking or under jamming and disappears during cut-offs; dead reckoning loses accuracy every second. Do
not operate the app while driving.

## Build from source

Requirements: JDK 21, Android SDK with platform 37.2 (`compileSdk 37.2`), build tools 36.1.

```bash
./gradlew :core:test                  # unit tests (JVM)
./gradlew :app:assembleFossDebug      # debug APK without Google libraries
./gradlew :app:assemblePlayRelease    # release APK with Play services
./gradlew :app:bundlePlayRelease      # Android App Bundle for Google Play
./gradlew :tools:installDist          # slnav desktop tools
```

Release signing reads `storeFile`, `storePassword`, `keyAlias` and `keyPassword` from `keystore.properties` in
the repository root (never commit it) or the variables `STARNAV_STORE_FILE`, `STARNAV_STORE_PASSWORD`,
`STARNAV_KEY_ALIAS`, `STARNAV_KEY_PASSWORD`; without them release builds use the local debug key.

| Module | What |
|---|---|
| `core/` | Plain Kotlin/JVM: dish gRPC client, session format, exporter, inertial filter, accuracy report |
| `app/` | The Android app (Jetpack Compose) |
| `tools/` | `slnav` desktop CLI: probe a dish, dump/export/convert sessions, a fake dish and router for development |

## Translations

App strings: `app/src/main/res/values*/strings.xml`; store texts: `fastlane/metadata/android/<locale>/`. See
[CONTRIBUTING.md](CONTRIBUTING.md#translations).

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
