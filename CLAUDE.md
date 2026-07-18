    # OpenDash

Personal Android companion app for **Royal Enfield bikes with the new-generation
TFT cluster** (450 platform: Himalayan 450, Guerrilla 450). Single user (just me),
Android-only, targeting a **Nothing Phone 3**. Not a product to sell — may be
open-sourced, but built around my own bike (a Himalayan 450). No user personas,
no client/enterprise concerns.

## Primary goal

Low-power **navigation projected onto the Royal Enfield Tripper Dash** (a small
**round TFT display**) without cooking the phone. The Royal Enfield app overheats
the phone because it screen-*projects* — it keeps the OLED lit and mirrors Google
Maps. OpenDash instead **renders the map off-screen and hardware-encodes H.264**, so
the phone screen can stay **OFF** during the ride. That single architectural
difference is the whole point of the project.

## Dash protocol

- Connect to the Tripper Dash using the **better-dash** protocol as reference:
  https://github.com/norbertFeron/better-dash
- After an auth handshake, the dash decodes an **H.264/RTP stream over UDP port
  5000**. It does not care what produces the video.
- **Unverified against my hardware:** the handshake/connection details vary by
  firmware. My dash runs firmware **11.63** — the control-plane + auth must be
  validated against it before anything else is trusted. This is the make-or-break
  gate; treat it as Phase 1, step 1.

## Core user flow

1. I share a destination from **Google Maps** into OpenDash.
2. OpenDash previews the route, I tap **Send to Dash**.
3. While riding, the dash shows the map; I use the bike's **physical joystick** to
   pan/zoom. The phone screen stays off.

## Tech stack

- **Language:** Kotlin (Android, native).
- **Map rendering:** off-screen render → `MediaCodec` hardware H.264 encode →
  `MediaCodec`/RTP to the dash on UDP/5000.
- **Maps (decided, built):** MapLibre with the OpenFreeMap "liberty" style
  (`ui/components/OpenDashMap.kt`). Offline map areas download via MapLibre's
  `OfflineManager` (`data/OfflineMaps.kt` + `OfflineMapsScreen`) — pick an area,
  its tile pyramid (zoom 6–16) is stored on-device and served with no network.
- **Routing:** `dash/nav/Router.kt` — Google Routes API (`computeRoutes`, real
  `TWO_WHEELER` profile) when `GOOGLE_MAPS_API_KEY` is set; Mapbox Directions as
  fallback (car profile + `exclude=motorway` approximates a bike). Both decode to
  the same `Route` model (geometry + maneuvers), so callers don't care which
  provider answered. Travel modes: Car / Bike. Routes are fetched at planning time
  while online and cached, so riding can proceed offline. Supports alternatives
  and waypoint stops (stops are Mapbox-only for now).
- **Place search:** `data/PlaceSearch.kt` — Google Places (New) first, Mapbox
  Search Box second, on-device Android Geocoder as the fallback.
- **Backend:** **Firebase** for email auth + multi-device sync (so installing on a
  second device restores my data). Single user, but sync is wanted.
- **Local persistence:** on-device **SQLite** as the source of truth; Firebase
  syncs it.

## Features

1. **Navigation** (primary) — receive shared location, route preview, send to dash,
   joystick pan/zoom, turn-by-turn.
2. **TTS / voice overlay** — toggle per trip: off / chime-only / full. We own the
   TTS layer, so this is just a setting.
3. **Maintenance log** — chain cleaning + lube tracker, service intervals, due
   reminders.
4. **Fuel diary** — fill-ups, mileage/efficiency calculations, cost tracking.
5. **Telemetry / ride history** — distance, duration, map snapshot per ride.
6. **Media controls** — now-playing overlaid onto our own video frame (not the
   dash's native widget). Note: Android restricts answering/ending calls
   programmatically — calls are realistically display + reject + alert only.

## Build phasing

Sequenced so standalone, useful parts land first and the risky reverse-engineering
is isolated:

1. **Phase 1:** Kotlin control plane + auth ported and validated against firmware
   11.63 (stream a static test video to the dash to prove the protocol). In
   parallel, the standalone features (maintenance log, fuel diary, telemetry) — no
   dash dependency, usable day one.
2. **Phase 2:** off-screen MapLibre/map → MediaCodec → dash with screen OFF. Proves
   the power fix.
3. **Phase 3:** GPS + offline routing + turn-by-turn rendering.
4. **Phase 4:** polish — TTS, day/night, reconnect handling, settings, media.

## Hard constraints / non-goals

- **Android only.** No iOS.
- **One dash target**: the **Tripper Dash** — RE's name for the round TFT
  instrument cluster on the 450 platform (Himalayan 450, Guerrilla 450). No
  generic multi-brand / multi-dash abstraction — bikes with the older
  "Tripper" navigation pod (350s etc.) are out of scope.
- No personas, no branding-as-product, no team/lab infrastructure, no server-side
  PostGIS unless a real need appears. Keep it lean.
- The dash-streaming core requires **hardware-in-the-loop validation on my bike** —
  that part can't be verified from code alone.

## Reference docs in this repo

- `@docs/HLD-LLD.md` — full architecture (high- and low-level design).
- `@docs/design/` — UI prototype and screen specs (from Claude Design).

## Recent Feature Updates & UI Polish

- **Call Arc Overlay & Multi-App Interception**:
  - Implemented real-time incoming/active call detection in `OpenDashNotificationListener.kt` via action button inspection (detects Dialer, WhatsApp, Telegram, etc. calls even without standard Android `CallStyle` classification).
  - Designed circular `CallDashArc` overlay rendering on the in-app dashboard's map preview, displaying caller name, duration, and status alongside accept/decline action triggers.
  - Configured next-turn navigation crescent to dynamically render at the concentric inner position (`R - 40.dp`) when a call overlay is active on the outer position (`R - 16.dp`) to avoid visual collisions.
- **Floating Glassmorphic Bottom Navigation Bar**:
  - Redesigned as a floating, semi-transparent, circular dock with `CircleShape` corners and a semi-transparent background (copying the Telegram UI style).
  - Fully transparent system navigation bar integration via custom `enableEdgeToEdge()` in `MainActivity.kt`.
  - Smooth vertical gradient fade overlay (`110.dp` height) behind the dock to fade out scrollable screen content seamlessly.
  - Standardized bottom padding across scrollable screens (`HomeScreen`, `SettingsScreen`, `TrailsScreen`, `GarageScreen`, `RidesScreen`, `DashScreen`) to `100.dp`.
- **Material Design Icon Library Integration**:
  - Replaced all custom hand-drawn SVG vectors in `OpenDashIcons.kt` with official high-quality, pixel-perfect Material Design Icons from `androidx.compose.material:material-icons-extended` (e.g., Turn arrows, bike types, dashboard, chain/link).
- **Now Playing & Caller Cards**:
  - Exposed active media (`nowPlaying`) and incoming/active calls (`incomingCall`) streams from `DashViewModel`.
  - Added new clean card UI components `NowPlayingCard` and `CallerCard` inside `MediaCards.kt`.
  - Configured Now Playing card on the Home Screen to dynamically show only when active navigation is running, regardless of whether the phone is connected to the bike.
- **Offline maps**: `OfflineMapsScreen` + `data/OfflineMaps.kt` — download named
  map areas for no-network riding (see Tech stack).
- **Custom trails**: `TrailsScreen` (own bottom tab) — record a GPS trail while
  riding, save it as a reusable route, navigate it later. The tab can be hidden
  via the `NavSettings.customTrailsEnabled` toggle in Settings.
- **Travel modes + route alternatives**: Car / Bike mode picker on the Route
  screen (`Router.TravelMode`); routing returns a primary route plus
  alternatives, with waypoint stops supported.
- **Nav settings**: `data/NavSettings.kt` — live-traffic refresh toggle (off by
  default until a billed API key is ready) and the custom-trails toggle.
- **Multi-vehicle garage**: `VehicleStore` profiles (PUC / insurance / service),
  per-vehicle fuel & expense records (`vehicle_id` columns), and bundled default
  bike images (`res/drawable/default_*.jpg`) for common Indian-market bikes.
- **Bottom tabs**: Home · Navigate · Trails · Expenses · Garage · More
  (Settings); Dash and Rides are Home children. Bar hides on the Route tab so
  horizontal drags pan the map.
- **350 Tripper pod (exploratory)**: `docs/tripper-350-ble-capture.md` — BLE/HCI
  capture plan to reverse-engineer the older 350 navigation pod's GATT protocol.
  A side track needing borrowed hardware; the Tripper Dash (450) remains the core
  target.

