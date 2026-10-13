package com.vihan.hospital.schedule;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.vihan.hospital.branch.Branch;
import com.vihan.hospital.config.AppProperties;
import com.vihan.hospital.doctor.Doctor;
import com.vihan.hospital.doctor.DoctorRepository;
import com.vihan.hospital.schedule.Slot.BlockReason;
import com.vihan.hospital.schedule.Slot.Source;
import com.vihan.hospital.schedule.SlotPlanner.Closures;
import com.vihan.hospital.schedule.SlotPlanner.PlannedSlot;
import com.vihan.hospital.security.CurrentUser;

/**
 * Brings a doctor's future slots in line with the plan (DESIGN.md §10.2). Used after every template,
 * leave or holiday change, by the nightly job and by the "Generate" button, so all of them behave
 * the same way. Rules:
 * <ul>
 * <li>Past slots and BOOKED slots are never changed. A booked slot that falls on new leave / a holiday
 *     / a removed session flags its appointment {@code needs_action} for reception (FR-R7).</li>
 * <li>Missing planned slots are created (idempotent: running twice changes nothing).</li>
 * <li>OPEN timetable slots that are no longer planned are deleted, or blocked if an old (cancelled)
 *     appointment still refers to them.</li>
 * <li>Blocks made for leave / holidays are lifted when the leave / holiday is removed; manual blocks
 *     are only lifted by a person.</li>
 * </ul>
 */
@Service
public class SlotReconciler {

	private static final Logger log = LoggerFactory.getLogger(SlotReconciler.class);

	/** What changed; shown to the user and written to the audit log. */
	public record Result(int created, int removed, int blocked, int reopened, List<Long> bookedSlotsNeedingAction) {
		public static final Result EMPTY = new Result(0, 0, 0, 0, List.of());

		Result plus(Result o) {
			List<Long> needs = new ArrayList<>(bookedSlotsNeedingAction);
			needs.addAll(o.bookedSlotsNeedingAction);
			return new Result(created + o.created, removed + o.removed, blocked + o.blocked, reopened + o.reopened, needs);
		}

		public boolean changedAnything() {
			return created + removed + blocked + reopened > 0 || !bookedSlotsNeedingAction.isEmpty();
		}
	}

	private final DoctorRepository doctors;
	private final DoctorScheduleRepository schedules;
	private final SlotRepository slots;
	private final DoctorLeaveRepository leaves;
	private final BranchHolidayRepository holidays;
	private final JdbcTemplate jdbc;
	private final Clock clock;
	private final int windowDays;

	public SlotReconciler(DoctorRepository doctors, DoctorScheduleRepository schedules, SlotRepository slots,
			DoctorLeaveRepository leaves, BranchHolidayRepository holidays, JdbcTemplate jdbc, Clock clock,
			AppProperties props) {
		this.doctors = doctors;
		this.schedules = schedules;
		this.slots = slots;
		this.leaves = leaves;
		this.holidays = holidays;
		this.jdbc = jdbc;
		this.clock = clock;
		this.windowDays = props.scheduling().windowDays();
	}

	/** Today .. today + window - 1 (the rolling booking window, 14 days by default). */
	public LocalDate windowEnd() {
		return LocalDate.now(clock).plusDays(windowDays - 1L);
	}

	/**
	 * Reconciles [from, to] clipped to the future part of "the window or as far as slots already
	 * exist", so a leave a month away fixes existing slots without generating a month of new ones.
	 */
	@Transactional
	public Result reconcileAffected(Long doctorId, LocalDate from, LocalDate to) {
		LocalDate today = LocalDate.now(clock);
		LocalDate horizon = slots.findLastSlotDate(doctorId).filter(d -> d.isAfter(windowEnd())).orElse(windowEnd());
		LocalDate start = from.isBefore(today) ? today : from;
		LocalDate end = to.isAfter(horizon) ? horizon : to;
		return start.isAfter(end) ? Result.EMPTY : reconcile(doctorId, start, end);
	}

	@Transactional
	public Result reconcile(Long doctorId, LocalDate from, LocalDate to) {
		LocalDateTime now = LocalDateTime.now(clock);
		Doctor doctor = doctors.findWithDetailsById(doctorId).orElse(null);
		if (doctor == null) {
			return Result.EMPTY;
		}
		Set<Long> served = doctor.isActive()
				? doctor.getBranches().stream().filter(Branch::isActive).map(Branch::getId).collect(Collectors.toSet())
				: Set.of();   // inactive doctor: plan nothing, so open slots are removed (DESIGN.md §19.6)
		Closures closures = new Closures(leaves.findOverlapping(doctorId, from, to),
				holidays.findByHolidayDateBetweenOrderByHolidayDate(from, to));
		NavigableMap<LocalDateTime, PlannedSlot> plan = SlotPlanner.plan(
				schedules.findByDoctorIdAndActiveTrueOrderByDayOfWeekAscStartTimeAsc(doctorId), served, closures, from, to);

		int removed = 0;
		int blocked = 0;
		int reopened = 0;
		List<Long> needsAction = new ArrayList<>();
		Set<LocalDateTime> occupied = new HashSet<>();

		for (Slot s : slots.findByDoctorIdAndSlotDateBetweenOrderByStartAt(doctorId, from, to)) {
			occupied.add(s.getStartAt());
			if (!s.getStartAt().isAfter(now)) {
				continue;   // the past is history
			}
			PlannedSlot planned = plan.get(s.getStartAt());
			boolean matches = planned != null && planned.branchId().equals(s.getBranchId())
					&& planned.end().equals(s.getEndAt());
			BlockReason closed = closures.reason(s.getSlotDate(), s.getBranchId());
			boolean branchServed = served.contains(s.getBranchId());

			switch (s.getStatus()) {
				case BOOKED -> {
					boolean lost = closed != null || !branchServed || (s.getSource() == Source.TEMPLATE && !matches);
					if (lost) {
						needsAction.add(s.getId());
					}
				}
				case OPEN -> {
					if (s.getSource() == Source.TEMPLATE && matches) {
						if (planned.token() != s.getTokenNo()) {
							slots.retokenIfOpen(s.getId(), planned.token());
						}
					}
					else if (s.getSource() == Source.MANUAL && closed == null && branchServed) {
						// an extra session added by a person stays as it is
					}
					else if (s.getSource() == Source.MANUAL || closed != null) {
						blocked += slots.blockIfOpen(s.getId(), closed != null ? closed : BlockReason.REMOVED);
					}
					else if (slots.deleteIfOpenAndUnreferenced(s.getId()) == 1) {
						removed++;
						occupied.remove(s.getStartAt());
					}
					else {
						blocked += slots.blockIfOpen(s.getId(), BlockReason.REMOVED);
					}
				}
				case BLOCKED -> {
					if (s.getBlockReason() == BlockReason.MANUAL) {
						continue;   // only a person lifts a manual block
					}
					if (closed != null) {
						if (closed != s.getBlockReason()) {
							slots.changeBlockReason(s.getId(), closed);
						}
					}
					else if (matches || (s.getSource() == Source.MANUAL && branchServed)) {
						reopened += slots.reopenIfBlocked(s.getId(), matches ? planned.token() : s.getTokenNo());
					}
					else if (s.getBlockReason() != BlockReason.REMOVED) {
						slots.changeBlockReason(s.getId(), BlockReason.REMOVED);
					}
				}
			}
		}

		List<PlannedSlot> toCreate = plan.values().stream()
			.filter(p -> p.start().isAfter(now) && !occupied.contains(p.start()))
			.toList();
		insert(doctorId, toCreate);
		flagNeedsAction(needsAction);

		Result result = new Result(toCreate.size(), removed, blocked, reopened, needsAction);
		if (result.changedAnything()) {
			log.debug("Reconciled doctor {} {}..{}: {}", doctorId, from, to, result);
		}
		return result;
	}

	/** Rolling window for every doctor (nightly job, startup, "generate all"). */
	@Transactional
	public Result reconcileAllDoctors() {
		LocalDate today = LocalDate.now(clock);
		Result total = Result.EMPTY;
		Set<Long> ids = new HashSet<>(schedules.findDoctorIdsWithActiveSchedules());
		for (Long id : ids) {
			total = total.plus(reconcile(id, today, windowEnd()));
		}
		return total;
	}

	/** Batch insert: thousands of slots in a few round trips (JPA identity inserts can't batch). */
	private void insert(Long doctorId, List<PlannedSlot> planned) {
		if (planned.isEmpty()) {
			return;
		}
		Long userId = CurrentUser.id().orElse(null);
		jdbc.batchUpdate("""
				INSERT INTO slot (doctor_id, branch_id, slot_date, start_at, end_at, token_no, status, source, created_by)
				VALUES (?, ?, ?, ?, ?, ?, 'OPEN', 'TEMPLATE', ?)""", planned, 500, (ps, p) -> {
			ps.setLong(1, doctorId);
			ps.setLong(2, p.branchId());
			ps.setObject(3, p.start().toLocalDate());
			ps.setTimestamp(4, Timestamp.valueOf(p.start()));
			ps.setTimestamp(5, Timestamp.valueOf(p.end()));
			ps.setInt(6, p.token());
			ps.setObject(7, userId);
		});
	}

	/** FR-R7: bookings hit by leave / holiday / removed sessions show up on reception's to-do list. */
	private void flagNeedsAction(List<Long> slotIds) {
		if (slotIds.isEmpty()) {
			return;
		}
		jdbc.batchUpdate("""
				UPDATE appointment SET needs_action = TRUE, updated_at = LOCALTIMESTAMP
				WHERE slot_id = ? AND status = 'BOOKED' AND needs_action = FALSE""", slotIds, 200,
				(ps, id) -> ps.setLong(1, id));
	}

	/** Booked appointments on the given slots, for the "these patients must be called" warning. */
	public List<Map<String, Object>> appointmentsOnSlots(List<Long> slotIds) {
		if (slotIds.isEmpty()) {
			return List.of();
		}
		String ids = slotIds.stream().map(String::valueOf).collect(Collectors.joining(","));
		return jdbc.queryForList("""
				SELECT a.booking_ref, a.patient_name, a.patient_mobile, a.start_at, a.token_no, b.city
				FROM appointment a JOIN branch b ON b.branch_id = a.branch_id
				WHERE a.slot_id IN (%s) AND a.status = 'BOOKED' ORDER BY a.start_at""".formatted(ids));
	}

}
