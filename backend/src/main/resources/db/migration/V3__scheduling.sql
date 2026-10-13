-- M4 scheduling (DESIGN.md §5.4, §10.2).

-- Why a slot is BLOCKED, so the system knows which blocks it may undo:
--   MANUAL  = blocked by a doctor / IT Admin (only a person unblocks it)
--   LEAVE   = doctor on leave       (reopened when the leave is removed)
--   HOLIDAY = branch holiday        (reopened when the holiday is removed)
--   REMOVED = no longer in the weekly timetable, but can't be deleted because a past
--             (cancelled) appointment still refers to it
ALTER TABLE slot ADD block_reason VARCHAR(10)
  CONSTRAINT ck_slot_block_reason CHECK (block_reason IN ('MANUAL','LEAVE','HOLIDAY','REMOVED'));
ALTER TABLE slot ADD CONSTRAINT ck_slot_blocked_reason
  CHECK ((status = 'BLOCKED' AND block_reason IS NOT NULL) OR (status <> 'BLOCKED' AND block_reason IS NULL));

CREATE INDEX ix_slot_date ON slot (slot_date, branch_id);

-- One holiday per branch per date; branch_id NULL = all branches (COALESCE makes NULL comparable).
CREATE UNIQUE INDEX ux_holiday_branch_date ON branch_holiday (COALESCE(branch_id, 0), holiday_date);
