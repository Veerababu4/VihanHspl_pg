-- SAMPLE DATA (DESIGN.md §9.4). Loaded by the `local` profile, and by the `cloud` profile only when
-- SAMPLE_DATA=true (a public demo; the site then shows the "fictional sample data" banner).
-- All doctors are FICTIONAL. Registration numbers start with SAMPLE- so they can't be mistaken for real ones.
-- Repeatable migration: re-runs whenever this file changes, so every statement is idempotent (ON CONFLICT / NOT EXISTS).
-- Staff users, patients, slots, appointments and visits need app code (BCrypt, Aadhaar crypto, slot generator)
-- and are seeded by LocalDataSeeder in the milestone that builds that code (M3, M4/M5, M7, M8).

-- ===== Doctors =====
INSERT INTO doctor (registration_no, full_name, gender, speciality_id, qualification, designation, practice_start_year,
                    languages, bio, consultation_fee)
SELECT s.reg_no, s.full_name, s.gender, (SELECT speciality_id FROM speciality WHERE name = s.spec), s.qual, s.desig,
       s.start_year, 'Telugu, English, Hindi', 'Sample profile of a fictional doctor, for local testing only.', s.fee
FROM (VALUES
  ('SAMPLE-001', 'Dr. Srinivas Kandula', 'M', 'General Medicine', 'MBBS, MD (General Medicine)', 'Senior Consultant', 2004, 500),
  ('SAMPLE-002', 'Dr. Lakshmi Prasanna Bollam', 'F', 'General Surgery', 'MBBS, MS (General Surgery)', 'Consultant', 2008, 500),
  ('SAMPLE-003', 'Dr. Ravi Teja Gaddam', 'M', 'Orthopaedics', 'MBBS, MS (Ortho)', 'Consultant', 2010, 600),
  ('SAMPLE-004', 'Dr. Swathi Nalla', 'F', 'Obstetrics & Gynaecology', 'MBBS, MS (OBG)', 'Senior Consultant', 2009, 500),
  ('SAMPLE-005', 'Dr. Mahesh Chityala', 'M', 'Paediatrics', 'MBBS, MD (Paediatrics)', 'Consultant', 2012, 400),
  ('SAMPLE-006', 'Dr. Venkata Ramana Pulla', 'M', 'Cardiology', 'MBBS, MD, DM (Cardiology)', 'Chief Cardiologist', 2001, 800),
  ('SAMPLE-007', 'Dr. Anitha Gundla', 'F', 'Neurology', 'MBBS, MD, DM (Neurology)', 'Consultant', 2011, 700),
  ('SAMPLE-008', 'Dr. Kiran Kumar Yerra', 'M', 'Nephrology', 'MBBS, MD, DM (Nephrology)', 'Consultant', 2013, 700),
  ('SAMPLE-009', 'Dr. Sai Krishna Mallela', 'M', 'Urology', 'MBBS, MS, MCh (Urology)', 'Consultant', 2014, 700),
  ('SAMPLE-010', 'Dr. Padmaja Thota', 'F', 'Pulmonology', 'MBBS, MD (Pulmonary Medicine)', 'Consultant', 2010, 500),
  ('SAMPLE-011', 'Dr. Naveen Bairi', 'M', 'Gastroenterology', 'MBBS, MD, DM (Gastroenterology)', 'Consultant', 2012, 700),
  ('SAMPLE-012', 'Dr. Haritha Kommu', 'F', 'ENT', 'MBBS, MS (ENT)', 'Consultant', 2015, 400),
  ('SAMPLE-013', 'Dr. Prakash Dasari', 'M', 'Ophthalmology', 'MBBS, MS (Ophthalmology)', 'Senior Consultant', 2007, 400),
  ('SAMPLE-014', 'Dr. Divya Sree Pendem', 'F', 'Dermatology', 'MBBS, MD (DVL)', 'Consultant', 2016, 500),
  ('SAMPLE-015', 'Dr. Rajesh Vuppala', 'M', 'Dental', 'BDS, MDS (Oral & Maxillofacial Surgery)', 'Consultant', 2011, 300),
  ('SAMPLE-016', 'Dr. Sunitha Mekala', 'F', 'Anaesthesiology', 'MBBS, MD (Anaesthesia)', 'Senior Consultant', 2006, 500),
  ('SAMPLE-017', 'Dr. Vamshi Krishna Ponnala', 'M', 'Radiology', 'MBBS, MD (Radio-Diagnosis)', 'Consultant', 2013, 500),
  ('SAMPLE-018', 'Dr. Ashok Nerella', 'M', 'Emergency & Critical Care', 'MBBS, MD (Emergency Medicine)', 'Head - Emergency', 2014, 500),
  ('SAMPLE-019', 'Dr. Bhavani Jakkula', 'F', 'General Medicine', 'MBBS, MD (General Medicine)', 'Consultant', 2015, 400),
  ('SAMPLE-020', 'Dr. Ramesh Banoth', 'M', 'Paediatrics', 'MBBS, DCH, DNB (Paediatrics)', 'Consultant', 2017, 400)
) AS s (reg_no, full_name, gender, spec, qual, desig, start_year, fee)
ON CONFLICT (registration_no) DO UPDATE SET
  full_name = EXCLUDED.full_name, gender = EXCLUDED.gender, speciality_id = EXCLUDED.speciality_id,
  qualification = EXCLUDED.qualification, designation = EXCLUDED.designation,
  practice_start_year = EXCLUDED.practice_start_year, consultation_fee = EXCLUDED.consultation_fee;

-- ===== Doctor <-> branch and weekly schedules (20-min slots) =====
-- Day mask = Mon..Sun ('1' = works that day). Two-branch doctors use non-overlapping days.
-- Morning OPD 10:00-14:00 on the doctor's days; doctors marked 'E' also have an evening OPD 17:00-19:00.
-- The same list drives both doctor_branch and doctor_schedule.
CREATE TEMPORARY TABLE sample_plan (reg_no VARCHAR(20), branch VARCHAR(10), days VARCHAR(7), evening VARCHAR(1))
  ON COMMIT DROP;
INSERT INTO sample_plan VALUES
  ('SAMPLE-001', 'MLG', '1010100', 'E'),
  ('SAMPLE-001', 'HZN', '0101010', 'E'),
  ('SAMPLE-002', 'MLG', '1111110', '-'),
  ('SAMPLE-003', 'MLG', '1010100', '-'),
  ('SAMPLE-003', 'KDD', '0101010', '-'),
  ('SAMPLE-004', 'HZN', '1111110', 'E'),
  ('SAMPLE-005', 'KDD', '1111110', 'E'),
  ('SAMPLE-006', 'MLG', '1001000', '-'),
  ('SAMPLE-006', 'HZN', '0100100', '-'),
  ('SAMPLE-006', 'KDD', '0010010', '-'),
  ('SAMPLE-007', 'MLG', '1111110', '-'),
  ('SAMPLE-008', 'KDD', '1111100', '-'),
  ('SAMPLE-009', 'HZN', '1010100', '-'),
  ('SAMPLE-009', 'KDD', '0101010', '-'),
  ('SAMPLE-010', 'HZN', '1111110', '-'),
  ('SAMPLE-011', 'MLG', '1111100', '-'),
  ('SAMPLE-012', 'KDD', '1111110', 'E'),
  ('SAMPLE-013', 'HZN', '1111110', '-'),
  ('SAMPLE-014', 'MLG', '1010100', 'E'),
  ('SAMPLE-015', 'KDD', '1111110', 'E'),
  ('SAMPLE-016', 'MLG', '1111110', '-'),
  ('SAMPLE-017', 'HZN', '1111110', '-'),
  ('SAMPLE-018', 'KDD', '1111111', 'E'),
  ('SAMPLE-019', 'KDD', '1111110', 'E'),
  ('SAMPLE-020', 'MLG', '1010100', '-'),
  ('SAMPLE-020', 'HZN', '0101010', '-');

INSERT INTO doctor_branch (doctor_id, branch_id)
SELECT d.doctor_id, b.branch_id
FROM sample_plan p
JOIN doctor d ON d.registration_no = p.reg_no
JOIN branch b ON b.code = p.branch
WHERE NOT EXISTS (SELECT 1 FROM doctor_branch x WHERE x.doctor_id = d.doctor_id AND x.branch_id = b.branch_id);

INSERT INTO doctor_schedule (doctor_id, branch_id, day_of_week, start_time, end_time, slot_minutes, effective_from)
SELECT d.doctor_id, b.branch_id, w.dow, t.start_time, t.end_time, 20, DATE '2026-01-01'
FROM sample_plan p
JOIN doctor d ON d.registration_no = p.reg_no
JOIN branch b ON b.code = p.branch
JOIN generate_series(1, 7) AS w (dow) ON SUBSTR(p.days, w.dow, 1) = '1'
JOIN (VALUES ('M', '10:00', '14:00'), ('E', '17:00', '19:00')) AS t (kind, start_time, end_time)
  ON t.kind = 'M' OR (t.kind = 'E' AND p.evening = 'E')
WHERE NOT EXISTS (
  SELECT 1 FROM doctor_schedule x
  WHERE x.doctor_id = d.doctor_id AND x.branch_id = b.branch_id
    AND x.day_of_week = w.dow AND x.start_time = t.start_time);

-- ===== Placeholder images (served by the frontend from /placeholders; no real photos) =====
UPDATE branch SET image_url = '/placeholders/branch.svg' WHERE image_url IS NULL;

INSERT INTO gallery_image (branch_id, title, caption, image_url, thumb_url, sort_order)
SELECT b.branch_id, g.title, g.caption, g.img, g.img, g.sort_order
FROM (VALUES
  ('MLG', 'Reception', 'Placeholder image', '/placeholders/reception.svg', 1),
  ('MLG', 'Operation theatre', 'Placeholder image', '/placeholders/ot.svg', 2),
  ('HZN', 'OPD waiting area', 'Placeholder image', '/placeholders/opd.svg', 3),
  ('HZN', 'Laboratory', 'Placeholder image', '/placeholders/lab.svg', 4),
  ('KDD', 'Patient ward', 'Placeholder image', '/placeholders/ward.svg', 5),
  ('KDD', 'Pharmacy', 'Placeholder image', '/placeholders/pharmacy.svg', 6)
) AS g (branch, title, caption, img, sort_order)
JOIN branch b ON b.code = g.branch
WHERE NOT EXISTS (SELECT 1 FROM gallery_image x WHERE x.image_url = g.img AND x.branch_id = b.branch_id);
