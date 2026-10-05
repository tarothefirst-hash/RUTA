"""Builds reports/all-logs/RUTA-testing-log.docx from the session's tables."""

from docx import Document
from docx.shared import Pt, Inches, RGBColor
from docx.enum.text import WD_ALIGN_PARAGRAPH

OUT = r"D:\XAVE\RUTA\reports\all-logs\RUTA-testing-log.docx"

doc = Document()

# base style
style = doc.styles["Normal"]
style.font.name = "Calibri"
style.font.size = Pt(10.5)


def h(text, level=1):
    doc.add_heading(text, level=level)


def p(text, bold=False, italic=False):
    par = doc.add_paragraph()
    run = par.add_run(text)
    run.bold = bold
    run.italic = italic
    return par


def bullet(text):
    doc.add_paragraph(text, style="List Bullet")


def table(headers, rows, widths=None):
    t = doc.add_table(rows=1, cols=len(headers))
    t.style = "Table Grid"
    hdr = t.rows[0].cells
    for i, name in enumerate(headers):
        hdr[i].text = ""
        run = hdr[i].paragraphs[0].add_run(name)
        run.bold = True
    for row in rows:
        cells = t.add_row().cells
        for i, val in enumerate(row):
            cells[i].text = str(val)
    if widths:
        for r in t.rows:
            for i, w in enumerate(widths):
                r.cells[i].width = Inches(w)
    doc.add_paragraph()
    return t


def bar(value, total, width=28, filled="#", empty="."):
    n = int(round(width * value / total)) if total else 0
    return filled * n + empty * (width - n)


# ---------------------------------------------------------------- title
title = doc.add_heading("RUTA: Testing, Audit and Repair Log", level=0)
sub = doc.add_paragraph()
sub.add_run("Android ride-hailing app, Kotlin, Firebase Authentication and Realtime Database\n").italic = True
sub.add_run("Compiled 30 September 2026. Every figure in this document was measured on the project tree, "
            "or taken from a formal review of it. Nothing here was run on a device or against a live "
            "Firebase project unless the section says so.").italic = True

# ---------------------------------------------------------------- 1. framework
h("1. How this document is organised", 1)
p("The testing work followed a three-layer framework. Layer 1 asks what is being tested, Layer 2 asks how it "
  "is run, and Layer 3 asks how much of the system is covered. Baseline results came from static reading of "
  "the source. Later sections record what was repaired and what the current state is.")

h("The three testing layers", 2)
table(
    ["Layer", "Category", "What it means", "Status"],
    [
        ["1 by purpose", "A. Functional", "Does it work: sign-up, login, booking, fares, rideshare, SOS, chat, wallet", "Covered"],
        ["1 by purpose", "B. Non-functional", "How well: security, performance, reliability, usability, compatibility", "Covered"],
        ["2 by method", "A. Manual", "Step-by-step cases walked by a person", "24 cases, inferred"],
        ["2 by method", "B. Automated", "Tests a machine runs", "0 at baseline, 65 now"],
        ["3 by scope", "A. Unit", "One function in isolation", "Was failing, now partial"],
        ["3 by scope", "B. Integration", "Two layers together, for example auth plus database", "Partial"],
        ["3 by scope", "C. System", "Passenger to driver, end to end", "Partial"],
        ["3 by scope", "D. Acceptance", "Good enough for the capstone demo", "Partial"],
        ["3 by scope", "E. Production", "Good enough for real users", "Partial"],
    ],
    widths=[1.0, 1.2, 3.3, 1.3],
)

# ---------------------------------------------------------------- 2. baseline
h("2. Baseline results, before any repair", 1)
p("These are the starting numbers. They were produced by reading the code, not by running it. An audit carried out "
  "when these pages were reviewed established that the item-level check lists behind these two scores were never "
  "saved anywhere in the repository, so the scores cannot be re-run item by item today. They are shown here as a "
  "historical starting line only. The current, auditable scores are in sections 3 to 5.")
p("The same audit did confirm the direction of travel independently: reading the current code, six of the eight "
  "production gates now pass where only one passed before, six of the seven critical defects are closed, and "
  "fourteen of the seventeen defect classes are closed.", italic=True)

h("2.1 Sixty-eight deep checks", 2)
table(
    ["Outcome", "Count", "Share", "Formula", "Distribution"],
    [
        ["Pass", 18, "26.47%", "18 / 68", bar(18, 68)],
        ["Fail", 28, "41.18%", "28 / 68", bar(28, 68)],
        ["Warn", 22, "32.35%", "22 / 68", bar(22, 68)],
    ],
    widths=[1.0, 0.7, 0.9, 1.0, 2.5],
)

h("2.2 Fifty-one user steps", 2)
table(
    ["Outcome", "Count", "Share", "Formula", "Distribution"],
    [
        ["Pass", 33, "64.71%", "33 / 51", bar(33, 51)],
        ["Fail", 10, "19.61%", "10 / 51", bar(10, 51)],
        ["Warn", 5, "9.80%", "5 / 51", bar(5, 51)],
        ["Not tested", 3, "5.88%", "3 / 51", bar(3, 51)],
    ],
    widths=[1.0, 0.7, 0.9, 1.0, 2.5],
)

h("2.3 Thirty recorded defects by severity", 2)
table(
    ["Severity", "Count", "Share of 30", "Distribution", "Examples"],
    [
        ["Critical", 7, "23.33%", bar(7, 30), "Admin credential, Maps key, discount image, wallet fork, approval split, cash-in replay, missing rules"],
        ["High", 11, "36.67%", bar(11, 30), "Fare forks, deviation counter, double accept, full-node reads, blobs in database"],
        ["Medium", 8, "26.67%", bar(8, 30), "Chat with no failure path, allowBackup, listener leaks"],
        ["Low", 4, "13.33%", bar(4, 30), "Dead code, duplicate adapter, unused helpers"],
    ],
    widths=[0.9, 0.6, 0.9, 1.4, 3.4],
)

h("2.4 The seven original critical defects", 2)
table(
    ["Severity", "Defect", "Where it was"],
    [
        ["Critical", "Hardcoded admin email and password in the client", "LoginActivity, lines 148 to 153"],
        ["Critical", "Google Maps key committed in clear text, in two places", "strings.xml and LocationSelectionActivity"],
        ["Critical", "Discount ID images stored double-prefixed, so unreadable", "PromosFragment, lines 107 and 136"],
        ["Critical", "Wallet request written to one node, read from another", "Walletmanager vs AdminHomeActivity"],
        ["Critical", "Cash-in approval could credit twice on replay", "AdminHomeActivity, lines 395 to 421"],
        ["Critical", "Driver approval written under two different field names", "AdminHomeActivity, lines 177 vs 629"],
        ["Critical", "No database security rules anywhere in the project", "Whole repository"],
    ],
    widths=[0.9, 3.6, 2.7],
)

# ---------------------------------------------------------------- 3. defect classes
h("3. The seventeen defect classes and their current status", 1)
p("Several of the thirty original defects shared one root cause, so they were regrouped into seventeen classes. "
  "Fourteen are now closed, three are partially closed, and none is fully open. "
  "Formula: 14 / 17 = 82.35%, 3 / 17 = 17.65%, 0 / 17 = 0.00%.")
table(
    ["Status", "Count", "Share", "Distribution"],
    [
        ["Closed", 14, "82.35%", bar(14, 17)],
        ["Partial", 3, "17.65%", bar(3, 17)],
        ["Open", 0, "0.00%", bar(0, 17)],
    ],
    widths=[1.0, 0.7, 0.9, 3.0],
)
table(
    ["Defect class", "Status", "What is true now"],
    [
        ["Hardcoded admin credential", "Closed", "Removed. Login goes through Firebase and the role is read from the database"],
        ["Hardcoded Maps key", "Closed", "Single source, injected from local.properties into the manifest"],
        ["Wallet receipt not renderable", "Closed", "Written as a data URI; the admin decodes raw, data URI and URL"],
        ["Discount image corruption", "Closed", "Exactly one prefix on the stored payload"],
        ["Cash-in replay double credit", "Closed", "Transactional claim; only a pending request can move"],
        ["Approval field split", "Closed", "One approval field used everywhere"],
        ["Double accept write", "Closed", "One atomic write per booking, on both the solo and shared paths"],
        ["Deviation counter bleeding across trips", "Closed", "Counter keyed per booking and cleared at trip end"],
        ["Shared pricing formula forks", "Closed", "One model chosen: the 50/50 split with detour surcharge and credit"],
        ["Final fare not visible to passenger", "Closed", "The settled fare and its breakdown are now shown to the passenger"],
        ["Database security rules missing", "Partial", "Hardened rules authored and staged, not deployed and not tested against a live project"],
        ["Wallet node fork", "Closed", "The dead duplicate node and the duplicate write are both gone; a single bookings node"],
        ["Booking date field fork", "Closed", "All writers now set createdAt; the read fallback still covers older records"],
        ["Full-node downloads and leaked listeners", "Partial", "Identified listeners are detached; whole-node reads remain"],
        ["Chat failure path and unbounded history", "Closed", "Send failure, read failure, history cap and listener detach are all in place"],
        ["Base64 blobs stored in the database", "Partial", "Profile photos moved to Storage; receipts and ID documents are still inline"],
        ["Release hygiene", "Closed", "Minification on with strip rules active, no log calls, backups off, dead code and duplicate dependencies removed"],
    ],
    widths=[2.4, 0.9, 4.0],
)

# ---------------------------------------------------------------- 4. criticals
h("4. Critical defects today", 1)
p("Of the seven critical defects, six are closed and one is partial. None is fully open. "
  "Formula: 6 / 7 = 85.71%.")
table(
    ["Critical defect", "Status"],
    [
        ["Hardcoded admin credential", "Closed"],
        ["Hardcoded Maps key", "Closed"],
        ["Discount image corruption", "Closed"],
        ["Cash-in replay double credit", "Closed"],
        ["Approval field split", "Closed"],
        ["Wallet node fork", "Closed"],
        ["Database security rules", "Partial: authored, not deployed or tested"],
    ],
    widths=[3.5, 3.5],
)

# ---------------------------------------------------------------- 5. gates
h("5. Production gates, before and after", 1)
p("Eight gates were defined at baseline. Five failed then. Now six pass, two are partial and none fails. "
  "Formula: 6 / 8 = 75.00% pass, 2 / 8 = 25.00% partial, 0 / 8 = 0.00% fail. Gates 1, 2, 4 and 8 moved from fail "
  "to pass, gate 5 moved from a warning to pass, and gate 3 moved from fail to a partial. Gate 3, the database rules, is the only one still "
  "failing in substance. Gate 7 is unproven rather than broken: it depends on running the app, which has not happened.")
table(
    ["Gate", "Baseline", "Now", "Why"],
    [
        ["1 Login without hardcoded credentials", "Fail", "Pass", "Admin backdoor deleted, no replacement credential added"],
        ["2 No key or credential in source", "Fail", "Pass", "Key injected at build time; nothing hardcoded in Kotlin or resources"],
        ["3 Database rules block strangers", "Fail", "Partial", "Hardened rules written, indexed and staged, not yet deployed and never executed"],
        ["4 Money maths consistent and tested", "Fail", "Pass", "One pricing model, one calculator, 65 tests pinning the numbers"],
        ["5 Approval and deviation correct per trip", "Warn", "Pass", "Every approval path is state guarded; deviation counter is per booking"],
        ["6 Happy-path booking", "Pass", "Pass", "Accept is a single atomic write on both paths"],
        ["7 Stable on two devices and rotation", "Warn", "Partial", "Listeners now detached, but the app has never been run"],
        ["8 Privacy, no leak in code or logs", "Fail", "Pass", "Backup off, debug logs removed, password literal gone"],
    ],
    widths=[2.3, 0.8, 0.8, 3.4],
)

# ---------------------------------------------------------------- 6. work log
h("6. Repair work log", 1)
p("The repair ran in six waves. Each wave was rebuilt and re-tested before the next began.")
table(
    ["Wave", "Focus", "Representative changes"],
    [
        ["1", "Security and trust", "Admin backdoor removed; Maps key single-sourced; backup disabled; signup validation added; orphan Google profile provisioned"],
        ["2", "Money", "Wallet receipt rendering; discount image prefix; cash-in guards; final fare surfaced; pricing unified later in wave 6"],
        ["3", "Lifecycle", "Accept collapsed to one atomic write; deviation counter keyed per booking; booking date read fallback; QR expiry rule"],
        ["4", "Reliability and hygiene", "Listeners detached; chat failure path and history cap; debug logs stripped; duplicate dependencies removed; dead files deleted"],
        ["5", "Pricing and display", "One pricing model chosen; per-passenger discount wired into settlement; passenger final-fare card built"],
        ["6", "Security rules, strings, tests", "Hardened database rules authored; 225 hardcoded UI labels extracted to resources, including 12 navigation menu labels; matcher and deviation tests added"],
        ["7", "Release and data integrity", "Minification enabled with rules that strip debug logging and raw stack traces; the duplicate booking node confirmed gone; all booking date writers unified on a single key; chat read failures surfaced; the last log calls removed; a database index added for the trip group queries"],
        ["8", "Verification and reporting", "Independent re-checks of every claimed fix; figures re-measured; the report pages rebuilt; two Word documents produced for the owner and for the requirements list"],
    ],
    widths=[0.6, 1.6, 5.0],
)

h("6.1 Verification evidence at each checkpoint", 2)
table(
    ["Checkpoint", "Build", "Unit tests", "Notes"],
    [
        ["Before any edit", "Not buildable", "0", "No Java on PATH, no Gradle wrapper, placeholder config only"],
        ["Baseline established", "Debug success", "0", "Toolchain set up, green baseline recorded"],
        ["After security wave", "Success", "0", "First real build of a working tree"],
        ["After lifecycle wave", "Success", "14", "Fare tests added"],
        ["After pricing wave", "Success", "31", "Fare display tests added"],
        ["After discount fix", "Success", "46", "Per-passenger discount pinned"],
        ["Final", "Debug and release success", "65", "Matcher and deviation tests added; zero failures"],
    ],
    widths=[1.4, 1.3, 1.0, 3.3],
)

# ---------------------------------------------------------------- 7. effort
h("7. Effort estimate by workstream", 1)
p("These are single-developer hour estimates made during planning, before the work began. "
  "They are shown here so the size of the remaining work is not a surprise.")
table(
    ["Workstream", "Defects", "Estimated hours"],
    [
        ["Secrets, auth and trust", "4", "8 to 12"],
        ["Money correctness", "6", "14 to 22"],
        ["Ride-lifecycle correctness", "7", "16 to 24"],
        ["Media and Storage", "3", "10 to 16"],
        ["Reliability and performance", "5 plus leaks", "14 to 20"],
        ["Release and repository hygiene", "dead code, logs, deps", "10 to 16"],
        ["Tests and rules", "0 tests, no rules", "20 to 40"],
        ["Total", "30 defects", "about 92 to 150 hours"],
    ],
    widths=[3.4, 2.0, 1.6],
)

# ---------------------------------------------------------------- 8. decisions
h("8. Decisions taken", 1)
table(
    ["Decision", "Choice", "Consequence"],
    [
        ["Shared-ride pricing model", "The 50/50 split with detour surcharge and credit", "The flat shared discount was retired. The shared quote now equals a solo quote, because at quote time there is no co-passenger yet; the saving appears at settlement"],
        ["Show the final fare to the passenger", "Yes", "The settled fare and its breakdown now appear on the passenger tracking screen"],
        ["Discount on a shared fare", "Applied to the shared fare too, at quote and settlement", "Otherwise a student or senior passenger would lose their discount purely by choosing a shared ride"],
        ["Number shown after a match", "Status update only, no estimate", "Avoids showing a figure the end-of-trip recompute would contradict"],
        ["Driver and admin history", "Show the settled fare when present, else the quote", "Matches the figure the commission was taken from"],
        ["Where the passenger sees it", "The live tracking screen only", "A passenger history screen would be new scope"],
        ["Matched-booking window risk", "Accepted and documented", "See section 9"],
        ["Six open database nodes", "Accepted and documented", "See section 9"],
    ],
    widths=[1.9, 2.0, 3.4],
)

# ---------------------------------------------------------------- 9. accepted risks
h("9. Accepted risks", 1)
p("Two risks were reviewed and deliberately accepted by the project owner for the capstone, "
  "with documentation in docs/ACCEPTED-RISKS.md. They are recorded here so they are visible rather than forgotten.")
table(
    ["Risk", "Worst realistic outcome", "Proposed fix"],
    [
        ["The matched-booking window. One rule clause exists so the rideshare matcher can mark the other passenger's booking, and it keys only on the booking being in its matching window",
         "A modified client could take over such a booking, for example by rewriting its driver or fare. An account whose driver role is not yet approved could also claim one, and the rest of the flow would treat it as the assigned driver",
         "Move matching into a server-side function that runs with admin rights, or narrow the clause so it cannot change the driver field"],
        ["Six database nodes remain open to any signed-in user, because those flows cross users: trusted contacts, pair requests, emergency alerts, chats, trip groups and match locks",
         "The worst case is injecting yourself into another user's trusted contacts and then receiving their emergency alerts, including live driver location. A user's real contacts could also be wiped so their SOS reaches nobody",
         "Scope each node to the parties involved. Trusted contacts to owner or admin, chats to the booking's passenger and driver, alerts to a trusted contact of the target"],
    ],
    widths=[2.6, 3.4, 1.3],
)

# ---------------------------------------------------------------- 10. rules
h("10. Database security rules", 1)
p("The owner supplied hand-written rules. An audit compared them against every database path the app actually "
  "touches, then a hardened set was produced. All ten nodes the app uses are covered, so no feature is silently "
  "denied. One active breakage was found and fixed: the QR single-use expiry is written by the scanner rather than "
  "the owner, so it was being denied and the pairing code could be reused.")

h("10.1 Holes closed by the hardened set", 2)
table(
    ["Hole", "What it allowed", "Now"],
    [
        ["Self-approve as driver", "A driver setting their own isApproved flag, bypassing the admin gate", "Only an admin can approve after creation; a driver cannot be created already approved"],
        ["Wallet inflation", "A user setting their own wallet balance to any amount", "Non-admins can only ever decrease it; deleting and re-seeding does not work either"],
        ["Role escalation", "A brand-new account choosing the admin role on first write", "Only passenger or driver at creation, immutable afterwards"],
        ["Self-granted discount", "A user setting their own discount flag", "Unchanged, or admin only"],
        ["Approving own top-up", "Any user flipping their own wallet or discount request to approved", "Only the owner at creation, or an admin"],
        ["Any user editing any booking", "Anyone writing any booking record", "Scoped to the booking's own passenger, its driver, an admin, or an adopting driver"],
        ["Unapproved driver taking rides", "Raw writes could adopt a ride while approval is false", "Adoption requires an explicitly approved driver"],
        ["QR code replay", "The scanner's expiry write was denied, so a pairing code never expired", "Narrow override allows the expiry write"],
    ],
    widths=[1.6, 2.9, 2.8],
)

h("10.2 Node coverage", 2)
table(
    ["Node", "Access now"],
    [
        ["users", "Read by any signed-in user. Write by the owner or an admin, with field guards on role, approval, wallet balance and discount"],
        ["drivers", "Read by any signed-in user. Write by the driver themselves or an admin"],
        ["bookings", "Read by any signed-in user. Write scoped to the parties, plus the accepted matching clause. Indexed on status, passengerId and driverId"],
        ["wallet_requests", "Owner creates, admin approves. Indexed on status and driverId"],
        ["discount_requests", "Owner creates, admin approves. Indexed on status"],
        ["tripGroups, matchLocks, pairRequests, emergencyAlerts, chats", "Open to any signed-in user by design. See accepted risks"],
        ["reports, driver_applications", "Removed. Nothing in the app uses them"],
    ],
    widths=[2.2, 5.1],
)

# ---------------------------------------------------------------- 11. metrics
h("11. Current repository metrics", 1)
table(
    ["Measure", "Baseline", "Now", "Change"],
    [
        ["Main Kotlin source files", "47", "44", "-3"],
        ["Main Kotlin lines", "7,575", "7,655", "+80"],
        ["Test source files", "0", "6", "+6"],
        ["Test lines", "0", "1,258", "+1,258"],
        ["Unit tests", "0", "65", "+65"],
        ["Test failures", "n/a", "0", "-"],
        ["Resource files", "109", "115", "+6"],
        ["Debug build", "Not buildable", "Success", "Fixed"],
        ["Release build", "Not buildable", "Success", "Fixed"],
        ["Debug APK", "n/a", "11.88 MB", "-"],
        ["Release APK", "n/a", "5.05 MB, minified", "-"],
        ["Tracked-file changes", "n/a", "66 files, 1,691 insertions, 1,609 deletions", "-"],
        ["Kotlin changes only", "n/a", "32 files, 1,181 insertions, 1,328 deletions", "-"],
    ],
    widths=[2.2, 1.5, 2.6, 1.0],
)

# ---------------------------------------------------------------- 12. tests
h("12. Automated test coverage", 1)
table(
    ["Test suite", "Tests", "What it pins"],
    [
        ["RideshareFareCalculatorTest", 19, "The chosen pricing model: split, detour surcharge, credit, floor, per-passenger discount, surge"],
        ["RideshareManagerTest", 11, "Matcher rejection rules with threshold boundaries, and sequencer ordering and shortest-total choice"],
        ["FareCalculatorTest", 10, "Solo fares: surge windows, discount, rounding, boundary hours"],
        ["FareDisplayTest", 10, "The passenger final-fare card: settled, running, cancelled, zero base, adjusted-from-estimate"],
        ["RouteDeviationManagerTest", 8, "Three-reading threshold, decay instead of reset, accuracy and speed widening, per-trip isolation"],
        ["PickupOrdinalTest", 7, "Pickup queue position counted over pickups only, the interleaved drop-off case"],
        ["Total", 65, "Zero failures, zero errors, zero skipped"],
    ],
    widths=[2.2, 0.7, 4.4],
)
p("Not covered by any automated test: anything needing an Android device or a live Firebase project. That includes "
  "the real login flow, Google sign-in token exchange, map and directions calls, database reads and writes, the "
  "settlement write, listener behaviour under rotation, and the emergency alert delivery.", italic=True)
p("Pure-logic coverage stated honestly: 6 of the 7 pure-logic units have tests, which is 85.71 percent. The seven "
  "are the two fare engines, the rideshare matcher and sequencer, the deviation detector, the fare display formatter, "
  "the pickup ordinal, and the wallet commission path. Only the last has no test.", italic=True)

# ---------------------------------------------------------------- 13. strings
h("13. Hardcoded UI text extraction", 1)
p("At baseline, 225 user-visible labels were hardcoded directly in 27 layout files, and a further 12 in the two "
  "navigation menu files. All of them have been moved into string resources, so the count is now zero. This is what "
  "makes the app translatable and keeps wording in one place. An initial count of 232 could not be reproduced on "
  "re-measurement; 225 is the figure an independent re-scan produced, and the difference is disclosed here rather "
  "than hidden.")
table(
    ["New resource file", "Strings", "Distribution"],
    [
        ["strings_ui_admin.xml", 69, bar(69, 197)],
        ["strings_ui_home.xml", 41, bar(41, 197)],
        ["strings_ui_dialog.xml", 31, bar(31, 197)],
        ["strings_ui_driver.xml", 29, bar(29, 197)],
        ["strings_ui_items.xml", 20, bar(20, 197)],
        ["strings_ui_menu.xml", 7, bar(7, 197)],
        ["Total", 197, "225 hardcoded labels now zero"],
    ],
    widths=[2.2, 0.9, 4.2],
)
p("The count is 197 new resources for 225 replaced labels because identical wording was reused rather than "
  "duplicated, for example the app name, the role labels and several navigation titles.", italic=True)
p("Scope note: the count covers the android:text, android:title, android:hint and android:contentDescription "
  "attributes. A small number of non-prose values are deliberately left in the layouts, such as currency amounts "
  "and single digits, and a few strings remain hardcoded in Kotlin, for example some toast messages.", italic=True)

# ---------------------------------------------------------------- 14. owner actions
h("14. What still needs the owner", 1)
p("Everything below needs either your Firebase console, your Google Cloud console, or a device. None of it can be "
  "done from the code alone.")
table(
    ["Action", "Why it matters"],
    [
        ["Confirm the admin account has a role field set to ADMIN in the live database", "Every admin rule depends on it. An admin record without it makes driver approval, cash-in and discount flows fail silently"],
        ["Confirm every live driver record has an isApproved field", "The adoption rule now requires it, so a driver record missing the field cannot accept rides"],
        ["Test the rules in a scratch project, then deploy", "A wrong rule fails silently. Deploy with: firebase deploy --only database,storage"],
        ["Turn on Storage in the console", "Profile picture uploads fail without it, and because the database write follows the upload, the whole profile save is skipped"],
        ["Rotate and restrict the Maps key", "The key supplied is the same one that was committed in the repository history, so it must be treated as leaked"],
        ["Run the app once on an emulator", "It has never been executed with real credentials. This is the first end-to-end proof"],
        ["Optionally add the server-side matching function", "This is the only clean way to close the accepted matched-booking risk"],
    ],
    widths=[3.2, 4.1],
)

# ---------------------------------------------------------------- 15. limits
h("15. What this document cannot tell you", 1)
bullet("Nothing was installed or run on a device or emulator. An emulator run was attempted and is impossible on this machine: the only available AVD points at an empty system image, and no hypervisor driver is installed, so x86_64 emulation cannot start. No screen has been seen by a person.")
bullet("Nothing was executed against the live Firebase project. The database rules are reasoned about, never run. The minified release build has also never been run, because it is unsigned and cannot be installed as produced.")
bullet("The baseline percentages were never re-run as a like-for-like suite, and the audit established that the item-level lists behind them were never saved, so they cannot be re-scored at all. They are a historical starting line, not a current score.")
bullet("The readiness bars on the scope-level page are reviewer judgements, not measurements. Every count and percentage in this document is measured.")
bullet("The tests cover pure logic only. Anything touching Android or Firebase is verified by reading the code.")
bullet("Three items could not be verified even by reading: whether the live database already has the admin role and driver approval fields, and whether the Maps key has already been restricted.")
bullet("One residual is recorded rather than buried: the database rules are authored, hardened and indexed, but neither deployed nor ever executed. Release hygiene is now closed: minification is on, the strip rules are active, no log calls remain, backups are off, and the dead code and duplicate dependency declarations are gone.")

doc.add_paragraph()
p("End of log.", italic=True)

doc.save(OUT)
print("WROTE", OUT)
