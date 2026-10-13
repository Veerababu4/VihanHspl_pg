# Vihan Multi Speciality Hospital — Phase 1

Public website, no-login online booking, and staff portal (Doctors, Receptionists, IT Admin) for the
hospital network in **Miryalaguda, Huzurnagar and Kodad**.

The full design, decisions and milestones are in **[docs/DESIGN.md](docs/DESIGN.md)**.

> **This is the PostgreSQL edition** (repo `VihanHspl_pg`, branch `postgres-port`), deployed for free on
> Cloudflare Pages + Render + Neon: see **[deploy/README.md](deploy/README.md)**. The original Oracle edition is the
> `main` branch of `VihanHspl`.

| Part | Stack | Folder |
|---|---|---|
| Backend API | Java 21, Spring Boot 4.1, Spring Security, JPA/Hibernate, Flyway | `backend/` |
| Frontend | React 19, TypeScript, Vite, Tailwind CSS, React Router, TanStack Query | `frontend/` |
| Database | PostgreSQL 17 (local: Docker; cloud: Neon) | — |
| Hosting | Cloudflare Pages (website + `/api` proxy) → Render (backend, Docker) → Neon | `deploy/`, `render.yaml` |

## Prerequisites

| Tool | Version | Check |
|---|---|---|
| JDK | 21 | `java -version` |
| Node.js | 22+ | `node -v` |
| Docker Desktop | running | `docker version` |

Maven is **not** needed: use the wrapper `mvnw`.

Set `JAVA_HOME` once (PowerShell), then open a new terminal:

```powershell
[Environment]::SetEnvironmentVariable("JAVA_HOME", "C:\Program Files\Java\jdk-21.0.12", "User")
```

## 1. Start the local PostgreSQL database (first time)

```powershell
cd backend
# Pick your own password. vihan_app owns the database; Flyway creates all tables in it.
docker run -d --name vihan-pg --restart unless-stopped -p 5432:5432 `
  -e POSTGRES_DB=vihan -e POSTGRES_USER=vihan_app -e POSTGRES_PASSWORD=<app-password> `
  -e TZ=Asia/Kolkata -v vihan-pg-data:/var/lib/postgresql/data `
  postgres:17-alpine
```

It starts with Docker Desktop. Data survives restarts (volume `vihan-pg-data`).
To **reset** the database completely: `docker rm -f vihan-pg; docker volume rm vihan-pg-data`, then run the command above again.

## 2. Configure the backend

```powershell
cd backend
copy .env.example .env     # then edit .env: DB_PASSWORD = <app-password>, and fill in the secrets
```

`.env` is git-ignored. Every secret and environment-specific value lives there. Moving to the cloud
database later means changing `.env` only (DESIGN.md §13).

`AADHAAR_ENC_KEY` and `AADHAAR_HASH_KEY` are required (the backend won't start without them). Make each
one with PowerShell: `[Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))`.
Keep a safe copy: stored Aadhaar numbers can't be read without the first key.

## 3. Run

```powershell
# Terminal 1: API on http://localhost:8080
cd backend
.\mvnw spring-boot:run

# Terminal 2: website on http://localhost:5173
cd frontend
npm install
npm run dev
```

On first start, Flyway creates the schema (`V1`), the reference data (`V2`: 3 branches, 18 specialities) and,
in the `local` profile (or the cloud demo with `SAMPLE_DATA=true`), the **fictional sample data** (`db/dev-data`: 20 doctors with weekly schedules, placeholder gallery photos).
Uploaded images are stored in the database (table `stored_file`; `FILE_STORAGE=disk` keeps them in `backend/uploads/` instead).
The website shows a yellow **SAMPLE DATA** banner while sample data is in use.

| URL | What |
|---|---|
| http://localhost:5173 | Website (Vite dev server; proxies `/api` to the backend) |
| http://localhost:8080/swagger-ui | API docs (local profile only) |
| http://localhost:8080/actuator/health | Health check (includes the DB) |

## Booking (local)

- Public booking: http://localhost:5173/book · check / cancel / reschedule: http://localhost:5173/appointment-status
- The "I am human" check is Cloudflare Turnstile. Locally it uses Cloudflare's **test keys**, which always pass:
  put `VITE_TURNSTILE_SITE_KEY=1x00000000000000000000AA` in `frontend/.env.local` (git-ignored) and keep
  `TURNSTILE_SECRET_KEY=1x0000000000000000000000000000000AA` in `backend/.env`. Working offline? Set
  `CAPTCHA_ENABLED=false` in `backend/.env` and leave the site key out.
- SMS / WhatsApp are not sent yet: the backend log shows `[would send SMS] …` lines instead.

## Reception, dashboard and reports (local)

- http://localhost:5173/staff/appointments — the day's list (a receptionist starts on their own branch):
  check in (pick a suggested patient or register a new one, which gives the UHID), no-show, cancel,
  reschedule to any doctor, walk-ins, and the "patients to call" list after leave / holidays.
  Check-in works only for **today's** bookings, and no-show only after the booking time.
- http://localhost:5173/staff/dashboard — counts per hospital and doctor for any date; CSV / Excel export.
- http://localhost:5173/staff/reports — six reports with CSV / Excel export and print.
- Bookings nobody checked in become `NO_SHOW` at 23:55 IST (and at startup for earlier days).
- Doctors (`doctor001` …) see only their own appointments and figures, and complete their checked-in visits.

## Patients (local)

- http://localhost:5173/staff/patients — search by name, mobile, UHID or Aadhaar; register a patient
  (Aadhaar optional, stored encrypted, only ever shown as `XXXX-XXXX-1234`); profile with upcoming
  appointments and visits; reception / IT Admin can edit. Every profile view, edit and Aadhaar search is
  in the audit log.
- Same Aadhaar twice is refused; same mobile with a similar name shows the existing record first.
- The local profile adds 40 **fictional** sample patients (fictional, checksum-valid Aadhaar numbers).
- Online and counter booking accept an optional UHID; the booking is linked when the mobile matches.

## Clinical (doctors, local)

- Log in as a doctor (e.g. `doctor006`): **My queue** lists today's checked-in patients in token order.
  Open a visit to record vitals, complaint, diagnosis, notes, advice, follow-up and the prescription;
  **Complete visit** closes it and the appointment. **Print prescription** gives a letterhead page (Save as PDF works).
- **Health history** (from the patient profile or the visit) shows the medical profile and every past visit,
  across all doctors and branches. Reception and IT Admin never see clinical details.
- Sample patients come with about 60 fictional past visits. To get a patient into your queue, check one in
  (or add a walk-in) as reception for that doctor today.

## Staff portal logins (local)

Open http://localhost:5173/staff/login.

| Username | Role | Password |
|---|---|---|
| `admin` | IT Admin (created once from `BOOTSTRAP_ADMIN_PASSWORD`) | `BOOTSTRAP_ADMIN_PASSWORD` in `backend/.env`; must be changed at first login |
| `itadmin` | IT Admin (sample) | `DEV_SAMPLE_PASSWORD` in `backend/.env` |
| `reception.mlg`, `reception.hzn`, `reception.kdd` | Receptionist at that branch (sample) | `DEV_SAMPLE_PASSWORD` |
| `doctor001` … `doctor020` | Doctor login for sample doctor SAMPLE-001 … 020 (sees only their own schedule, appointments and dashboard) | `DEV_SAMPLE_PASSWORD` |

Sample logins exist only while sample data is on (`local` profile, or `SAMPLE_DATA=true` in the cloud demo). Five wrong passwords lock an account for 15 minutes;
an IT Admin can unlock it with "Reset password".

## Tests

```powershell
cd backend;  .\mvnw test          # unit tests + integration tests on a throwaway PostgreSQL container (needs Docker)
cd frontend; npm test             # Vitest + React Testing Library
cd frontend; npm run lint; npm run typecheck; npm run build
```

Run one backend test class: `.\mvnw test -Dtest=FoundationIntegrationTest`.
Run one frontend test file: `npx vitest run src/features/public/HomePage.test.tsx`.

**End-to-end and accessibility tests** (Playwright, `frontend/e2e`). Start Docker, the database and the backend
first. They use the installed Microsoft Edge, so there is no browser download:

```powershell
cd frontend; npm run e2e                      # builds the site, serves it on :4173 with the production headers, runs every flow
npx playwright test e2e/a11y.spec.ts          # only the WCAG 2.1 AA (axe-core) and keyboard checks
npx playwright show-report                    # HTML report, with a trace of any failure
```

Every E2E test fails on a Content-Security-Policy violation or a browser error. The tests use the sample logins
(`DEV_SAMPLE_PASSWORD` from `backend/.env`, or set `E2E_PASSWORD`) and add bookings and walk-ins to the local database.
`E2E_BASE_URL` runs them against a site that is already running instead, e.g. the Cloudflare Pages runtime:
`npx wrangler pages dev dist --binding BACKEND_URL=http://localhost:8081 --binding PROXY_SECRET=…` (port 8788).

**Performance check** (run on request, not part of `mvnw test`): `cd backend; .\mvnw test -Dgroups=perf -Dperf.excluded=`.
It loads a year of data into the throwaway test container (never the development database) and times the heavy screens.

**Production build with the real security headers:** `cd frontend; npm run build; npm run preview` →
http://localhost:4173 (the dev server on :5173 doesn't send them). In production, Cloudflare Pages sends the same headers (`frontend/public/_headers`; `deploy/Caddyfile` for an own server).

**UAT:** `docs/UAT-CHECKLIST.md` is the go-live checklist for hospital staff.

## Project layout

```
backend/
  src/main/java/com/vihan/hospital/
    config/      security, CORS, JPA auditing, OpenAPI, app properties
    common/      error handling (ApiError), request logging, masking, base entity, reference-data cache
    security/    JWT config, access/refresh tokens, password rules, current-user helper
    user/        login/refresh/logout/change-password, staff user admin, bootstrap admin, local sample logins
    audit/       audit log (write + IT Admin viewer)
    appointment/ booking, lookup, cancel, reschedule, counter booking, free-slot queries;
                 reception list, check-in, status changes, walk-ins, end-of-day no-show job
    patient/     registration with UHID, Aadhaar (Verhoeff check, AES-GCM + HMAC), duplicates, search,
                 profile, edit, visit list; local fictional sample patients
    visit/       visits opened at check-in or for walk-ins
    clinical/    doctor-only: queue, visit record + prescription, medical profile, health history
    dashboard/   day counts network → branch → doctor, export
    report/      reports (SQL) + CSV / XLSX writer
    captcha/     Cloudflare Turnstile verification
    notification/  SMS / WhatsApp hook (log-only in Phase 1)
    branch/ speciality/ doctor/ gallery/   public API (/api/public/**) + IT Admin API (/api/admin/**)
    schedule/    weekly timetables, slot planner + reconciler, leave, holidays, nightly slot job
    storage/     image upload checks, resizing, file storage (database or disk), /files/** serving
  src/main/resources/
    application.yml (+ -local / -cloud)
    db/migration/  Flyway: V1 schema, V2 reference data, V3 scheduling, V4 reception indexes, V5 stored files
    db/dev-data/   Flyway: fictional sample data (local profile; cloud demo with SAMPLE_DATA=true)
  Dockerfile       backend image for Render (512 MB)
frontend/
  src/api/        typed API client + React Query hooks
  src/components/ layout and shared UI
  src/features/   pages by area (public/, staff/ …); staff pages are lazy-loaded
  src/auth/       staff session (access token in memory, refresh via httpOnly cookie)
  src/lib/        formatting, SEO (schema.org) and dialog focus helpers
  public/placeholders/  labelled placeholder images used by the local sample data
  src/locales/    UI text (en.json; Telugu in Phase 2)
  e2e/            Playwright end-to-end + accessibility tests (npm run e2e)
  security-headers.ts   website security headers (preview server; mirrored in public/_headers and deploy/Caddyfile)
  functions/ cloudflare/   Cloudflare Pages Functions: /api and /files proxy to the backend
deploy/README.md  free deployment guide (Neon + Render + Cloudflare Pages); generate-secrets.ps1
deploy/Caddyfile  web server config for an own server (alternative)
render.yaml       Render Blueprint for the backend
docs/DESIGN.md    design, decisions, milestones, as-built notes
docs/HANDOVER.md  where things stand and how to resume
docs/UAT-CHECKLIST.md
```

## Conventions

- API errors always use one JSON shape: `{ timestamp, status, code, message, fieldErrors }`.
- Controllers return DTOs (Java records), never entities.
- Schema changes go in a **new** Flyway file (`V3__…sql`). Never edit an applied `V` file.
- No Aadhaar, clinical text or full mobile numbers in logs (`Masking.mobile()`).
- All times are IST (`Asia/Kolkata`), fixed in code.
- UI text goes in `src/locales/en.json`, not inline in components.
