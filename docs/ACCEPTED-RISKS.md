# Accepted Security Risks — RUTA

This document records two security weaknesses in RUTA's Firebase Realtime
Database rules that the project owner reviewed and decided to **accept rather
than fix for this capstone**. "Accepted" means the owner knows about the
weakness and has chosen to ship with it; it does not mean the weakness is
safe. Each item is explained in plain terms first, with a short technical
note underneath for whoever maintains the code later.

Reviewed and accepted: 29 September 2026.
Rules file concerned: `database.rules.hardened.json` (the file `firebase.json`
deploys).

---

## Risk 1 — A signed-in user can take over a booking while it is being matched

**In plain terms.** When a passenger requests a shared (rideshare) ride, their
booking waits in a short "matching" state while the app looks for a
co-passenger. During that state the database rules let *any* signed-in user
rewrite that booking, not only the passenger who owns it. This happens because
the rule that lets the matching code update the *other* passenger's booking
does not check *who* is doing the writing. A modified copy of the app could
use this to seize the booking.

**Technical detail.** In `database.rules.hardened.json` the `bookings/$bookingId`
write rule ends with the alternative `data.child('status').val() == 'MATCHING'`.
That alternative is reached only after the top-level `auth != null` check and
is not conditional on `auth.uid`, so any authenticated caller satisfies it. It
exists so the rideshare matcher can mark the paired booking as matched. The
matcher runs for 300 seconds (`RideshareFlowManager.kt`,
`matchingWindowMillis = 300_000L`), and while a booking's status is `MATCHING`
that alternative permits a write to any field of the booking.

**What an attacker could actually do.** Using a modified client, and only
during the 300-second window, someone signed in could rewrite a `MATCHING`
booking — for example replacing its `driverId` with an account they control,
changing the passenger, changing the fare fields, or changing the status.

**A second consequence: an unapproved driver can claim the booking.** Because
this clause keys only on the booking being in its matching window, it also lets
an account whose driver role is **not** yet approved lay claim to such a
booking — and once claimed, the rest of the flow treats that account as the
assigned driver. So the "unapproved drivers cannot take rides" protection has a
remaining gap, specifically for shared bookings inside their matching window.
The same fix closes it: moving matching into a privileged server-side path, or
narrowing the clause so it cannot set the driver field.

**Why it was accepted.** The owner accepts this for the capstone. It requires
the attacker to already be able to sign in and run a modified client, and the
window during which it is reachable is short.

**What the proper fix would be (not being done now).** Either move the matching
step into a Cloud Function that performs the cross-booking update with admin
rights, so no client needs a cross-user write; or keep the client update but add
field-level validation so this clause can change only the status and trip-group
fields, not driver, passenger, or fare.

---

## Risk 2 — Six database paths stay open to any signed-in user

**In plain terms.** Six parts of the database can be read and written by any
account that has signed in. The app genuinely crosses between users in these
areas (for example, a passenger's emergency contacts are stored inside other
users' records), but the rules do not restrict them. A modified client could
read or alter other people's data in these areas.

**Technical detail.** In `database.rules.hardened.json` each of these nodes has
`.read: "auth != null"` and `.write: "auth != null"`, with no per-user scoping:

- `users/<uid>/trustedContacts`
- `pairRequests`
- `emergencyAlerts`
- `chats`
- `tripGroups`
- `matchLocks`

**What an attacker could actually do (worst cases, stated honestly).**

- **Trusted contacts (`users/<uid>/trustedContacts`).** Any signed-in user can
  add themselves to another user's trusted-contacts list, or remove entries
  from it. Adding themselves means they will receive that user's future
  emergency alerts; removing entries means the user's SOS reaches fewer people,
  or no one.
- **Emergency alerts (`emergencyAlerts`).** Any signed-in user can read another
  user's alert queue, push fabricated alerts into it, or delete alerts. A
  fabricated alert is surfaced to the recipient as if it came from a trusted
  contact, and it carries a sender name and a location the attacker chooses. A
  genuine alert created by the app includes the driver's live latitude and
  longitude (`EmergencyContactManager.kt`, the `lat` and `lng` fields).
- **Chats (`chats/<bookingId>`).** Any signed-in user can read a booking's chat
  and post messages into it, for any booking id.
- **Pair requests (`pairRequests/<uid>`).** Any signed-in user can read another
  user's pending contact requests, create a request in their name, or remove
  one.
- **Trip groups (`tripGroups/<groupId>`).** Any signed-in user can read or
  rewrite a group's data, including the stop order (`stops`) and the
  `currentStopIndex` the driver follows.
- **Match locks (`matchLocks/<pairKey>`).** Any signed-in user can create or
  delete a match lock, interfering with co-passenger matching.

**Which is worst, and why.** The trusted-contacts node is the worst, because it
is the entry point to the emergency-alert path and it defeats a safety feature
rather than merely exposing data. By inserting themselves as a trusted contact,
an attacker quietly begins receiving the victim's SOS alerts — including the
driver's live location — and by wiping the list they can silently ensure a
genuine SOS reaches nobody. The victim has no way to tell either has happened,
and both can put a person in danger while they believe the safety feature is
working. The emergency-alerts node on its own is nearly as serious, because it
too can be used to plant forged alerts and to read other people's location
data.

**Why it was accepted.** The owner accepts this for the capstone. Closing these
paths means changing how the app crosses between users — for example, routing
emergency alerts through a server-side function — which is beyond the current
scope.

**What the proper fix would be (not being done now).** Give each path rules
that tie it to the users who should be able to touch it — for example, keying
trusted contacts and emergency alerts to a server-side check of the pairing,
and scoping chats, pair requests, trip groups and match locks to the booking
members, or routing the cross-user writes through a Cloud Function that runs
with admin rights.

**This is a carried-over risk, not a regression.** These six grants are the
same as in the owner's original ruleset (`database.rules.original.json`); the
hardened file did not introduce them. The original was looser elsewhere: it let
any signed-in user write the entire `bookings`, `wallet_requests` and
`discount_requests` nodes with no ownership check, and left additional nodes
(`reports`, `driver_applications`) fully open. The hardened file scopes
bookings, wallet requests and discount requests to the owning user or an admin,
and adds field-level validation on the user record. So none of these six paths
became more permissive, and the changes around bookings, wallet requests,
discount requests and the user record tighten access rather than loosen it.
(One unrelated path, `users/<uid>/activeQr/status`, is a new addition in the
hardened file; it is not part of the two accepted risks above.) The accepted
items above are a continuation of a known level of openness, not a new one.

---

## Owner's outstanding security actions

These cannot be done in code and are still open:

1. **Rotate and restrict the leaked Google Maps API key.** A Google API key
   (`MAPS_API_KEY`) is present in this repository's git history — it appears in
   past commits, even though `local.properties` is git-ignored now. The key
   needs to be rotated in the Google Cloud console, and the replacement
   restricted by application (package name and signing certificate) and by API
   (limited to the Maps/Places APIs actually used).
2. **Confirm the admin account's role in the live database.** The rules grant
   admin powers only when `users/<uid>/role` equals `ADMIN`. The repository does
   not guarantee that such a record exists in the live database. Verify that the
   intended admin account has `role = ADMIN` there, and that no other account
   does.
3. **Test the rules against a scratch Firebase project before deploying.** Do
   not deploy straight to the live project. A wrong rule fails silently (see the
   README), so validate the hardened rules against a throwaway project first.
4. **Confirm every live driver record has an `isApproved` field before
   deploying.** The driver-adoption rule now requires an explicitly approved
   driver, so any existing driver record with no `isApproved` field at all is
   treated as unapproved and cannot accept rides. Check the live database and
   make sure every driver record already there has the field set.

---

## What is not verified

Nothing in this repository has been run against a real Firebase project as part
of this review.

- The two accepted risks are described from reading the rules file and the
  app's Kotlin source. No attack was executed and no modified client was built
  or run, so these are descriptions of what the rules *permit*, not a
  demonstrated exploit.
- The hardened rules have not been deployed or tested. Whether they evaluate as
  intended in the Firebase rules engine has not been checked.
- Whether the `ADMIN` role record exists and is correct in the live database has
  not been checked; that is action 2 above.
- Whether the leaked key has already been rotated or restricted has not been
  checked; that is action 1 above.
