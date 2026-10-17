-- Confirmation email asked for on the confirmation page (DESIGN.md §19.8). The address is kept for
-- later booking emails (cancel / reschedule); the count caps how often one booking can send mail,
-- so the form can't be used to flood a stranger's inbox.
ALTER TABLE appointment ADD COLUMN patient_email VARCHAR(120);
ALTER TABLE appointment ADD COLUMN email_sent_count INTEGER DEFAULT 0 NOT NULL;
