# Handover — Vihan Multi Speciality Hospital, Phase 1

*Updated 2026-10-13, end of session (PostgreSQL edition **live** at https://vihanhspl-pg.pages.dev).
Start here, then read `docs/DESIGN.md` (v0.14, §16.3), `deploy/README.md` and `README.md`.*

## 0. Resume here (next session)

**Work in progress:** branch **`feature/booking-lookup-and-staff-logout`** (commit `b975493`, on top of `main`
`06619a5`), **draft PR #3**: https://github.com/Veerababu4/VihanHspl_pg/pull/3. Not merged, not deployed.
`git switch feature/booking-lookup-and-staff-logout` before continuing.

What it does (both asked for by the user on 2026-10-13):
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

Tests on the branch: backend **183/183**, frontend unit **69/69**, lint and typecheck clean, E2E **8/9**.

**Open issue (fix first):** E2E `a11y.spec.ts › public pages meet WCAG 2.1 AA` times out in
`page.waitForLoadState('networkidle')` on the **booking details step** (`/book`, heading "Book an appointment"; the
Cloudflare Turnstile widget is on that step).
- It passed 9/9 several times earlier on 2026-10-13, so it may be flaky (Turnstile network traffic, or a slow PC
  with low memory). It isn't caused by the new code: `/book` didn't change.
- Steps: re-run `npx playwright test e2e/a11y.spec.ts`. If it still fails, make `expectAccessible` wait for the
  page's main heading, or skip `networkidle` on pages that have a captcha, instead of waiting for `networkidle`.
- Then: E2E 9/9 → mark PR #3 ready → the user reviews and merges → check the live site (it deploys on merge).

**Also still open (user):**
- In Render, switch `DB_USER` / `DB_PASSWORD` to the `vihanhspl` login (§2). Afterwards check that the app runs as
  `vihanhspl`.
- UptimeRobot (optional).

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
- `CORS_ALLOWED_ORIGINS=https://vihanhspl-pg.pages.dev` is set in Render (done 2026-10-13). UptimeRobot (user, optional) keep-awake: `/api/public/app-info` every 5 min (answered without the database, so Neon still sleeps).
- Free plan: Render sleeps after 15 min idle (first visit ~1 min). Every push to `main` of `VihanHspl_pg` redeploys both.

**Own schema (2026-10-13):** the tables live in schema **`vihanhspl`**, owned by login `vihanhspl` (like `VIHAN_APP` on
Oracle), not in `public`. Code: `DB_SCHEMA` (`application.yml`, `render.yaml`). The live data was moved by hand:
`deploy/sql/2026-10-13-move-to-vihanhspl-schema.sql` (PR #1). `cloud-secrets.env`: `DB_USER` / `DB_PASSWORD` = the app
login `vihanhspl`; `DB_OWNER_USER` / `DB_OWNER_PASSWORD` = `neondb_owner` (admin only, never in Render).
- **To do (user):** in Render set `DB_USER=vihanhspl` and `DB_PASSWORD` (the app login from `cloud-secrets.env`). Until
  then the app still logs in as `neondb_owner`. Afterwards: check the app runs as `vihanhspl` and run the live E2E.
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
- Feature branches: `git switch --no-track -c <branch> origin/main`. A plain `-c … origin/main` makes `main` the
  upstream, and then `git push` would go to production.
- E2E pages that wait for `networkidle` fail if a page loads Cloudflare Turnstile on arrival. That's why "Check my
  booking" shows the captcha only for one-field searches.
- The local DB `vihan` has the app in schema `vihanhspl`; leftovers from before the schema change are in `public`.
