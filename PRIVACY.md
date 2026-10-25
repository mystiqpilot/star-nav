# Privacy policy — Star Nav

Last updated: 2026-10-26

Star Nav is an open-source Android app that serves the position of a Starlink dish to
the phone as its GPS. This policy covers every build of the app (GitHub, Google Play,
F-Droid).

## Summary

Star Nav does not collect, sell or share personal data. It has no accounts of its own, analytics,
advertising, crash reporting or tracking, and it sends nothing to the developer. Map
backgrounds, off until you pick one, download tiles from third-party servers, which see the
area on screen. The network fallback reads Android's own network location, which on most
phones Google provides under the phone's location settings. Both are described below. One more
optional feature, off until you start it yourself, signs you in to your own Starlink account once
to switch on a setting of your dish; it is the only time the app contacts Starlink's servers (see
"Starlink account sign-in" below).

## What the app processes, and where it stays

| Data | Why | Where it goes |
|---|---|---|
| Precise location from the phone's GNSS, and the dish's position | To serve the dish's position as the phone's GPS, to compare it with the phone's own GNSS, and to record drives you start | Processed on the phone. Recordings are stored in the app's private storage. |
| Raw GNSS measurements, satellite status, navigation messages, motion sensors | Recording and accuracy analysis | The app's private storage |
| Motion sensors (accelerometer, gyroscope) while the mock output is on in Auto mode with the inertial correction | Correcting the dish position and carrying it on through dish outages | Read and used on the phone only, in memory; nothing leaves the phone. Stored only as part of a recording you run. |
| The learned mount angles (how the dish and the phone sit in the car) | The inertial correction | The app's settings; no coordinates |
| Answers of your Starlink dish and router (status, telemetry, identifiers) | Showing the dish state, recording | The app's private storage. Identifiers are removed when you export. |
| What your dish and router tell the Starlink page (state, alerts, charts, obstruction map, and the router's address, status, connected devices with their names and addresses, Wi-Fi networks and the Wi-Fi password) | Showing them on the phone | Read while the tab is on screen and the app is in front, and shown on the phone only. Router answers are kept in memory only: they are dropped when you leave the Router tab, and when the app goes to the background it keeps only copies without Wi-Fi passwords, secrets and keys (the screen's last full copy is released when the app is in front again or closed). They are never written to a recording or an export raw: Wi-Fi passwords, RADIUS secrets and keys are removed before anything is stored. The password is shown masked, with a "Show" button; while it is shown or typed, the window cannot be captured (no screenshots, a blank preview in the recent apps). A password you type to set a new one is sent to the router and not stored. |
| What each dish and router call answered last (accepted, not implemented, refused), under a hash of the device's id | Greying out controls the device does not offer | The app's private storage. No payloads, no passwords, no ids. |
| App settings | Your configuration | The app's private storage |

Recordings are deleted automatically after the retention period you set (default 7 days)
or when the storage cap is reached, and you can delete any session yourself. The period and the
cap are enforced when the app starts, when you open the Sessions page, when you switch logging
off and when a recording starts, so they apply with logging off as well. Backups to
Google Drive and device-to-device transfers are disabled for this data.

**Logging can be switched off completely** (Settings > Logging, "Record sessions"; it is on by
default). With it off the app writes no recordings at all: nothing starts one, not the Sessions
page, the notification, the Quick Settings tile or the mock GPS. The mock GPS, the alerts and
the delivery check work as before, and recordings already on the phone stay until you delete
them or the retention period or the storage cap removes them.

## Network access

The app connects to the Starlink dish on your local network (by default `192.168.100.1`) and to
the router: the address you type under Starlink > Connection, or else the gateway of the Wi-Fi network the phone
is on (`192.168.1.1` on a Starlink Mini). The Router sub-tab, the Probe and Debug data send their read
requests to that gateway on whatever Wi-Fi network the phone is on, so on another network (home,
hotel, office) they reach that network's router; the requests carry no personal data. Out of the
box the app makes no connection to the internet, and it never contacts Starlink's servers unless you
start the optional account sign-in below.

**Starlink account sign-in (optional; only when you tap "Enable via my Starlink account").** The
dish's "location on the local network" setting is what makes it share its position, and on current
firmware the dish may refuse to switch it on when asked from the phone. Then, and only then, you can
let Star Nav have Starlink switch it on through your own Starlink account. This is the app's only
contact with Starlink's servers (the cloud), and it never happens at startup, in the background or
without your tap on that button and then on the consent screen that explains it.
- *What you see.* After the consent screen the app opens Starlink's own sign-in page (starlink.com)
  inside the app, in a WebView. You type your password and the verification code into Starlink's page:
  the app never receives them. The WebView has no bridge to the app, the app injects no script into the
  page and reads none of its fields (JavaScript is on only so that Starlink's page works), and it
  opens only https pages of starlink.com and its sub-domains. A WebView host could in principle read
  what is typed into the page; Star Nav does not, and its source code is public, so you can check. In
  release builds the window cannot be captured (no screenshots, a blank preview in the recent apps)
  while the sign-in page is shown. Starlink's page is a web page like any other and loads content and
  scripts from the companies Starlink uses (an emulator check saw a fraud-prevention service among
  them); Star Nav does not control or filter that, it happens only while the page is open, and it is
  Starlink's page doing it, under Starlink's own privacy policy.
- *What it sends, to whom.* Once the page reports you are signed in, the app takes the session
  cookies Starlink's page set and sends them (a whole-account sign-in) to `starlink.com` and
  `api.starlink.com`, and nowhere else, with four kinds of request: a check that the sign-in is live
  (this returns your account's e-mail address, which is shown on the result screen), a read of the
  list of your Starlink dishes (to check that your dish is among them), the one request that sets
  "location on the local network" on your dish (addressed by the dish's own id, which the app read from
  the dish on the local network), and, only if Starlink refuses the cookies alone, a request for a
  fresh access token. The requests identify the app and its version in the `User-Agent`.
- *What stays.* Nothing. The cookies are held in memory only, never written to storage, a log, a
  recording, an export or Debug data, and are deleted as soon as the operation ends (also when you
  cancel). The WebView's own cookies, storage and cache are wiped as soon as the cookies were taken
  and again when the page closes (and, if the app was killed while the page was open, the next time
  the app starts). The e-mail address, the dish list and Starlink's reply are not kept; the dish keeps
  the setting itself. There is no "stay signed in": the next time you do it you sign in again.
- *Terms.* Starlink publishes no interface for this. Star Nav uses the one Starlink's own website
  uses, so it may stop working at any time, and Starlink's terms may not allow other apps to use it.
  It acts only on your own dish and account, once, when you ask, and any consequence for the account
  is yours; the consent screen says so before anything starts.

**Open in Google Maps (only when you tap it).** The Position page has an "Open in Google Maps"
button. Only when you tap it does the app hand the position shown on that page to a maps app, as
an Android `geo:` link: Google Maps if it is installed, otherwise the maps app you pick in
Android's chooser. That app then handles the position under its own privacy policy. Star Nav
itself sends nothing anywhere, and the button works the same in the builds without Google
libraries.

**Map backgrounds (off by default).** Maps draw tracks and positions on a plain background
until you pick a background yourself ("Map" or "Satellite"). Then the app downloads map
tiles for the area on screen, which tells the tile server that area, your IP address and the
app's name and version. The servers are OpenFreeMap (`tiles.openfreemap.org`, street map
from OpenStreetMap data) and EOX IT Services (`tiles.maps.eox.at`, Sentinel-2 imagery). Tiles
are cached on the phone. Switch back to "Plain" to stop all tile requests.

**Network fallback (on by default, Auto mode, mock output on).** When the dish has given no
position for 5 seconds, the app reads Android's own network location (cell towers and
Wi-Fi) until the dish answers again: with the inertial correction on it goes into the
correction's dead reckoning on the phone and is passed to apps only while the correction has nothing
to serve, otherwise it is passed to apps. On phones with Google Play
services that position comes from Google Location Accuracy, a phone setting Google processes
under its own privacy policy for every app that asks; Star Nav itself sends nothing anywhere,
and with that setting off the fallback simply gets no position. Switch it off on the Mock GPS
page.

The `play` build includes Google Play services' location library, used only to set
Google's fused location provider to the dish's position while you have the mock output on.
Google Play services' own handling of location is governed by Google's privacy policy.

## Sharing

Nothing leaves the phone unless you export a recording or Debug data (or use the optional
Starlink account sign-in, described above, whose requests go to Starlink only). When you export a
recording, you choose the start and end of the part to share, and you choose the app it goes
to through Android's share sheet. Exports contain precise positions from that part of the
recording.

**Debug data** (Starlink page, Overview) saves what the dish and the router answer at that moment
as one JSON file and offers it to the share sheet; you choose where it goes. It holds the
state, alerts, history, settings and counters the two devices report, with device ids, router
ids, network names (SSIDs), client names, MAC addresses and public IP addresses left out unless
you switch "include identifiers" on (it is off by default); the private addresses of your local
network (such as the dish's 192.168.100.1) stay in. It never holds Wi-Fi passwords, RADIUS secrets
or keys, or any position (the dish's location is cleared even when identifiers are included).
The router's data (its status, the devices on its Wi-Fi with their names and addresses, and its Wi-Fi settings) is shown on the phone only, apart from this file. The speed test (off until you switch it on) is run by the router against internet test servers, which see your connection like any speed test does; Star Nav asks the router not to send the result to Starlink (its "send telemetry" flag off), which has not been checked on a real router.

Commands you send to the dish or router (restart, sleep schedule, GNSS and radio switches, Wi-Fi name and password, pausing a device, DNS, and so
on) go to those devices on your local network and nowhere else; while a recording runs they are
logged in it like any other dish call, with router answers stripped of secrets.

## Google Play data safety

The Data safety section of the Google Play listing declares what leaves the phone, as described above:

- **Approximate location: collected and shared, optional.** Only when you pick a map background: the tile
  servers (OpenFreeMap, EOX) learn the area on screen. Purpose: app functionality. Not with "Plain".
- **Device or other IDs and User IDs: collected, processed ephemerally, optional, not shared.** Only during the
  optional Starlink account sign-in: the dish's id and your Starlink session go to Starlink, at your request,
  after a consent screen, and nothing is kept. Purpose: app functionality.
- Nothing else. Precise location, recordings, sensor data and the dish's and router's answers are processed
  and stored on the phone only; exports go only where you send them.
- All data that leaves the phone is encrypted in transit (HTTPS). The developer receives and keeps no data,
  so there is nothing to ask the developer to delete; recordings on the phone are deleted from the Sessions
  page or by uninstalling the app.

## Permissions

- **Precise location** (`ACCESS_FINE_LOCATION`): to read the phone's GNSS for comparison
  and recording, and to run the location foreground service.
- **Foreground service — location**: keeps recording and the mock output running while the
  screen is off, with a visible notification.
- **Mock location** (selected in Developer options by you): to serve the dish's position
  as the phone's GPS.
- **Notifications**: the recording notification and cut-off alerts.
- **Network access**: to talk to the dish and router on the local network, to download
  map tiles when you choose a map background, and to reach Starlink's servers during the optional
  account sign-in.

The app does not request background location access.

## Children

The app is not directed at children.

## Changes

Changes to this policy are published with the app's source code in this repository.

## Contact

Questions or concerns: open an issue in this repository.
