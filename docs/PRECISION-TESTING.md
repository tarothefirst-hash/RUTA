# Algorithm precision testing (SOL / MSH / GEO)

These tests run the REAL app code (FareCalculator, RideshareFareCalculator, DropoffGeofence)
against independent oracles (exact rational arithmetic; WGS84 ellipsoid) and write data files.

Run all three: Android Studio -> right-click `precision/PrecisionSuite` -> Run,
or `./gradlew testDebugUnitTest --tests "com.ruta.app.precision.*"`.

Output folder: `app/build/precision-reports/`
- `PRECISION_REPORT.md` - combined report (paste-ready tables)
- `SOL_summary.md`, `MSH_summary.md`, `GEO_summary.md`
- `SOL_cases.csv`, `MSH_breakdown_cases.csv`, `GEO_cases.csv`, `GEO_noise.csv` - every test case

Add `-Dprecision.out=<folder>` to write elsewhere.
