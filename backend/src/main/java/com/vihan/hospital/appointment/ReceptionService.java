package com.vihan.hospital.appointment;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.vihan.hospital.appointment.ReceptionDtos.Actions;
import com.vihan.hospital.appointment.ReceptionDtos.StaffAppointmentRow;
import com.vihan.hospital.appointment.ReceptionDtos.StatusChangeRequest;
import com.vihan.hospital.appointment.ReceptionDtos.WalkInRequest;
import com.vihan.hospital.audit.AuditAction;
import com.vihan.hospital.audit.AuditService;
import com.vihan.hospital.branch.Branch;
import com.vihan.hospital.branch.BranchLookup;
import com.vihan.hospital.common.error.ApiException.BadRequestException;
import com.vihan.hospital.common.error.ApiException.ConflictException;
import com.vihan.hospital.common.error.ApiException.NotFoundException;
import com.vihan.hospital.doctor.Doctor;
import com.vihan.hospital.doctor.DoctorRepository;
import com.vihan.hospital.patient.Patient;
import com.vihan.hospital.patient.PatientDtos.PatientMatch;
import com.vihan.hospital.patient.PatientRepository;
import com.vihan.hospital.patient.PatientService;
import com.vihan.hospital.schedule.SlotRepository;
import com.vihan.hospital.security.CurrentUser;
import com.vihan.hospital.user.Role;
import com.vihan.hospital.visit.Visit;
import com.vihan.hospital.visit.VisitRepository;

/**
 * Reception's side of appointments (DESIGN.md §5.5, §10.4): the day list, check-in with patient
 * linking, no-show / cancel / complete, walk-ins and staff reschedules. Doctors see and change only
 * their own appointments; only the doctor (or IT Admin) completes a visit.
 */
@Service
public class ReceptionService {

	/** "Often doesn't come": this many no-shows ... */
	static final int FREQUENT_NO_SHOWS = 3;
	/** ... within this many days. */
	static final int NO_SHOW_DAYS = 30;
	/** Keeps IN lists (one bind value each) to a moderate size. */
	private static final int IN_CHUNK = 900;
	/** How many bookings the "Recent bookings" list and its search show. */
	static final int RECENT_LIMIT = 20;

	private final AppointmentRepository appointments;
	private final BookingService booking;
	private final PatientService patientService;
	private final PatientRepository patients;
	private final VisitRepository visits;
	private final DoctorRepository doctors;
	private final SlotRepository slots;
	private final BranchLookup branches;
	private final AuditService audit;
	private final Clock clock;

	public ReceptionService(AppointmentRepository appointments, BookingService booking, PatientService patientService,
			PatientRepository patients, VisitRepository visits, DoctorRepository doctors, SlotRepository slots,
			BranchLookup branches, AuditService audit, Clock clock) {
		this.appointments = appointments;
		this.booking = booking;
		this.patientService = patientService;
		this.patients = patients;
		this.visits = visits;
		this.doctors = doctors;
		this.slots = slots;
		this.branches = branches;
		this.audit = audit;
		this.clock = clock;
	}

	// ---- lists ----

	/**
	 * FR-R1. A full booking reference in {@code q} finds that booking on any date; anything else
	 * filters the day's list by name, mobile or reference.
	 */
	@Transactional(readOnly = true)
	public List<StaffAppointmentRow> list(LocalDate date, String branchCode, Long doctorId, String status, String q) {
		Who who = who();
		if (who.role() == Role.DOCTOR) {
			if (who.doctorId() == null) {
				return List.of();
			}
			doctorId = who.doctorId();   // a doctor sees only their own appointments
		}
		Long branchId = branches.idOf(branchCode);
		Appointment.Status st = parseStatus(status);
		String query = q == null ? "" : q.strip();
		String ref = BookingRefs.normalize(query);

		List<Appointment> found;
		if (ref.matches("VMH-\\d{6}-[A-Z0-9]{4}")) {
			Long onlyDoctor = doctorId;
			found = appointments.findByBookingRef(ref)
				.filter(a -> onlyDoctor == null || a.getDoctorId().equals(onlyDoctor))
				.map(List::of).orElse(List.of());
		}
		else {
			found = appointments.findForDay(date == null ? LocalDate.now(clock) : date, branchId, doctorId, st).stream()
				.filter(a -> matches(a, query))
				.toList();
		}
		return rows(found.stream().sorted(LIST_ORDER).toList(), who);
	}

	/**
	 * The latest {@value #RECENT_LIMIT} bookings, newest first, or with {@code q} the latest that match
	 * it by name, mobile or booking reference on any date. Doctors get only their own.
	 */
	@Transactional(readOnly = true)
	public List<StaffAppointmentRow> recent(String q) {
		Who who = who();
		Long doctorId = null;
		if (who.role() == Role.DOCTOR) {
			if (who.doctorId() == null) {
				return List.of();
			}
			doctorId = who.doctorId();
		}
		String query = q == null ? "" : q.strip();
		Pageable page = PageRequest.of(0, RECENT_LIMIT);
		if (query.isEmpty()) {
			return rows(appointments.findRecent(doctorId, page), who);
		}
		String digits = query.replaceAll("\\D", "");
		String ref = BookingRefs.normalize(query);
		return rows(appointments.searchRecent(doctorId, contains(query.toUpperCase(Locale.ROOT)), contains(ref),
				digits.length() >= 3 ? contains(digits) : "", page), who);
	}

	/** A {@code like} pattern for "contains", with the user's {@code %}, {@code _} and {@code \} taken literally. */
	private static String contains(String s) {
		return "%" + s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
	}

	/** FR-R7: upcoming bookings hit by leave / holiday, so reception can phone the patients. */
	@Transactional(readOnly = true)
	public List<StaffAppointmentRow> needsAction(String branchCode) {
		return rows(appointments.findNeedingAction(LocalDate.now(clock), branches.idOf(branchCode)), who());
	}

	@Transactional(readOnly = true)
	public List<PatientMatch> patientMatches(Long appointmentId) {
		Appointment a = find(appointmentId);
		return patientService.candidates(a.getPatientName(), a.getPatientMobile(), a.getPatientAge());
	}

	// ---- workflow ----

	/** FR-R4: today's booking, linked to a registered patient; creates the visit. */
	@Transactional
	public StaffAppointmentRow checkIn(Long appointmentId, Long patientId) {
		Appointment a = find(appointmentId);
		requireStatus(a, Appointment.Status.BOOKED);
		LocalDate today = LocalDate.now(clock);
		if (!a.getApptDate().equals(today)) {
			throw new ConflictException("NOT_TODAY", "Only today's appointments can be checked in");
		}
		Patient p = patientService.activePatient(patientId);
		a.checkIn(p.getId(), LocalDateTime.now(clock));
		visits.save(new Visit(a.getId(), p.getId(), a.getDoctorId(), a.getBranchId(), today));
		audit.record(AuditAction.APPOINTMENT_CHECKED_IN, "APPOINTMENT", a.getId(), p.getUhid());
		return row(a.getId());
	}

	/** FR-R2: no-show, cancel or complete; any other change is a 409. */
	@Transactional
	public StaffAppointmentRow changeStatus(Long appointmentId, StatusChangeRequest req) {
		Appointment a = find(appointmentId);
		Who who = who();
		if (!who.desk() && !who.owns(a)) {
			throw new AccessDeniedException("Doctors can change only their own appointments");
		}
		String reason = req.reason() == null ? "" : req.reason().strip();
		switch (Appointment.Status.valueOf(req.status())) {
			case CANCELLED -> {
				if (reason.isEmpty()) {
					throw new BadRequestException("REASON_REQUIRED", "Please give a reason for cancelling");
				}
				booking.cancelByStaff(a.getId(), reason);
			}
			case NO_SHOW -> {
				requireStatus(a, Appointment.Status.BOOKED);
				if (a.getStartAt() != null && LocalDateTime.now(clock).isBefore(a.getStartAt())) {
					throw new ConflictException("TOO_EARLY", "A booking can be marked as no-show only after its time");
				}
				a.markNoShow();
				audit.record(AuditAction.APPOINTMENT_STATUS_CHANGED, "APPOINTMENT", a.getId(), "NO_SHOW");
			}
			case COMPLETED -> {
				if (who.role() != Role.IT_ADMIN && !who.owns(a)) {
					throw new AccessDeniedException("Only the doctor completes a visit");
				}
				requireStatus(a, Appointment.Status.CHECKED_IN);
				a.complete(LocalDateTime.now(clock));
				visits.findByAppointmentId(a.getId()).ifPresent(Visit::complete);
				audit.record(AuditAction.APPOINTMENT_STATUS_CHANGED, "APPOINTMENT", a.getId(), "COMPLETED");
			}
			default -> throw new BadRequestException("INVALID_STATUS", "Use check-in to check a patient in");
		}
		return row(a.getId());
	}

	/**
	 * FR-R5: a registered patient seen today without a booking. The token comes after the doctor's
	 * last token that day; the doctor row is locked so two walk-ins can't get the same number.
	 */
	@Transactional
	public StaffAppointmentRow walkIn(WalkInRequest req) {
		Patient p = patientService.activePatient(req.patientId());
		Long branchId = branches.idOf(req.branch());
		Doctor doctor = doctors.findWithDetailsById(req.doctorId())
			.orElseThrow(() -> new NotFoundException("Doctor", req.doctorId()));
		boolean available = doctor.isActive()
				&& doctor.getBranches().stream().anyMatch(b -> b.isActive() && b.getId().equals(branchId));
		if (!available) {
			throw new ConflictException("DOCTOR_UNAVAILABLE", "This doctor does not see patients at this hospital");
		}
		LocalDate today = LocalDate.now(clock);
		LocalDateTime now = LocalDateTime.now(clock);
		appointments.lockDoctor(doctor.getId());
		int lastToken = Math.max(slots.findMaxToken(doctor.getId(), branchId, today).orElse(0),
				appointments.findMaxToken(doctor.getId(), branchId, today).orElse(0));
		String reason = req.reason() == null || req.reason().isBlank() ? null : req.reason().strip();
		PatientDetails details = new PatientDetails(p.getFullName(), p.ageOn(today), p.getGender(), p.getMobile(), reason);
		Appointment a = appointments.saveAndFlush(Appointment.walkIn(booking.newBookingRef(today), doctor.getId(), branchId,
				today, lastToken + 1, p.getId(), details, now));
		visits.save(new Visit(a.getId(), p.getId(), doctor.getId(), branchId, today));
		audit.record(AuditAction.WALK_IN_CREATED, "APPOINTMENT", a.getId(), p.getUhid() + " token " + a.getTokenNo());
		return row(a.getId());
	}

	/** FR-R6 (reception and IT Admin only; checked by the controller). */
	@Transactional
	public StaffAppointmentRow reschedule(Long appointmentId, Long newSlotId, String reason) {
		booking.rescheduleByStaff(appointmentId, newSlotId, reason);
		return row(appointmentId);
	}

	/** End-of-day job (DESIGN.md §10.4): bookings up to {@code lastDate} that nobody checked in. */
	@Transactional
	public int markNoShows(LocalDate lastDate) {
		int count = appointments.markNoShowsUpTo(lastDate);
		if (count > 0) {
			audit.record(AuditAction.APPOINTMENT_STATUS_CHANGED, "APPOINTMENT", null,
					"auto NO_SHOW: " + count + " bookings up to " + lastDate);
		}
		return count;
	}

	// ---- helpers ----

	private static final Comparator<Appointment> LIST_ORDER = Comparator
		.comparing((Appointment a) -> a.getStartAt() != null ? a.getStartAt() : a.getCheckedInAt(),
				Comparator.nullsLast(Comparator.naturalOrder()))
		.thenComparing(Appointment::getTokenNo, Comparator.nullsLast(Comparator.naturalOrder()));

	private static boolean matches(Appointment a, String q) {
		if (q.isEmpty()) {
			return true;
		}
		String upper = q.toUpperCase(Locale.ROOT);
		String digits = q.replaceAll("\\D", "");
		return a.getPatientName().toUpperCase(Locale.ROOT).contains(upper)
				|| a.getBookingRef().contains(upper)
				|| (digits.length() >= 3 && a.getPatientMobile().contains(digits));
	}

	private StaffAppointmentRow row(Long id) {
		// Re-read: the slot updates in BookingService clear the persistence context.
		return rows(List.of(find(id)), who()).getFirst();
	}

	private List<StaffAppointmentRow> rows(List<Appointment> list, Who who) {
		if (list.isEmpty()) {
			return List.of();
		}
		LocalDate today = LocalDate.now(clock);
		LocalDateTime now = LocalDateTime.now(clock);
		Map<Long, Doctor> doctorMap = doctors.findAllByOrderByFullNameAsc().stream()
			.collect(Collectors.toMap(Doctor::getId, Function.identity()));
		Map<Long, Branch> branchMap = branches.byId();
		Set<Long> patientIds = list.stream().map(Appointment::getPatientId).filter(id -> id != null).collect(Collectors.toSet());
		Map<Long, String> uhids = patients.findAllById(patientIds).stream()
			.collect(Collectors.toMap(Patient::getId, Patient::getUhid));
		Set<String> frequent = frequentNoShows(list.stream().map(Appointment::getPatientMobile).distinct().toList(), today);
		Map<Long, Long> visitIds = visits.findByAppointmentIdIn(list.stream().map(Appointment::getId).toList()).stream()
			.collect(Collectors.toMap(Visit::getAppointmentId, Visit::getId));

		List<StaffAppointmentRow> rows = new ArrayList<>(list.size());
		for (Appointment a : list) {
			Doctor d = doctorMap.get(a.getDoctorId());
			Branch b = branchMap.get(a.getBranchId());
			rows.add(new StaffAppointmentRow(a.getId(), a.getBookingRef(), a.getStatus(), a.getTokenNo(), a.getApptDate(),
					a.getStartAt(), a.getDoctorId(), d == null ? null : d.getFullName(),
					d == null ? null : d.getSpeciality().getName(), b == null ? null : b.getCode(),
					b == null ? null : b.getCity(), a.getPatientId(), uhids.get(a.getPatientId()), a.getPatientName(),
					a.getPatientAge(), a.getPatientGender(), a.getPatientMobile(), frequent.contains(a.getPatientMobile()),
					a.getReason(), a.getChannel(), a.isNeedsAction(), a.getCancelReason(), a.getCancelledBy(),
					a.getRescheduleCount(), a.getCheckedInAt(), visitIds.get(a.getId()), actions(a, who, today, now)));
		}
		return rows;
	}

	private Set<String> frequentNoShows(List<String> mobiles, LocalDate today) {
		Map<String, Long> counts = new HashMap<>();
		for (int i = 0; i < mobiles.size(); i += IN_CHUNK) {
			for (Object[] r : appointments.countNoShows(mobiles.subList(i, Math.min(i + IN_CHUNK, mobiles.size())),
					today.minusDays(NO_SHOW_DAYS))) {
				counts.put((String) r[0], (Long) r[1]);
			}
		}
		return counts.entrySet().stream().filter(e -> e.getValue() >= FREQUENT_NO_SHOWS).map(Map.Entry::getKey)
			.collect(Collectors.toSet());
	}

	private static Actions actions(Appointment a, Who who, LocalDate today, LocalDateTime now) {
		boolean booked = a.getStatus() == Appointment.Status.BOOKED;
		boolean mine = who.desk() || who.owns(a);
		return new Actions(
				who.desk() && booked && a.getApptDate().equals(today),
				mine && booked,
				mine && booked && (a.getStartAt() == null || !now.isBefore(a.getStartAt())),
				(who.role() == Role.IT_ADMIN || who.owns(a)) && a.getStatus() == Appointment.Status.CHECKED_IN,
				who.desk() && booked);
	}

	private Appointment find(Long id) {
		return appointments.findById(id).orElseThrow(() -> new NotFoundException("Appointment", id));
	}

	private static void requireStatus(Appointment a, Appointment.Status expected) {
		if (a.getStatus() != expected) {
			throw new ConflictException("INVALID_TRANSITION", "This appointment is "
					+ a.getStatus().name().toLowerCase(Locale.ROOT).replace('_', ' ') + " and can't be changed that way");
		}
	}

	private static Appointment.Status parseStatus(String status) {
		if (status == null || status.isBlank()) {
			return null;
		}
		try {
			return Appointment.Status.valueOf(status.strip().toUpperCase(Locale.ROOT));
		}
		catch (IllegalArgumentException e) {
			throw new BadRequestException("INVALID_STATUS", "Unknown status: " + status);
		}
	}

	private static Who who() {
		return new Who(CurrentUser.role().orElseThrow(() -> new AccessDeniedException("Not logged in")),
				CurrentUser.doctorId().orElse(null));
	}

	/** The logged-in user; {@code desk} = reception or IT Admin. */
	private record Who(Role role, Long doctorId) {

		boolean desk() {
			return role == Role.RECEPTIONIST || role == Role.IT_ADMIN;
		}

		boolean owns(Appointment a) {
			return role == Role.DOCTOR && doctorId != null && doctorId.equals(a.getDoctorId());
		}
	}

}
