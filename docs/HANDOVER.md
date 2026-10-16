# Handover — Vihan Multi Speciality Hospital, Phase 1

*Updated 2026-10-16, evening (PostgreSQL edition **live** at https://vihanhspl-pg.pages.dev; PRs #3–#12 merged;
UptimeRobot on). Start here, then read `docs/DESIGN.md` (v0.14, §16.3), `deploy/README.md` and `README.md`.*

## 0. Resume here (next session)

**2026-10-16, evening: nothing open.** `main` = `3c52f59` (PR #12, the UptimeRobot handover below, merged).
Since PR #12:
- The user deleted the plain-site monitor: **only one monitor now**, the `app-info` keep-awake one (id `804299976`).
- The API key in `cloud-secrets.env` is now the **read-only** key (starts `ur`). Reading works; writes such as
  `newMonitor` answer `not_authorized` (checked). Creating or changing monitors: the user does it in the UptimeRobot
  website, or swaps in a Main key temporarily.
- Keep-awake check: at 20:37 IST, after a long gap without my requests, `app-info` answered in 0.58 s and
  `branches` in 0.28 s (no cold start). Uptime 99.65% (1 day) / 99.95% (7 days) / 99.99% (30 days).
- One UptimeRobot DOWN event: 2026-10-16 16:54:15 IST, **502 Bad Gateway** for 306 s, then UP at 16:59:21. Cause
  unknown (probably Render restarting or waking). If DOWN events repeat, look at Render's events/logs.
- The user is learning APIs: `C:\Veera\Work\dummy_API_learning.sh` (outside the repo, never commit it) calls our
  public API, UptimeRobot `getMonitors`, prints request/response JSON and parses it with Python, Node and grep.

**2026-10-16: UptimeRobot keep-awake is on** (set up by the user, free plan, user's own account).
- Why: measured cold start 2026-10-16 16:32 IST: page HTML 0.46 s (Cloudflare), but the first `/api` call took
  **123 s**, almost all of it Render waking up (container + Spring Boot + Flyway, which also wakes Neon). Warm, the
  whole chain Browser → Cloudflare → Render → Neon answers in ~0.3–0.4 s (each hop ~0.1 s).
- Monitors (HTTP(s), every 5 min, alerts by email; the first one was later deleted by the user, see above):
  1. ~~`https://vihanhspl-pg.pages.dev`~~: the static site on Cloudflare (doesn't wake Render).
  2. `https://vihanhspl-pg.pages.dev/api/public/app-info`: **keeps Render awake** (5 min < Render's 15 min sleep).
     `app-info` doesn't touch the database, so Neon still sleeps (first DB call after 5 min idle: ~1 s extra).
     Don't ping Render's `/actuator/health` instead: it runs a DB check and would keep Neon awake 24/7.
- Render free: one service awake 24/7 uses ~744 of the 750 free instance-hours a month; don't add a second free
  Render service to that account.
- API key: `UPTIMEROBOT_API_KEY` (now the read-only key, see above) in `deploy/cloud-secrets.env` (git-ignored); read it from the file,
  never print it. `POST https://api.uptimerobot.com/v2/getMonitors` with `api_key=…` works; `getAccountDetails`
  answers `not_authorized`.
- UptimeRobot's free-plan terms on commercial use are unclear (sources disagree): check them before the real
  hospital relies on it.
- To check: after > 15 min with no visitors, the first `/api` call should take < ~1 s, not ~2 min.

**2026-10-14, night:** `main` = `ba62c0e`: PRs #9 (handover) and #10 (E2E for Recent bookings) merged.
The E2E suite has **12** tests: local **12/12**; live, the new test and the staff WCAG check 2/2 (the other 10
passed live after PR #8). PR #10's test `recent bookings: search by name, mobile or booking ID; a doctor sees only
their own` covers the manual check below: admin searches by booking ID (without dashes), mobile and part of a name,
reception sees the general list, `doctor001` sees only their own bookings. It only reads, so it is safe live.
Next: whatever the user asks (UptimeRobot done 2026-10-16, see above; it should also stop the Render stalls that
time out live E2E).

**2026-10-14 (later): PR #8** https://github.com/Veerababu4/VihanHspl_pg/pull/8, branch `feature/recent-bookings-search`
(`450f103` + merge of `main` `884627c`), **merged 2026-10-14 as `3a105a6`**. Asked for by the user:
- **Recent bookings** page `/staff/bookings` (staff menu, after Appointments) for IT Admin, reception and every
  doctor (by role, so doctor001–020 and any doctor added later). A doctor sees only their own bookings, the same rule
  as the rest of the portal; the user can ask to change that (one line in `ReceptionService.recent`).
- `GET /api/staff/appointments/recent?q=`: the latest 20 bookings by `created_at` (when made, not the appointment
  date). With `q`: name (contains, any case), mobile (3+ digits, contains) or booking ref (contains; a full ref
  works without dashes) on **any date**, newest 20 matches; `%` and `_` are taken literally.
- Rows reuse `AppointmentRow` (now exported) without action buttons; "Open in day list" goes to
  `/staff/appointments?date=…&branch=&q=<ref>`. Dates in another year show the year.
- Tests: backend **185/185** (new `ReceptionIntegrationTest.recentBookingsShowTheLatestTwentyAndSearchAnyDate`),
  frontend unit **71/71** (2 new in `Reception.test.tsx`), lint + typecheck clean. **E2E not run.** DESIGN §11.4 updated.
- The merge with `main` (after PR #7) had one conflict, `lazyPages.tsx`: the new page uses `reloadForNewVersion` too.
- **Deployed 2026-10-14** (Render deploy of `3a105a6`: success; Pages serves `RecentBookingsPage`). Live E2E
  **11/11** (also confirms PR #7 live). E2E for `/staff/bookings` added by PR #10 (see above).
- The stray `VeeraWork.claudesettings.local.json` (a copy of the Claude settings, made when a Windows path lost its
  backslashes in Git Bash) was deleted 2026-10-14 with the user's OK: same content as the real file.

**PR #7 merged** (`5b50534`) and live: the staff-screen fixes below (live E2E 11/11, see above).

**2026-10-14:** PR #6 deployed (backend live again, `/find` answers 400). Live E2E: **7/9**; the 2 failures were
real and are fixed on branch `fix/dialog-remount-and-scroll-regions` (PR #7), with the user's report of
"Failed to fetch dynamically imported module" on `/staff/schedule` and `/staff/queue` (a tab from before a deploy):
- Users/Doctors dialogs: one `Modal` from "Loading" to the form (swapping dialogs dropped focus for a frame).
- `ScrollRegion` (`components/ui.tsx`): focusable wrapper for tables that scroll sideways (axe on the live Audit log).
- `lib/reloadForNewVersion.ts`: every `lazy()` page import reloads once when its old file is gone.
- Local: E2E **11/11**, unit **69/69** (one earlier run had 2 timeouts while the PC was busy; 3 reruns clean).
- Deployed; live E2E 11/11 confirmed it.

**Backend deploys were failing (2026-10-13/14, fixed by PR #6).** Every Render deploy since PR #3 failed: Maven Central answers
Render's shared build hosts with **429 Too Many Requests** (first for `spring-boot-configuration-processor`, then even
for the wrapper's Maven download). Cloudflare deployed the frontend fine, so the live site was half-updated: the
one-field search called `/api/public/appointments/find` on the old backend and got 404.
- PR #5: the dependency layer also compiles a throwaway class (go-offline misses annotation processors); the real
  build runs offline (`-o`).
- Branch `fix/docker-build-maven-mirror`: Maven and all dependencies come from Google's Central mirror
  (`MVNW_REPOURL` + `.mvn/docker-settings.xml`). Verified with `docker build --no-cache --add-host
  repo.maven.apache.org:127.0.0.1` (Central unreachable): passes.
- **After it deploys:** `POST {}` to `https://vihanhspl-pg.pages.dev/api/public/appointments/find` must answer 400,
  not 404. Then check the live site and run the live E2E (§2).
- Render deploy status without the dashboard: `gh api repos/Veerababu4/VihanHspl_pg/deployments`, then
  `…/deployments/<id>/statuses`. Logs: the user downloads them from Render.

**PR #3** https://github.com/Veerababu4/VihanHspl_pg/pull/3 (merged 2026-10-13), both asked for by the user:
1. **Check my booking with one field.** The user chose "find with either, act with both":
   - `POST /api/public/appointments/find` takes exactly one of the booking ref or the mobile number. It needs the
     captcha and the lookup rate limit.
   - It returns masked `BookingSummary` results: name `R*** K***`, no doctor or speciality. Found by mobile, the
     ref is masked (`VMH-261013-****`); found by ref, the mobile is masked.
   - Opening, cancelling or rescheduling still needs both fields (the existing `/lookup`).
   - Page: with both fields the booking opens directly. With one field it lists results, and each result opens once
     the other field is entered. The captcha shows only for a one-field search.
2. **Staff Log out at the top right:** a top bar shows the name, role, a "View website" link and **Log out**. It's on
   every staff screen and on the forced password-change screen. On phones it stays in the ☰ menu.

Tests on the branch: backend **183/183**, frontend unit **69/69**, lint and typecheck clean, E2E **9/9**.

**Fixed (PR #4):** E2E `a11y.spec.ts` timed out in `waitForLoadState('networkidle')` on the booking
details step every time: the Turnstile widget keeps the network busy. `expectAccessible` now waits for `load` and
the first `h1`, then gives `networkidle` at most 5 s. The a11y spec dropped from 3.7 to 1.7 min.
UptimeRobot: done 2026-10-16 (§0).

## 1. Two editions, two repos

| Edition | Where | Status |
|---|---|---|
| **PostgreSQL** (learning project, free hosting) | local `main` → `origin` = `https://github.com/Veerababu4/VihanHspl_pg.git` (**public**) | **live**; this folder |
| **Oracle** (original, M1–M9) | GitHub only: remote `oracle` = `https://github.com/Veerababu4/VihanHspl.git` (private), `main` = `9d4b1d1` | unchanged since M9 |

- Since 2026-10-13 local `main` is the PostgreSQL code (it was the Oracle code before; that is safe on `oracle/main`).
  To look at the Oracle code: `git switch -c oracle-main oracle/main`. Never push PostgreSQL work to `oracle`.
- `VihanHspl_pg` is public: never commit secrets (real `.env` files and `deploy/cloud-secrets.env` are git-ignored).
- `backend/.env` (git-ignored) holds the PostgreSQL `DB_*` lines; the Oracle ones are kept as comments
  `# (Oracle, branch main) …`: swap them only when running the Oracle code.

## 2. PostgreSQL edition: where things stand

**Phase A (code, done 2026-10-13):** PostgreSQL port (DESIGN §16.3), images in the database, `ProxySecretFilter`,
`cloud` profile for Render, `backend/Dockerfile`, `render.yaml`, Cloudflare Pages Functions proxy + `_headers`,
`deploy/generate-secrets.ps1`, `deploy/README.md`.
Verified: backend **179/179**, frontend unit **67/67**, lint + typecheck clean, Docker image under 512 MB on an empty
DB, E2E **9/9** on `vite preview` and **9/9** through `wrangler pages dev` → cloud-mode container.
Fixed along the way: E2E `waitForCaptcha` (Turnstile now takes ~3.5 s to load; the helper waits per
`data-testid="captcha"` box), E2E walk-in uses the first seeded sample family (`96973 36649`), dialog-focus check
polls, mojibake "â€”" on the Schedule page.

**Phase B + C (done 2026-10-13): live.**

| Part | Where |
|---|---|
| Website | **https://vihanhspl-pg.pages.dev** (Cloudflare Pages project `vihanhspl-pg`, root `frontend`) |
| Backend | **https://vihan-backend.onrender.com** (Render Blueprint `vihan`, service `vihan-backend`, free, Singapore); answers 404 without `X-Proxy-Secret` except `/actuator/health` |
| Database | Neon project `vihan`, Singapore, **PostgreSQL 18** (tests run on 17), direct (unpooled) connection |

- All secrets and the Neon connection are in `deploy/cloud-secrets.env` (git-ignored). The user must not paste them into chat; read them from the file without printing.
- Live checks passed: pages + headers, `/api` via the proxy, E2E **9/9** against the live site (`E2E_BASE_URL=https://vihanhspl-pg.pages.dev`, `E2E_PASSWORD` = cloud `DEV_SAMPLE_PASSWORD`), real visitor IP recorded (IPv6 is stored in brackets: cosmetic).
- `CORS_ALLOWED_ORIGINS=https://vihanhspl-pg.pages.dev` is set in Render (done 2026-10-13). UptimeRobot keep-awake (on since 2026-10-16, §0): `/api/public/app-info` every 5 min (answered without the database, so Neon still sleeps).
- Free plan: Render sleeps after 15 min idle (first visit ~1–2 min; 123 s measured 2026-10-16); the UptimeRobot ping prevents that. Every push to `main` of `VihanHspl_pg` redeploys both.

**Own schema (2026-10-13):** the tables live in schema **`vihanhspl`**, owned by login `vihanhspl` (like `VIHAN_APP` on
Oracle), not in `public`. Code: `DB_SCHEMA` (`application.yml`, `render.yaml`). The live data was moved by hand:
`deploy/sql/2026-10-13-move-to-vihanhspl-schema.sql` (PR #1). `cloud-secrets.env`: `DB_USER` / `DB_PASSWORD` = the app
login `vihanhspl`; `DB_OWNER_USER` / `DB_OWNER_PASSWORD` = `neondb_owner` (admin only, never in Render).
- Render still has `DB_USER` / `DB_PASSWORD` = `neondb_owner`, so the live app logs in as the owner. The user chose
  (2026-10-13) not to switch it to `vihanhspl`: don't raise it as a to-do.
- SQL Developer (user's desktop): PostgreSQL tab, Hostname `<neon host>/neondb?` (the `/neondb?` trick, because the
  Database field can't be set before connecting), Port `5432`, user `vihanhspl` or `neondb_owner`.

## 3. How to start everything (local, PostgreSQL)

```powershell
& "C:\Users\Veera\AppData\Local\Programs\DockerDesktop\Docker Desktop.exe"   # vihan-pg starts with it
cd C:\Veera\Work\Vihan-Hospital-Phase1\backend;  .\mvnw spring-boot:run        # :8080
cd C:\Veera\Work\Vihan-Hospital-Phase1\frontend; npm run dev                   # :5173
```

- Local DB: container **`vihan-pg`** (postgres:17-alpine, volume `vihan-pg-data`, db `vihan`, user `vihan_app`).
  The app creates schema `vihanhspl` in it on first start (Flyway); older local tables may still sit in `public`.
- The test containers and image from 2026-10-13 were removed with the user's OK. `vihan-oracle` (+ volume
  `vihan-oracle-data`) is the Oracle edition's local DB: keep it.
- Logins: `backend/.env` (`DEV_SAMPLE_PASSWORD`, `BOOTSTRAP_ADMIN_PASSWORD`). Never print them in chat.

## 4. Tests

```powershell
cd backend;  .\mvnw test                       # 3-14 min (low memory), Testcontainers PostgreSQL (Docker must run)
cd frontend; npm test; npm run lint; npm run typecheck
cd frontend; npm run e2e                       # needs the backend on :8080
# Through the Pages runtime, against the cloud-mode container on :8081:
#   npm run build; npx wrangler@4 pages dev dist --port 8788 --binding BACKEND_URL=http://localhost:8081 --binding PROXY_SECRET=<from cloud-secrets.env>
#   $env:E2E_BASE_URL='http://localhost:8788'; $env:E2E_PASSWORD=<cloud DEV_SAMPLE_PASSWORD>; npx playwright test
```

## 5. Working agreements with the user

- Work stage by stage and **show live progress** ("stage 2/8, test 1/10 running"); summarise, then wait.
- **Every change through a pull request** (since 2026-10-13): branch from `origin/main` with `--no-track` (so a plain
  `git push` can't reach `main`), commit, `git push -u origin <branch>`, `gh pr create` (GitHub CLI, logged in as
  Veerababu4), send the link and wait. The user merges. If the user skips the PR, **ask before** merging or pushing to
  `main`: it deploys production. Manual database changes go into `deploy/sql/` in the same PR.
- **Ask for a fresh OK right before every push**, even if an earlier message said "push".
- Claude Code may push without a permission prompt only through `C:\Veera\Work\.claude\settings.local.json` (added
  by the user 2026-10-14): `Bash(git push -u origin feature/*)` (also `fix/*`, `docs/*`, `chore/*`) and
  `Bash(gh pr create *)`. Run them from the repo folder as plain commands (`cd` in a separate call, not `cd … &&`),
  or the rules don't match. Auto mode won't let Claude edit that file; the user edits it.
- A `cd` to a folder **outside** the project (e.g. a git worktree in a temp/scratch folder) is reset to the project
  folder, so the next command runs in the main checkout. On 2026-10-14 that renamed the user's checked-out branch and
  pushed it as PR #10 (fixed the same day). Outside the project use `git -C "<path>" …` and `gh … --head <branch>`,
  even if the push then asks for permission, and check `git -C <main> branch --show-current` afterwards.
- **Never delete anything** (files, folders, DB rows, containers, volumes, temp files) without asking first.
- When the session context gets near full, update this file at a clean point and hand off.

## 6. Gotchas learned

- PostgreSQL can't type a null parameter in `:x is null`: cast it in JPQL (`cast(:from as LocalDateTime)`).
  Long and enum parameters were fine.
- A `@Lob String` maps to an OID large object on PostgreSQL: use `@JdbcTypeCode(SqlTypes.LONG32VARCHAR)` for `TEXT`.
- The session time zone is pinned to IST by Hikari `connection-init-sql`, so `LOCALTIMESTAMP` defaults are IST.
- Windows PowerShell 5.1 has no `RandomNumberGenerator.GetBytes(n)`; use `RNGCryptoServiceProvider` (see the secrets script).
- `Start-Process cmd /c mvnw.cmd …` failed to start the backend; run `.\mvnw.cmd spring-boot:run` as a background task.
- Adding a Maven dependency needs a full backend restart. Vite reads `.env*` only at startup.
- Integration tests share one database: tests that create doctors deactivate them afterwards, and use random
  mobiles and client IPs (booking limits).
- `SlotRepository` modifying queries clear the persistence context: re-read entities after `bookIfOpen` / `releaseIfBooked`.
- Phone layouts: `<fieldset>` and grid items need `min-w-0`.
- Aadhaar search and duplicate checks use POST bodies, so the number never appears in URLs or logs.
- **The PC is low on memory.** A full backend test run took 3–14 minutes, and Claude Code once stopped the backend
  for memory. Run heavy things one at a time and stop the local backend when it's not needed.
- PowerShell: `R` is a built-in alias (`Invoke-History`), so don't name helper functions `R`. In `gh … --jq`, quote
  expressions with spaces carefully, or use simple expressions such as `.ahead_by`.
- A page that waits for data inside a dialog: render one `Modal` and swap its contents, never two `Modal`s
  (focus drops to the page between them). E2E "a dialog stays the same dialog" checks it.
- New lazy pages: `lazy(() => import('…').then((m) => ({ default: m.X }), reloadForNewVersion))`.
- PowerShell 5.1 mangles `"` inside native-exe arguments (`git commit -m`): write the message to a file, `-F`.
- Docker build: don't fetch from `repo.maven.apache.org` (429 on Render). Keep `MVNW_REPOURL` and
  `-s .mvn/docker-settings.xml` on every `mvnw` call in `backend/Dockerfile`.
- E2E: never wait for `networkidle` unbounded on a page with a Turnstile captcha; it may never come.
- `gh` isn't on PATH in the PowerShell tool: call `& "C:\Program Files\GitHub CLI\gh.exe"`.
- Feature branches: `git switch --no-track -c <branch> origin/main`. A plain `-c … origin/main` makes `main` the
  upstream, and then `git push` would go to production.
- E2E pages that wait for `networkidle` fail if a page loads Cloudflare Turnstile on arrival. That's why "Check my
  booking" shows the captcha only for one-field searches.
- The local DB `vihan` has the app in schema `vihanhspl`; leftovers from before the schema change are in `public`.
- Don't run the backend tests and Vitest at the same time on this laptop: the Testcontainers database failed to
  start (Flyway error after 40 min) and Vitest couldn't start a worker. One after the other, both pass.
- `ReceptionIntegrationTest`: every test's doctor is called "Dr. Reception Test", and the reports test finds rows by
  that name on next Wednesday. New tests there should use `todayBooking` (today), not Wednesday counter bookings.
- In Git Bash a Windows path like `C:\Veera\Work\.claude\x.json` loses its backslashes (it became a file named
  `VeeraWork.claudesettings.local.json` in the current folder): use forward slashes.
