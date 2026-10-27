# Driver navigation, alert popups and approval-based reroute

## What changed
- **Alerts never take over the screen.** Emergency/deviation alerts from a trusted contact now show a
  banner (driver) or a bottom Snackbar (passenger) plus sound/vibration. "Track on map" is opt-in;
  **Back to my screen** returns to the driver's own map and navigation. The passenger no longer gets
  forced onto the Live tab when their own ride deviates.
- **Driver navigation UI.** Next-turn banner (arrow, distance, instruction), speed, follow-camera with
  heading/tilt (drag the map to stop following, tap the locate button to resume), English voice using
  the phone's built-in TextToSpeech ("In 300 meters, turn left onto ...", then "Turn left onto ..."),
  mute button.
- **Reroute by approval.** Driver goes off route -> status card + voice -> passenger answers:
  - "I'm OK" -> `bookings/{id}/deviationResponse = OK` -> driver gets a NEW polyline from the current
    position to the next stop, voice says "Rerouting", passenger map redraws once (`reroutedAt`).
  - "Emergency" -> `deviationResponse = EMERGENCY` -> reroute denied; original line turns red and the
    driver is told to return to it. No answer after 90 s behaves the same.
  - Shared rides: the first "I'm OK" approves for everyone; any Emergency blocks.
- **Live location fixes.** Shared rides now write `driverLat/driverLng` (+ bearing, speed, timestamp) to
  every passenger still on the trip (before, only the lead booking was updated, so the second rider saw a
  frozen driver). `DriverLocationService` keeps those updates going if the driver screen is destroyed.

## Test checklist
1. Two phones (driver + passenger). Accept a ride: banner and voice start; mute button silences it.
2. Drag the map (follow stops), tap the locate button (follow resumes).
3. Drive off the route: driver sees the yellow status card; passenger gets the dialog -> "I'm OK" -> Yes:
   driver hears "Rerouting" and the line is redrawn from the driver's position.
4. Repeat but pick Emergency: original line goes red, driver is told to return; line is not replaced.
5. Make a trusted contact send an alert while the driver is mid-trip: only a banner appears; the map and
   voice guidance keep going; Track on map -> Back to my screen.
6. Shared ride: the second passenger's map moves live; either rider's OK reroutes.

Pure logic is unit tested (`NavigationLogicTest`, `DeviationApprovalTest`). Activity/Fragment code could
not be compiled here (no Android SDK), so please build and run it once.

---

# Round 2: SOS, notifications, admin review, fares, wallet

## Driver / passenger
- **Driver SOS** (red button on the driver map during a trip): asks first, then alerts the *driver's own*
  trusted contacts with live location + vehicle. Riders are not notified (passengers are not trusted
  contacts). While SOS is active no automatic reroute happens.
- **Chat button overlap fixed**: the pin selector now positions itself after the trip card state is set.
- **One route-deviation panel** for riders: the full-screen card. "I'm safe" approves the reroute with one
  tap; Emergency denies it and offers to notify contacts. The second popup is gone and the card no longer
  comes back after it was answered.
- **Notifications**: the bell on Home opens a real list (`notifications/{uid}`) with an unread badge.
  Events: ride accepted / arrived / started / completed / cancelled, ID and driver-document reviews.
- **Closed-app alerts (no server)**: `NotificationWatchService`, a small foreground service started when
  the app opens. Best effort: swiping the app away or aggressive battery saving on some phones can stop
  it; opening the app starts it again.
- **ID approved**: the Promos tab is replaced by "Registration Complete. Enjoy your 20% Discount on Solo
  Ride" the moment the admin approves (live).
- **Recent rides** on Home (last 3 completed/cancelled, tap for details).
- **Profile keyboard**: the white block was the floating bottom nav riding up with the keyboard; it hides
  while typing (already in `feature/fare-chat-profile`).

## Admin
- **Checklist review** for passenger IDs and driver documents: a check or an X per item; every X needs a
  reason (presets such as "Picture is too blurry" or free text). All checks approve, any X rejects.
- Passenger sees the reasons in Promos and re-uploads only the marked photo; the driver sees notes in
  red per document and re-uploads only the marked ones (approved ones are kept).
- **Fixed**: ID submissions never reached the admin (status/field names did not match).
- **Ride history**: each row shows passenger and driver; tap for full details (phones, vehicle, route,
  fare, distance, times, shared ride).
- **Fare rates** (Settings): solo and rideshare base/per-km/per-min, saved to `config/fare`, used by new
  bookings only. Quoted/locked fares are untouched.

## Driver wallet
- Custom cash-in amount (P20 to P10,000) next to the quick-pick chips.

## Firebase rules you must add (otherwise these features get PERMISSION_DENIED)
```json
"notifications": { "$uid": { ".read": "auth != null && auth.uid == $uid", ".write": "auth != null" } },
"config": { ".read": "auth != null", "fare": { ".write": "root.child('users').child(auth.uid).child('role').val() === 'ADMIN'" } }
```

## Cloud Storage photos + closed-app push (post-defense)

**Photos.** New uploads go to Firebase Storage; the database keeps a `storage:<path>` reference
(old inline Base64 still displays, nothing is migrated).
- `profiles/{uid}/avatar.jpg` – any signed-in user can view, owner writes.
- `ids/{uid}/…`, `driver_docs/{uid}/…`, `receipts/{uid}/…` – owner + admin only (admin UID list in `storage.rules`).
- Drivers upload a 1x1 photo during registration; admin checks it as the 4th checklist item; it becomes the locked profile picture (`photoLocked`).
- Riders can add/remove a profile photo; it appears in the "Co-Passenger Found" sheet and on the driver's trip card. Initials show when there is no photo.
- Honest limits: the driver photo lock and "only the matched co-passenger/driver sees a rider photo" are enforced in the app, not by Storage rules.

**Closed-app push.** `functions/` has three Cloud Functions (notification created, emergency alert created, booking status changed). Phones register `users/{uid}/fcmTokens/{token}`.
Deploy: `cd functions && npm install && cd .. && firebase deploy --only functions,storage`. Set `REGION` in `functions/index.js` to the database region first. `maxInstances` is capped at 3; add a budget alert in Google Cloud Billing.
