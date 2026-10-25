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
