# RUTA — Login/Signup Prototype (Kotlin, Android)

A standalone Android Studio project implementing RUTA's Login and Signup
flow with three roles: **Passenger**, **Driver**, and **Admin**.

## What's included

- **Splash screen** — logo + "Start now" (matches the User prototype).
- **Login screen** — role picker (Passenger / Driver / Admin) + email/password.
- **Signup screen** — same role picker + full name / email / password / confirm.
- **Placeholder home screens** for each role, so the login flow is fully
  navigable end to end even though only Passenger and Driver have real
  prototypes so far.
- Your `RUTAlogo2.png` is baked in as the app icon (adaptive icon +
  legacy icon at all densities) and as the in-app logo.

## Authentication (important)

There's no `google-services.json` yet, so this build uses a **local,
on-device auth repository** (`data/AuthRepository.kt`) that stores
accounts in `SharedPreferences` as JSON. It's a drop-in stand-in for the
Firebase Auth + Firestore setup mentioned in your capstone docs — same
`signUp()` / `logIn()` shape, same role handling.

To switch to real Firebase later:
1. Add your `google-services.json` (from Firebase console) into `/app`.
2. In `build.gradle.kts` (root), add back:
   `id("com.google.gms.google-services") version "4.4.2" apply false`
3. In `app/build.gradle.kts`, apply the plugin and uncomment the Firebase
   dependencies (already stubbed in with comments).
4. Replace the body of `AuthRepository` with `FirebaseAuth` /
   `FirebaseFirestore` calls — the method signatures can stay the same,
   so `LoginActivity` / `SignupActivity` won't need to change.

## How to run

1. Extract this zip.
2. Open the extracted `RUTA` folder in **Android Studio** (Koala/2024.1+
   recommended) via *File → Open*.
3. Let Gradle sync (Android Studio will fetch the Gradle distribution and
   dependencies automatically the first time — needs internet access).
4. Run on an emulator or device (▶ button, or `Shift+F10`).

> Note: this project doesn't include the Gradle wrapper jar binary. Android
> Studio will offer to regenerate it automatically on first open (or use
> its own bundled Gradle) — you don't need to do anything extra.

## Project structure

```
app/src/main/
├── java/com/ruta/app/
│   ├── ui/            LoginActivity, SignupActivity, SplashActivity,
│   │                   PassengerHomeActivity, DriverHomeActivity, AdminHomeActivity
│   ├── data/           AuthRepository.kt (swap-in point for Firebase)
│   └── model/          Account.kt, UserRole.kt
└── res/
    ├── layout/          activity_splash, activity_login, activity_signup,
    │                    activity_passenger_home, activity_driver_home, activity_admin_home
    ├── drawable/        ruta_logo.png + button/input/chip shapes
    ├── mipmap-*/        app icon at all densities (from RUTAlogo2.png)
    └── values/          colors.xml, strings.xml, themes.xml
```

## Next steps

- Flesh out `PassengerHomeActivity` / `DriverHomeActivity` to match your
  Image 1 / Image 2 prototypes (location search, recent bookings, earnings
  card, etc.).
- Design the Admin dashboard (no prototype exists yet).
- Wire up real Firebase Auth + Firestore per the notes above.
