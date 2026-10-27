/**
 * RUTA Cloud Functions: push notifications that reach a phone even when the app is closed.
 *
 *  1. notifications/{uid}/{id}  created -> push to that user's phones
 *  2. emergencyAlerts/{uid}/{id} created -> high-priority push (trusted contacts)
 *  3. bookings/{id}/status      changed -> writes the matching notifications/{passenger}/bk_<id>_<status>
 *                                          item (idempotent key), which triggers (1)
 *
 * REGION: it must match your Realtime Database region. Look at the database URL:
 *   https://<name>-default-rtdb.firebaseio.com                    -> "us-central1"
 *   https://<name>-default-rtdb.asia-southeast1.firebasedatabase.app -> "asia-southeast1"
 *   https://<name>-default-rtdb.europe-west1.firebasedatabase.app  -> "europe-west1"
 */
const { setGlobalOptions } = require("firebase-functions/v2");
const { onValueCreated, onValueUpdated } = require("firebase-functions/v2/database");
const admin = require("firebase-admin");

const REGION = "us-central1"; // <- change to your database region (see above)

// Hard cap so a bug can never run up a bill.
setGlobalOptions({ region: REGION, maxInstances: 3 });
admin.initializeApp();

/** Sends one push to every token the user has; removes tokens Google says are dead. */
async function pushToUser(uid, data) {
  const db = admin.database();
  const snap = await db.ref(`users/${uid}/fcmTokens`).get();
  if (!snap.exists()) return;
  const tokens = Object.keys(snap.val());
  if (tokens.length === 0) return;

  // Data-only message: the app's own service shows it, so it also works with the app closed
  // and uses the same notification id as the in-app watcher (no duplicates).
  const res = await admin.messaging().sendEachForMulticast({
    tokens,
    data: Object.fromEntries(Object.entries(data).map(([k, v]) => [k, String(v ?? "")])),
    android: { priority: "high", ttl: 60 * 60 * 1000 },
  });

  const dead = [];
  res.responses.forEach((r, i) => {
    const code = r.error && r.error.code;
    if (code === "messaging/registration-token-not-registered" || code === "messaging/invalid-registration-token") {
      dead.push(tokens[i]);
    }
  });
  if (dead.length) {
    const updates = {};
    dead.forEach((t) => (updates[`users/${uid}/fcmTokens/${t}`] = null));
    await db.ref().update(updates);
  }
}

exports.pushOnNotification = onValueCreated("/notifications/{uid}/{id}", async (event) => {
  const n = event.data.val() || {};
  await pushToUser(event.params.uid, {
    title: n.title || "RUTA",
    message: n.body || "",
    type: n.type || "",
    notifId: event.params.id,
  });
});

exports.pushOnEmergencyAlert = onValueCreated("/emergencyAlerts/{uid}/{id}", async (event) => {
  const a = event.data.val() || {};
  await pushToUser(event.params.uid, {
      title: a.title || "🚨 RUTA EMERGENCY ALERT",
      message: a.message || "Your contact triggered an alert!",
      bookingId: a.bookingId || "",
      contactUid: a.contactUid || "",
      type: "EMERGENCY",
      notifId: `em_${event.params.id}`,
  });
});

const RIDE_TEXT = {
  ACCEPTED: ["Driver on the way", "A driver accepted your ride."],
  ARRIVED: ["Driver has arrived", "Your driver is at the pickup point."],
  IN_PROGRESS: ["Trip started", "Enjoy your ride."],
  COMPLETED: ["Trip completed", "Thanks for riding with RUTA."],
  CANCELLED: ["Ride cancelled", "Your ride was cancelled."],
};

// Only writes under notifications/, never under bookings/, so it cannot loop on itself.
exports.notifyOnBookingStatus = onValueUpdated("/bookings/{bookingId}", async (event) => {
  const before = event.data.before.val() || {};
  const after = event.data.after.val() || {};
  if (before.status === after.status) return;
  const text = RIDE_TEXT[after.status];
  const passengerId = after.passengerId;
  if (!text || !passengerId) return;

  // Same key the app uses, so the app and the server never create two copies.
  const ref = admin.database().ref(`notifications/${passengerId}/bk_${event.params.bookingId}_${after.status}`);
  await ref.transaction((cur) =>
    cur !== null
      ? undefined
      : { type: "RIDE", title: text[0], body: text[1], timestamp: admin.database.ServerValue.TIMESTAMP, read: false }
  );
});
