# Defense comments: what was built

| Panel comment | What the app does now |
|---|---|
| Emergency drop-off, show passenger and driver are safe | **Emergency stop** button (passenger: trip card while in progress; driver: map controls during a trip). Confirm dialog, then the trip ends at the current location (`CANCELLED`, `cancelReason = EMERGENCY_STOP`), the stopper's trusted contacts get the live-location alert, and passenger + driver are each asked **"Are you safe?"** on whatever screen they are on. Answers are saved on the booking (`emergencyStop.passengerSafe / driverSafe`) and sent to the answerer's trusted contacts. "I need help" re-sends the full alert. |
| Face scan + liveness at registration, verify the account holder, driver sees the passenger | **FaceVerificationActivity** after sign-up (and for any existing account that has not passed it): blink, turn head, smile in shuffled order, then one selfie. Detection is on-device (ML Kit). The selfie is stored privately in Storage (`faces/{uid}/selfie.jpg`); admins compare it with the ID/license in the review screens. The driver sees a "Face verified" line on the passenger's request. |
| Email receipts / digital receipts | Receipt emailed to the passenger when a trip completes (Brevo, same sender as the email OTP), and a receipt dialog (with "Email me a copy") when tapping a trip in history. |
| 5 minutes without a match, then a notification | Solo ride search timer with the "wait longer?" popup (already built). |
| Trusted contact: ask before anything else | Passengers are asked before booking, drivers before going online, whenever they have no trusted contact ("Add now" / "Not now"). |
| Live traffic / road blockage | Google traffic layer on the driver and booking maps; Directions requests use `departure_time=now`, so routes avoid roads Google knows are closed or jammed. |

## Deploy checklist
1. Gradle sync (adds CameraX and ML Kit face detection).
2. Publish `storage.rules` (adds `faces/`), keeping your real admin UID instead of `ADMIN_UID_1`.
3. Add the `safetyChecks` block from `database.rules.hardened.json` to your Realtime Database rules.
4. Test on a real phone: camera permission, the three challenges, selfie upload, then an admin opening the review screen.
