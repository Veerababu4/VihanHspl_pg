# Handover — Vihan Multi Speciality Hospital, Phase 1

*Updated 2026-10-13 (PostgreSQL edition: **live** at https://vihanhspl-pg.pages.dev, E2E 9/9 on the live site).
Start here, then read `docs/DESIGN.md` (v0.14, §16.3), `deploy/README.md` and `README.md`.*

## 1. Two editions, two repos

| Edition | Local branch | Remote | Status |
|---|---|---|---|
| **Oracle** (original, M1–M9) | `main` | `oracle` → `https://github.com/Veerababu4/VihanHspl.git` (private) | M9 done, `9d4b1d1`; unchanged |
| **PostgreSQL** (learning project, free hosting) | `postgres-port` (tracks `origin/main`) | `origin` → `https://github.com/Veerababu4/VihanHspl_pg.git` (**public**) | Port + deployment files done, tested locally |

- `git switch main` = Oracle; `git switch postgres-port` = PostgreSQL. Push Oracle work with `git push oracle main`.
- `VihanHspl_pg` is public: never commit secrets (real `.env` files and `deploy/cloud-secrets.env` are git-ignored).
- `backend/.env` is shared by both branches (git-ignored). It now holds the PostgreSQL `DB_*` lines; the Oracle ones
  are kept as comments `# (Oracle, branch main) …`: swap them when working on `main`.

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
- To do (user): in Render set `CORS_ALLOWED_ORIGINS=https://vihanhspl-pg.pages.dev` (the name became `vihanhspl-pg`, not `vihan-hospital`). Optional: UptimeRobot on `/api/public/branches`.
- Free plan: Render sleeps after 15 min idle (first visit ~1 min). Every push to `main` of `VihanHspl_pg` redeploys both.

## 3. How to start everything (local, PostgreSQL)

```powershell
& "C:\Users\Veera\AppData\Local\Programs\DockerDesktop\Docker Desktop.exe"   # vihan-pg starts with it
cd C:\Veera\Work\Vihan-Hospital-Phase1\backend;  .\mvnw spring-boot:run        # :8080
cd C:\Veera\Work\Vihan-Hospital-Phase1\frontend; npm run dev                   # :5173
```

- Local DB: container **`vihan-pg`** (postgres:17-alpine, volume `vihan-pg-data`, db `vihan`, user `vihan_app`).
  Also in it: database `vihan_cloudtest` (used for the cloud-mode container test).
- Containers the user may want removed (ask first, never delete without asking): `vihan-postgres` (stopped, broken:
  created with an empty password) + volume `vihan-postgres-data`; `vihan-cloudtest` (cloud-mode test container,
  port 8081); `vihan-oracle` is the Oracle edition's DB, keep it.
- Logins: `backend/.env` (`DEV_SAMPLE_PASSWORD`, `BOOTSTRAP_ADMIN_PASSWORD`). Never print them in chat.

## 4. Tests

```powershell
cd backend;  .\mvnw test                       # ~3 min, Testcontainers PostgreSQL (Docker must run)
cd frontend; npm test; npm run lint; npm run typecheck
cd frontend; npm run e2e                       # needs the backend on :8080
# Through the Pages runtime, against the cloud-mode container on :8081:
#   npm run build; npx wrangler@4 pages dev dist --port 8788 --binding BACKEND_URL=http://localhost:8081 --binding PROXY_SECRET=<from cloud-secrets.env>
#   $env:E2E_BASE_URL='http://localhost:8788'; $env:E2E_PASSWORD=<cloud DEV_SAMPLE_PASSWORD>; npx playwright test
```

## 5. Working agreements with the user

- Work stage by stage and **show live progress** ("stage 2/8, test 1/10 running"); summarise, then wait.
- **Commit only when the user says so; ask before any push.**
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
