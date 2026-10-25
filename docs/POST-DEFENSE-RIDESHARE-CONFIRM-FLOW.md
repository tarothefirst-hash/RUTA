# Post-defense enhancement: rideshare confirm flow

The approved paper is unchanged. This work only changes how a rideshare match is
presented and agreed; the matching rules, the unified fare model and route
deviation detection are the same ones the paper describes.

## What the passenger sees
1. **Matching co-passenger…** with a live 5:00 countdown on the status button.
2. **Co-Passenger Found** sheet: name, number, pickup, drop-off, what *you* pay,
   what the co-passenger pays, and **Confirm Passenger Match**. 60 seconds to answer.
   Both must confirm. If either declines or the time runs out, both go back to
   searching and are never paired with each other again.
3. After 5 minutes with no match: **"No Co-Passenger available. Switch to Solo-Ride?"**
   (also sent as a notification if the app is in the background). Never automatic.
   *No* keeps searching (a fresh 5 minutes). *Yes* shows the solo price first,
   built from the same booking, so nobody retypes a location.
4. Map: one colour per passenger (you = lavender `ruta_primary`, co-passenger = blue),
   shared legs draw blue under lavender, labeled pins (circle = pickup, rounded
   square = drop-off, name underneath). The driver map uses the same colours.
5. The fare shown at confirmation is **locked**; the driver's completion step pays
   out the stored quote instead of recalculating.

## Data
Booking stays `MATCHING` through search, proposal and confirm (no new statuses).
New booking fields: `searchExpiresAt`, `matchProposalId`, `matchPartnerId`,
`matchPhase`, `matchExpiresAt`, `matchConfirmed`, `matchExcluded/*`, `fareLocked`,
`quotedFare`. `tripGroups/<pairKey>` gains `quote/<bookingId>/*` and `stopOrder`.
The first passenger to claim a pair does so with a transaction on each booking's
`matchProposalId` (lower id first), so two searchers cannot be paired twice.

## Code
- `util/RideshareMatchLogic.kt` pure rules (tested): windows, eligibility, confirm
  resolution, countdown text, per-leg ownership for route colours.
- `util/RideshareFlowManager.kt` Firebase wiring of those rules.
- `ui/RideshareMatchUi.kt`, `ui/RideshareMapOverlay.kt`, `util/RideshareMapStyle.kt`,
  `util/MapMarkerUtils.createPassengerPin`, `util/RideshareNotifier.kt`.

## How to test (two phones, same area)
1. Both book Rideshare: both see the countdown; one then sees Co-Passenger Found.
2. Both confirm: both go to "Searching for driver", fare card says price is locked.
3. One declines / ignore for 60 s: both return to searching, not paired again.
4. Wait 5 minutes alone: prompt appears; No keeps searching; Yes shows the price,
   Confirm switches to a solo booking.
5. Driver accepts a matched pair: colours/pins match the passengers' screens;
   on completion each passenger is charged the locked amount.
