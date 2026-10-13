# Deploying for free: Cloudflare Pages + Render + Neon

*Repo `VihanHspl_pg`, branch `main` (DESIGN.md §16). ₹0/month; no server to look after.*

```
Browser ──► Cloudflare Pages  https://<project>.pages.dev
              ├─ website (frontend/dist, headers from public/_headers)
              └─ functions/  /api/* and /files/* ──► Render web service (backend Docker image)
                                                        └─► Neon PostgreSQL (data + uploaded images)
```

- **One origin:** the browser only talks to `pages.dev`; the Pages Function forwards `/api` and `/files`
  to Render with the shared `PROXY_SECRET` and the visitor's IP. The backend answers 404 to anything
  else (except `/actuator/health`), so its own `onrender.com` URL is useless to outsiders.
- **Auto-deploy:** every `git push` to `main` rebuilds Render (when `backend/` changed) and Pages.
- **Free-plan behaviour:** Render sleeps after 15 min without visitors; the next visit waits about
  a minute while it starts. On every start the slot window and the no-show job catch up.
  Neon scales to zero when idle and wakes by itself.

## 0. Secrets (once)

```powershell
powershell -ExecutionPolicy Bypass -File deploy\generate-secrets.ps1
```

Creates `deploy/cloud-secrets.env` (git-ignored) with fresh `PROXY_SECRET`, `JWT_SECRET`,
Aadhaar keys and passwords. Open it in an editor, never paste it into chat; keep a copy in a
password manager (the Aadhaar keys also offline: losing `AADHAAR_ENC_KEY` makes stored numbers unreadable).

Decide the website name now, e.g. **`vihan-hospital`** → `https://vihan-hospital.pages.dev`, and put
it in `CORS_ALLOWED_ORIGINS`.

## 1. Neon (database)

1. <https://neon.com> → **Sign up** with GitHub.
2. **Create project**: name `vihan`, Postgres **17**, region **AWS Asia Pacific (Singapore)**.
3. **Connect** → turn **Connection pooling off** (Flyway migrations need a direct connection) →
   copy host, database, user and password into `cloud-secrets.env`:
   `DB_URL=jdbc:postgresql://<host>/<database>?sslmode=require`, `DB_USER=…`, `DB_PASSWORD=…`.

## 2. Render (backend)

1. <https://render.com> → **Sign up** with GitHub → allow access to **`VihanHspl_pg`** only.
2. **New → Blueprint** → pick `VihanHspl_pg` → Render reads `render.yaml` (service `vihan-backend`,
   free plan, Singapore).
3. It asks for every `sync: false` value: copy them from `cloud-secrets.env`
   (`DB_URL`, `DB_USER`, `DB_PASSWORD`, `JWT_SECRET`, `AADHAAR_ENC_KEY`, `AADHAAR_HASH_KEY`,
   `BOOTSTRAP_ADMIN_PASSWORD`, `PROXY_SECRET`, `CORS_ALLOWED_ORIGINS`, `DEV_SAMPLE_PASSWORD`).
4. **Apply**. The first build takes ~10 min. Then open
   `https://vihan-backend.onrender.com/actuator/health` → `{"status":"UP"}`
   (the exact URL is on the service page). Any other path must give 404.

## 3. Cloudflare Pages (website)

1. <https://dash.cloudflare.com> → sign up → **Workers & Pages → Create → Pages → Connect to Git**
   → `VihanHspl_pg`.
2. Project name = the name chosen in step 0. Production branch `main`.
   Framework preset **None**; build command `npm run build`; output directory `dist`;
   root directory **`frontend`**.
3. **Variables** (Production):

   | Name | Value | Type |
   |---|---|---|
   | `BACKEND_URL` | `https://vihan-backend.onrender.com` (from step 2) | Text |
   | `PROXY_SECRET` | from `cloud-secrets.env` | **Secret** |
   | `VITE_TURNSTILE_SITE_KEY` | `1x00000000000000000000AA` (Cloudflare's test key) | Text |
   | `VITE_API_BASE_URL` | `/api` | Text |

4. **Save and Deploy**, then open the site.

## 4. Check the live site

- Home, doctors, branches, gallery load; the yellow "sample data" banner shows.
- Book an appointment → find it → reschedule → cancel.
- `/staff/login` as `admin` with `BOOTSTRAP_ADMIN_PASSWORD` (you must change it on first login),
  or the sample logins `itadmin`, `reception.mlg`, `doctor001` … with `DEV_SAMPLE_PASSWORD`.
- Browser dev tools: no CSP errors; response headers as in `frontend/public/_headers`.
- Optional: UptimeRobot (free) on `https://<project>.pages.dev/api/public/app-info` (no database query, not rate-limited) every 5 min
  emails you when the site is down (this also keeps Render awake during the day; it uses the
  750 free hours/month, enough for one service around the clock).

## Real data?

Not on this setup as it stands: `SAMPLE_DATA=true` fills it with fictional doctors, patients and
staff logins anyone with the sample password can use. For real patients: set `SAMPLE_DATA=false`
and `FLYWAY_LOCATIONS=classpath:db/migration` on a **fresh** Neon database, remove
`DEV_SAMPLE_PASSWORD`, use real Turnstile keys (`TURNSTILE_SECRET_KEY` on Render,
`VITE_TURNSTILE_SITE_KEY` on Pages, `TURNSTILE_HOSTNAME` = your domain), and review DESIGN §16's
go-live checklist (backups, domain, monitoring).
