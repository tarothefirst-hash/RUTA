# RUTA — Ride-Hailing App (Kotlin, Android)

A standalone Android Studio project implementing RUTA, a three-role
ride-hailing app for **Passenger**, **Driver**, and **Admin**. It covers
ride booking with live driver tracking, shared/rideshare trips with
co-passenger matching, a wallet, promos and discount requests, in-app
chat, route-deviation monitoring, and an SOS dialer.

## What's included

- **Splash screen** — logo + "Start now" (matches the User prototype).
- **Login screen** — role picker (Passenger / Driver / Admin) + email/password.
- **Signup screen** — same role picker + full name / email / password / confirm.
- **Passenger flow** — home with live active-trip status, map-based location
  selection with fare estimate, solo or shared (rideshare) booking,
  co-passenger matching, live driver tracking on the map, route-deviation
  alerts with an SOS dialer, chat with the driver, wallet, and promos /
  discount requests.
- **Driver flow** — ride requests, accept / start / complete trips (with
  drop-off proximity checks), earnings and wallet, ride history, profile
  editing, and document upload.
- **Admin flow** — driver approval, discount-request review, and wallet
  cash-in approval / rejection.
- Your `RUTAlogo2.png` is baked in as the app icon (adaptive icon +
  legacy icon at all densities) and as the in-app logo.

## Authentication (important)

The app authenticates with **Firebase Auth** and stores user profiles,
bookings, wallet, and the rest of its data in the **Firebase Realtime
Database**. `LoginActivity` / `SignupActivity` call `FirebaseAuth`
directly, and account records live under `users/<uid>` in the Realtime
Database — there is no local / `SharedPreferences` auth repository.

A real `app/google-services.json` from the Firebase console is **required**
before the app will work at runtime, and a real configuration is now in
place for Firebase project `ruta-d70de` (project number `550811486087`).
`google-services.json` is listed in `.gitignore`, so this real config must
**not** be committed — keep it local to your machine.

## How to run

1. Extract this zip.
2. Open the extracted `RUTA` folder in **Android Studio** (Koala/2024.1+
   recommended) via *File → Open*.
3. Let Gradle sync (Android Studio will fetch the Gradle distribution and
   dependencies automatically the first time — needs internet access).
4. Run on an emulator or device (▶ button, or `Shift+F10`).

> Note: this project has **no Gradle wrapper**. `gradlew`, `gradlew.bat`,
> and `gradle/wrapper/gradle-wrapper.jar` are all absent — only
> `gradle/wrapper/gradle-wrapper.properties` (pinning Gradle 8.14.5) is
> present. So `./gradlew` will **not** work. Sync from Android Studio's
> bundled Gradle instead, or run `gradle wrapper` once with a local Gradle
> 8.14.5 install to generate the wrapper scripts and jar.

## Google Maps / Places API key

The Google Maps **and** Places key is read from `local.properties` as
`MAPS_API_KEY`; the `secrets-gradle-plugin` injects it into the manifest's
`com.google.android.geo.API_KEY` entry at build time. The app reads the key
back out of the merged manifest, so it lives in exactly one place.
`local.properties` is **not** committed, so add your key locally:

```
MAPS_API_KEY=your_maps_key_here
```

## Database and storage rules

The Firebase security rules are kept in the project and versioned with it:

- `database.rules.hardened.json` — the Realtime Database rules. This is the
  file that gets deployed; `firebase.json` points at it.
- `database.rules.original.json` — the owner's original hand-written ruleset,
  kept for reference only. It is **not** the live ruleset and is not deployed.
- `storage.rules` — the Cloud Storage rules; `firebase.json` points at it.

Deploy both in one line:

```
firebase deploy --only database,storage
```

Run it from the project root with your Firebase project selected (`firebase use
<project-id>`, or add `--project <project-id>` to the command).

> Warning: test the rules against a **scratch** Firebase project first. A wrong
> rule fails silently — the app keeps running, and the rule simply does not do
> what you intended (it may block a legitimate write, or keep allowing a write
> you meant to stop), with no error shown at runtime.

Two security risks in the current ruleset have been reviewed and accepted for
this capstone; they are written up, with the owner's remaining security
actions, in [`docs/ACCEPTED-RISKS.md`](docs/ACCEPTED-RISKS.md).

## Project structure

```
app/src/main/
├── java/com/ruta/app/
│   ├── MainApplication.kt
│   ├── ui/            LoginActivity, SignupActivity, SplashActivity,
│   │                   PassengerHomeActivity, AdminHomeActivity, ChatActivity,
│   │                   LocationSelectionActivity + shared fragments/helpers
│   │   └── driver/    DriverMainActivity + DriverHome/Wallet/Rides/Profile
│   │                   fragments and DriverDocumentUploadActivity
│   ├── model/         Booking.kt, DiscountRequest.kt, LocationItem.kt, UserRole.kt
│   └── util/          fare calculators, route/maps + wallet helpers, ImageUtils
└── res/
    ├── layout/          activity_* and fragment_* screens
    ├── drawable/        ruta_logo.png + button/input/chip shapes
    ├── mipmap-*/        app icon at all densities (from RUTAlogo2.png)
    └── values/          colors.xml, themes.xml, and the string resources
                         (strings.xml, strings_ui_admin.xml, strings_ui_dialog.xml,
                         strings_ui_driver.xml, strings_ui_home.xml, strings_ui_items.xml)
                         that hold the bulk of the app's user-facing text
```

## Next steps

All three role flows and the features listed above are already implemented
in this project. To get it running you only need to supply one required
config: a `MAPS_API_KEY` in `local.properties`. A real
`app/google-services.json` is already in place for Firebase project
`ruta-d70de` (it is gitignored, so do not commit it). Optionally generate
the missing Gradle wrapper if you want to build from the command line.
