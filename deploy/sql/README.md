# deploy/sql — database operations run by hand

Scripts here change a **live** database once, by a person, as the Neon owner role.
They are a record of what was done (and how to repeat it on a fresh database), not migrations:
Flyway never runs this folder. Schema changes the app needs belong in
`backend/src/main/resources/db/migration` (`V6__…sql`), which Flyway applies on startup.

| File | Date | What |
|---|---|---|
| `2026-10-13-move-to-vihanhspl-schema.sql` | 2026-10-13 | Login + schema `vihanhspl`; all tables moved out of `public` |

Rules: name files `YYYY-MM-DD-what.sql`; never put passwords in them (pass them with `psql -v`);
add every manual change here in the same pull request as the code that depends on it.
