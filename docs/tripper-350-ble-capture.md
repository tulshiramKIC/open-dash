# Tripper (350 pod) BLE capture procedure

Goal: capture the Bluetooth LE traffic between the **Royal Enfield app** and an
**old-style Tripper navigation pod** (Hunter 350 / Meteor 350 / Classic 350) during
a live navigation session, so we can reverse-engineer the pod's GATT protocol and
drive it from OpenDash using our own turn-by-turn maneuver stream.

This is hardware-in-the-loop: it needs a physical 350 pod (a friend's Hunter 350
works). The pod is 12 V bike-powered, so the capture happens on the bike with the
ignition on.

## What we're trying to learn

1. The pod's **GATT service + characteristic UUIDs** (the "address" the app writes to).
2. The **payload byte format** for a maneuver: maneuver type (left / right / straight /
   roundabout / U-turn / arrive), remaining distance, and the street-name string.
3. The **connection/handshake** writes the app sends right after connecting, before any
   maneuver data flows.

OpenDash already computes the maneuver data (the `dash/nav` engine). The only missing
piece is this wire format.

## Method: Android HCI snoop log (no extra hardware)

The capturing phone must be the one running the RE app **and paired to the pod**. Use
your own phone (adb already set up) — install the RE app and pair it to the friend's
Hunter pod (unpair it from the friend's phone first if needed).

### 1. Enable HCI snoop logging
- Settings → About → tap Build number 7× to unlock **Developer options**.
- Developer options → **Enable Bluetooth HCI snoop log** → set to **Enabled / Full**
  (if it offers Filtered vs Full, pick **Full** — Filtered can scrub payloads).
- Toggle Bluetooth **off then on** so logging starts on a fresh session.

### 2. Capture the handshake
- Start the snoop log fresh (Bluetooth off/on) **before** connecting the pod, so the
  capture includes pairing + GATT service discovery + the app's initial setup writes.
- Open the RE app, connect to the Tripper pod, confirm it shows the idle/paired state.

### 3. Capture maneuvers with tight ground truth
- In the RE app, set a destination a few km away with **varied turns** (a route through
  a town with left/right/roundabout/U-turn, ending in arrival).
- Start navigation and ride the route (short loop is fine).
- **Film the pod display** with a second phone, with a running stopwatch/clock visible in
  frame. This video is the ground truth — we correlate "at 00:42 the pod showed LEFT,
  180 m, MG Road" against the packet timestamps. Without this, the bytes are unlabeled.
- Try to capture at least one of each: left, right, straight/continue, roundabout, U-turn,
  and the final **arrival** screen, plus a distance that visibly counts down (10 m
  granularity tells us the distance encoding).

### 4. Pull the log
Most modern phones (incl. ColorOS/OPPO) keep the snoop log internal; retrieve it via a
bug report (no root needed):

```
adb bugreport bugreport.zip
```

Unzip and find the log at:
```
FS/data/misc/bluetooth/logs/btsnoop_hci.log
```
(Older phones may expose it directly — try `adb pull /sdcard/btsnoop_hci.log`.)

### 5. What to send back
- `btsnoop_hci.log` (or the whole `bugreport.zip`).
- The **pod-display video** (or a written timeline: timestamp → what the pod showed).
- The pod's advertised **BLE name / MAC** if you noticed it (helps filter the log).

## Decode (my side)

Open in Wireshark, filter `btatt`, and isolate **Write Request / Write Command** packets
to the pod's handle. Correlate each write's payload bytes against the ground-truth video:
the maneuver enum, the distance field encoding, and the string field fall out from a few
labeled examples. Result is a small spec (UUIDs + payload layout).

## Then

Build a BLE-client module in OpenDash that connects to the pod and replays that format,
fed by the maneuver stream the routing engine already produces. This path never touches
RE's video stream or auth handshake — it's just GATT writes — so it's independent of the
Tripper Dash streaming work.

## Safety / legality notes
- You're capturing traffic between your own phone and a pod you have permission to use —
  standard interop reverse engineering.
- Do the capture while stationary or with a second person filming; don't fiddle with
  phones while riding.
