-- One-off operation on the live Neon database, run by hand on 2026-10-13 (DESIGN.md §16.3).
-- NOT a Flyway migration: Flyway only runs backend/src/main/resources/db/migration.
--
-- What it did: the app's tables had been created in PostgreSQL's shared "public" schema while the
-- app logged in as the Neon owner role (neondb_owner, which can do anything). This creates a
-- dedicated login + schema "vihanhspl" (like the VIHAN_APP schema on Oracle) and moves every table
-- and sequence there, data intact. The app finds them through DB_SCHEMA=vihanhspl (application.yml:
-- Hikari sets the connection's schema; Flyway keeps flyway_schema_history there).
--
-- Result on 2026-10-13: 19 tables (18 app tables + flyway_schema_history) and 16 sequences moved,
-- owned by vihanhspl; public left empty. Rows checked afterwards as vihanhspl: patient=41,
-- appointment=4, slot=3317, app_user=25, Flyway version 5.
--
-- How to run (as the owner role, never as the app login):
--   psql "host=<neon host> dbname=neondb user=neondb_owner sslmode=require" \
--        -v app_password='<new password>' -v ON_ERROR_STOP=1 -f 2026-10-13-move-to-vihanhspl-schema.sql
-- The password is passed in, never stored here. Neon wants a strong one (it was 32 random letters
-- and digits, kept in deploy/cloud-secrets.env as DB_PASSWORD; the owner login is DB_OWNER_*).
-- Order used live: run this, push the code with DB_SCHEMA, then switch Render's DB_USER / DB_PASSWORD.
-- The site was down from this script until Render had deployed the new code (about 3 minutes).

-- ---- Step 1: the app's own login and schema ----
CREATE ROLE vihanhspl WITH LOGIN PASSWORD :'app_password';
-- Lets the owner hand objects to vihanhspl (CREATE SCHEMA ... AUTHORIZATION, ALTER ... OWNER TO).
GRANT vihanhspl TO neondb_owner;
CREATE SCHEMA vihanhspl AUTHORIZATION vihanhspl;

-- ---- Step 2: move everything from public, in one transaction (all or nothing) ----
-- Indexes, constraints and identity sequences move with their tables.
BEGIN;
DO $$
DECLARE r record;
BEGIN
  FOR r IN SELECT tablename FROM pg_tables WHERE schemaname = 'public' LOOP
    EXECUTE format('ALTER TABLE public.%I SET SCHEMA vihanhspl', r.tablename);
    EXECUTE format('ALTER TABLE vihanhspl.%I OWNER TO vihanhspl', r.tablename);
  END LOOP;
  -- Sequences not owned by a table (seq_uhid for patient UHIDs).
  FOR r IN SELECT sequencename FROM pg_sequences WHERE schemaname = 'public' LOOP
    EXECUTE format('ALTER SEQUENCE public.%I SET SCHEMA vihanhspl', r.sequencename);
    EXECUTE format('ALTER SEQUENCE vihanhspl.%I OWNER TO vihanhspl', r.sequencename);
  END LOOP;
END $$;
COMMIT;

-- ---- Step 3: check ----
SELECT n.nspname AS schema, c.relkind::text AS kind, pg_get_userbyid(c.relowner) AS owner, count(*)
FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname IN ('public', 'vihanhspl') AND c.relkind IN ('r', 'S')
GROUP BY 1, 2, 3 ORDER BY 1, 2;
