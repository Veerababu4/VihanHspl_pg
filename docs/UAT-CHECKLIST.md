# User Acceptance Testing (UAT) — Vihan Multi Speciality Hospital, Phase 1

*For hospital staff, run before go-live (DESIGN.md §15, §18 M9). Version 1, 2026-10-12.*

## How to use this list

- Run it on the **test server** (the cloud setup of M10) with the **hospital's real data** entered:
  branches (addresses, phones, map links, timings), specialities, doctors (photos with consent),
  weekly timetables, holidays. Patients may be test patients with clearly fake names (e.g. "Test Patient 1").
- Never use a real patient's Aadhaar number while testing.
- Each person ticks their own section. Write problems in the **Notes** column with the date and
  the screen (a phone photo of the screen helps). The developer fixes them, and the row is re-tested.
- Test on the devices staff really use: the reception PC, a doctor's PC or tablet, and at least
  one **Android phone** and one **iPhone** for the public website.
- Result: ✅ works · ❌ problem (see notes) · — not applicable.

| Who | Login |
|---|---|
| IT Admin | own account (created by `admin`) |
| Receptionist | one per hospital: Miryalaguda, Huzurnagar, Kodad |
| Doctor | at least two doctors, one of them working at two hospitals |
| Patient | no login: anyone with a phone |

---

## A. Before testing (IT Admin)

| # | Check | Result | Notes |
|---|---|---|---|
| A1 | `admin` logged in with the first-run password and was forced to choose a new one | | |
| A2 | Each branch: name, address, phone, emergency phone, email, timings and Google Maps link are correct | | |
| A3 | Speciality list matches what the hospital offers (inactive ones hidden from the website) | | |
| A4 | Every doctor: name, speciality, qualification, registration no., experience, fee, hospitals, photo | | |
| A5 | Weekly timetable entered for every doctor; slots appear for the next 14 days | | |
| A6 | Known holidays entered (hospital-wide and per branch) | | |
| A7 | Staff accounts created: each receptionist with the right home hospital, each doctor linked to their doctor profile | | |
| A8 | Each new user logged in with the temporary password and had to change it | | |

## B. Public website (anyone, on a phone and a PC)

| # | Check | Result | Notes |
|---|---|---|---|
| B1 | Home page loads quickly on mobile data (4G); hospital names, phones and emergency numbers are correct | | |
| B2 | Red **Emergency** button on the phone calls the right number | | |
| B3 | Doctors page: search by name and filter by speciality and hospital work | | |
| B4 | Doctor profile shows the right details, fee and "Book appointment" | | |
| B5 | Branch page shows the map and "Get directions" opens Google Maps at the right place | | |
| B6 | Gallery, About, Contact and Privacy pages are correct and contain no placeholder text | | |
| B7 | No yellow "SAMPLE DATA" banner anywhere | | |
| B8 | Pages look right on a small phone: nothing cut off, no sideways scrolling | | |

## C. Online booking (patient, on a phone)

| # | Check | Result | Notes |
|---|---|---|---|
| C1 | Book: choose hospital → doctor → date → time; only the doctor's working days and free times are offered | | |
| C2 | The "I am human" check appears and passes | | |
| C3 | Confirmation shows booking number, token, date, time, hospital and fee; booking appears at reception | | |
| C4 | A 4th booking on the same mobile for the same day is refused with a clear message | | |
| C5 | "My booking": find with booking number + mobile; wrong mobile shows nothing | | |
| C6 | Reschedule to another time: same booking number, new token; the old time becomes free again | | |
| C7 | Cancel; the time becomes free again; cancelling less than 1 hour before is refused | | |
| C8 | A doctor's leave or holiday day can't be booked | | |
| C9 | Two people booking the same time at the same moment: only one succeeds | | |
| C12 | Every public page starts with the scrolling red "TEST WEBSITE – not a real hospital" line; Pause stops it | | |
| C10 | Confirmation page → "Share on WhatsApp" opens WhatsApp with the booking details (no mobile number); send it to yourself | | |
| C11 | Confirmation page → email yourself the confirmation: it arrives (check spam), says TEST / not a real hospital at the top, details match; a 4th send for the same booking is refused | | |

## D. Reception (each receptionist at their own hospital)

| # | Check | Result | Notes |
|---|---|---|---|
| D1 | Login opens the dashboard; the appointment list starts on **your** hospital and today | | |
| D2 | Counter / phone booking for a patient who calls in | | |
| D3 | Check in an online booking: existing patient suggested by mobile; new patient registered and gets a UHID | | |
| D4 | Register a patient with Aadhaar: shown only as XXXX-XXXX-1234; the same Aadhaar twice is refused | | |
| D5 | Same mobile + similar name warns about a possible duplicate before registering | | |
| D6 | Walk-in: patient added to the doctor's queue with the next token | | |
| D7 | Mark no-show (only after the booking time) and cancel with a reason | | |
| D8 | Reschedule a patient to another doctor or day | | |
| D9 | Doctor goes on leave → affected bookings appear in "Patients to call" | | |
| D10 | Patient search by name, mobile, UHID and Aadhaar; profile shows visits but **no** diagnosis or prescriptions | | |
| D11 | Edit a patient's address / mobile; the change shows in the audit log (IT Admin) | | |
| D12 | Dashboard counts for today match the appointment list; CSV / Excel export opens in Excel | | |
| D13 | Reports (all six) for last month; export and print work | | |
| D14 | Bookings nobody checked in show as No-show the next morning | | |

## E. Doctor

| # | Check | Result | Notes |
|---|---|---|---|
| E1 | **My queue** shows today's checked-in patients in token order; allergies shown in red | | |
| E2 | Open a visit: vitals, complaint, diagnosis, notes, advice, follow-up date; **Save** keeps everything | | |
| E3 | Prescription: add, change order and remove medicines | | |
| E4 | **Print prescription**: letterhead, doctor details, patient, medicines are correct; Save as PDF works | | |
| E5 | **Complete visit**: patient moves to "Seen"; the visit can no longer be changed | | |
| E6 | Health history shows earlier visits from all doctors and hospitals | | |
| E7 | Medical profile (allergies, conditions, medicines) can be updated | | |
| E8 | Doctor sees only their own schedule, appointments and dashboard | | |
| E9 | Doctor can add leave; patients with bookings on those days go to "Patients to call" | | |

## F. IT Admin

| # | Check | Result | Notes |
|---|---|---|---|
| F1 | Add, edit and deactivate a staff user; a deactivated user can't log in | | |
| F2 | Reset password gives a one-time password; it also unlocks a locked account | | |
| F3 | 5 wrong passwords lock the account for 15 minutes | | |
| F4 | Audit log shows logins, patient views and edits, exports; filters by user, action and date work | | |
| F5 | Add / change / remove gallery photos; photos look right and load fast on a phone | | |
| F6 | Upload of a non-image file or a file over 5 MB is refused | | |

## G. Security and privacy (IT Admin + one receptionist)

| # | Check | Result | Notes |
|---|---|---|---|
| G1 | Receptionist typing a doctor page address (e.g. `/staff/queue`) does not see clinical data | | |
| G2 | After **Log out**, the browser's Back button does not show staff pages | | |
| G3 | Leaving the portal idle: after 8 hours the user must log in again | | |
| G4 | The site opens only on `https://` with the padlock; `http://` redirects to `https://` | | |
| G5 | Privacy notice on the booking form is the text approved by the hospital | | |
| G6 | Printed or exported files are kept only on hospital PCs (staff instruction) | | |

## H. Accessibility and ease of use (anyone)

Automated WCAG 2.1 AA checks run on every main screen (`frontend/e2e/a11y.spec.ts`). These need a person:

| # | Check | Result | Notes |
|---|---|---|---|
| H1 | Booking can be done with the keyboard only (Tab, Shift+Tab, Enter, Space, arrow keys) | | |
| H2 | The focused button or field is always clearly visible (outline) | | |
| H3 | In pop-up windows (check-in, walk-in), Tab stays inside; Escape closes; focus returns to the button | | |
| H4 | With the phone's text size set to large, pages are still usable | | |
| H5 | Error messages say what is wrong and are next to the field | | |
| H6 | Optional: Android TalkBack / iPhone VoiceOver reads the booking steps in a sensible order | | |

## I. Daily operations (IT Admin, during the trial week)

| # | Check | Result | Notes |
|---|---|---|---|
| I1 | Nightly backup ran (date of the latest backup file) and a copy exists outside the server | | |
| I2 | A restore was tested into a test database | | |
| I3 | The uptime alert reaches the IT contact when the site is down (test by stopping it briefly) | | |
| I4 | The site worked on a busy morning without slowness at reception | | |

---

## Sign-off

| Role | Name | Date | Signature |
|---|---|---|---|
| Hospital management | | | |
| IT Admin | | | |
| Reception (one per hospital) | | | |
| Doctor | | | |
| Developer | | | |

Go-live only when every row is ✅ or —, or a ❌ is accepted in writing by hospital management.
