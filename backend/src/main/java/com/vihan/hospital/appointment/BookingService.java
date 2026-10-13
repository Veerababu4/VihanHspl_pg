package com.vihan.hospital.appointment;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.vihan.hospital.appointment.Appointment.CancelledBy;
import com.vihan.hospital.appointment.Appointment.Channel;
import com.vihan.hospital.appointment.AppointmentDtos.AppointmentView;
import com.vihan.hospital.appointment.AppointmentDtos.BookingRequest;
import com.vihan.hospital.appointment.AppointmentDtos.BookingSummary;
import com.vihan.hospital.appointment.AppointmentDtos.BranchInfo;
import com.vihan.hospital.appointment.AppointmentDtos.DoctorInfo;
import com.vihan.hospital.appointment.AppointmentDtos.StaffBookingRequest;
import com.vihan.hospital.audit.AuditAction;
import com.vihan.hospital.audit.AuditService;
import com.vihan.hospital.branch.Branch;
import com.vihan.hospital.branch.BranchRepository;
import com.vihan.hospital.common.error.ApiException;
import com.vihan.hospital.common.error.ApiException.BadRequestException;
import com.vihan.hospital.common.error.ApiException.ConflictException;
import com.vihan.hospital.common.error.ApiException.NotFoundException;
import com.vihan.hospital.common.util.Masking;
import com.vihan.hospital.common.util.Mobiles;
import com.vihan.hospital.config.AppProperties;
import com.vihan.hospital.doctor.Doctor;
import com.vihan.hospital.doctor.DoctorRepository;
import com.vihan.hospital.notification.NotificationService.Event;
import com.vihan.hospital.notification.NotificationService.Message;
import com.vihan.hospital.notification.Notifications;
import com.vihan.hospital.patient.Patient;
import com.vihan.hospital.patient.PatientService;
import com.vihan.hospital.schedule.Slot;
import com.vihan.hospital.schedule.SlotReconciler;
import com.vihan.hospital.schedule.SlotRepository;

/**
 * Booking, lookup, cancel and reschedule (DESIGN.md §5.2, §10.3, §19.3).
 *
 * <p>A slot is taken with one conditional UPDATE ({@link SlotRepository#bookIfOpen}); the unique
 * index {@code ux_appt_active_slot} is the second safety net. Note: the slot updates clear the JPA
 * persistence context, so an appointment changed <em>after</em> one of them is re-read first.
 */
@Service
public class BookingService {

	private static final String NOT_FOUND_MESSAGE = "No booking found with that reference and mobile number";
	private static final int REF_ATTEMPTS = 10;

	private final AppointmentRepository appointments;
	private final SlotRepository slots;
	private final DoctorRepository doctors;
	private final BranchRepository branches;
	private final SlotReconciler reconciler;
	private final AuditService audit;
	private final Notifications notifications;
	private final Clock clock;
	private final AppProperties.Booking rules;
	private final PatientService patients;

	public BookingService(AppointmentRepository appointments, SlotRepository slots, DoctorRepository doctors,
			BranchRepository branches, SlotReconciler reconciler, AuditService audit, Notifications notifications,
			Clock clock, AppProperties props, PatientService patients) {
		this.appointments = appointments;
		this.slots = slots;
		this.doctors = doctors;
		this.branches = branches;
		this.reconciler = reconciler;
		this.audit = audit;
		this.notifications = notifications;
		this.clock = clock;
		this.rules = props.booking();
		this.patients = patients;
	}

	// ---- booking ----

	/**
	 * Online booking (FR-B1, FR-B4). The captcha and IP rate limit are checked before this is called.
	 * A UHID whose patient has a different mobile is ignored without saying so, so the form can't be
	 * used to find out whose number a UHID is (DESIGN.md §19.5).
	 */
	@Transactional
	public AppointmentView bookOnline(BookingRequest req, String clientIp) {
		PatientDetails patient = patient(req.patientName(), req.patientAge(), req.patientGender(), req.mobile(), req.reason());
		Long patientId = linkedPatient(req.uhid(), patient.mobile()).orElse(null);
		Slot slot = bookableSlot(req.slotId(), true);
		checkMobileLimit(patient.mobile(), slot.getSlotDate(), null);
		return book(slot, patient, Channel.ONLINE, clientIp, patientId);
	}

	/**
	 * Counter / phone booking by reception (FR-R3): no booking window or per-mobile limit. Reception
	 * may book for a registered patient by UHID; a UHID with another mobile is an error here.
	 */
	@Transactional
	public AppointmentView bookByStaff(StaffBookingRequest req) {
		PatientDetails patient = patient(req.patientName(), req.patientAge(), req.patientGender(), req.mobile(), req.reason());
		Long patientId = null;
		if (req.uhid() != null && !req.uhid().isBlank()) {
			patientId = linkedPatient(req.uhid(), patient.mobile()).orElseThrow(() -> new BadRequestException("UHID_MISMATCH",
					"No registered patient with this UHID and mobile number. Check both, or leave the UHID empty."));
		}
		Slot slot = bookableSlot(req.slotId(), false);
		return book(slot, patient, Channel.valueOf(req.channel()), null, patientId);
	}

	private Optional<Long> linkedPatient(String uhid, String mobile) {
		if (uhid == null || uhid.isBlank()) {
			return Optional.empty();
		}
		return patients.byUhid(uhid).filter(p -> p.getMobile().equals(mobile)).map(Patient::getId);
	}

	private AppointmentView book(Slot slot, PatientDetails patient, Channel channel, String clientIp, Long patientId) {
		if (slots.bookIfOpen(slot.getId()) == 0) {
			throw slotTaken();
		}
		LocalDateTime now = LocalDateTime.now(clock);
		Appointment booked = Appointment.book(newBookingRef(now.toLocalDate()), slot, patient, channel, clientIp, now);
		if (patientId != null) {
			booked.linkPatient(patientId);
		}
		Appointment a = appointments.saveAndFlush(booked);
		audit.record(AuditAction.APPOINTMENT_BOOKED, "APPOINTMENT", a.getId(), "channel=" + channel);
		AppointmentView view = view(a, slot);
		notifications.afterCommit(message(Event.BOOKED, a, view));
		return view;
	}

	// ---- patient self-service (FR-B3, FR-B3a) ----

	@Transactional(readOnly = true)
	public AppointmentView lookup(String bookingRef, String mobile) {
		Appointment a = find(bookingRef, mobile);
		return view(a, a.getSlotId() == null ? null : slots.findById(a.getSlotId()).orElse(null));
	}

	/**
	 * "Check my booking" with one field only: by reference (that booking, any date) or by mobile
	 * (today and later). Limited, masked results; see {@link BookingSummary}. No result is an empty list.
	 */
	@Transactional(readOnly = true)
	public List<BookingSummary> findByOne(String bookingRef, String mobile) {
		if (bookingRef != null && !bookingRef.isBlank()) {
			return appointments.findByBookingRef(BookingRefs.normalize(bookingRef))
				.map(a -> summary(a, false))
				.stream().toList();
		}
		String normalized = Mobiles.normalize(mobile);
		if (normalized == null) {
			return List.of();
		}
		return appointments
			.findTop10ByPatientMobileAndApptDateGreaterThanEqualOrderByApptDateAscStartAtAsc(normalized, LocalDate.now(clock))
			.stream().map(a -> summary(a, true)).toList();
	}

	@Transactional
	public AppointmentView cancelByPatient(String bookingRef, String mobile, String reason) {
		Appointment a = find(bookingRef, mobile);
		requireChangeableOnline(a);
		a.cancel(blankToNull(reason), CancelledBy.PATIENT);
		Slot slot = a.getSlotId() == null ? null : slots.findById(a.getSlotId()).orElse(null);
		releaseSlot(a.getSlotId(), a.getDoctorId(), a.getApptDate());
		audit.record(AuditAction.APPOINTMENT_CANCELLED, "APPOINTMENT", a.getId(), "by patient");
		AppointmentView view = view(a, slot);
		notifications.afterCommit(message(Event.CANCELLED, a, view));
		return view;
	}

	/** Same doctor, any of the doctor's branches; same booking reference, new token (FR-B3a). */
	@Transactional
	public AppointmentView rescheduleByPatient(String bookingRef, String mobile, Long newSlotId) {
		Appointment a = find(bookingRef, mobile);
		requireChangeableOnline(a);
		if (a.getRescheduleCount() >= rules.maxReschedules()) {
			throw new ConflictException("RESCHEDULE_LIMIT", "This booking has already been moved " + rules.maxReschedules()
					+ " times. Please cancel it and book again.");
		}
		if (newSlotId.equals(a.getSlotId())) {
			throw new BadRequestException("SAME_SLOT", "Please choose a different time");
		}
		Slot newSlot = bookableSlot(newSlotId, true);
		if (!newSlot.getDoctorId().equals(a.getDoctorId())) {
			throw new BadRequestException("DIFFERENT_DOCTOR", "A booking can only be moved to another time with the same doctor");
		}
		checkMobileLimit(a.getPatientMobile(), newSlot.getSlotDate(), a.getId());

		Long oldSlotId = a.getSlotId();
		LocalDate oldDate = a.getApptDate();
		LocalDateTime oldStart = a.getStartAt();
		if (slots.bookIfOpen(newSlot.getId()) == 0) {
			throw slotTaken();   // nothing has changed yet: the transaction rolls back cleanly
		}
		Appointment moved = appointments.findById(a.getId()).orElseThrow();   // re-read: see class comment
		moved.moveTo(newSlot);
		releaseSlot(oldSlotId, moved.getDoctorId(), oldDate);
		audit.record(AuditAction.APPOINTMENT_RESCHEDULED, "APPOINTMENT", moved.getId(),
				"from " + oldStart + " to " + newSlot.getStartAt());
		AppointmentView view = view(moved, newSlot);
		notifications.afterCommit(message(Event.RESCHEDULED, moved, view));
		return view;
	}

	// ---- reception (FR-R2, FR-R6) ----

	/** Staff cancel: any time before check-in, with a reason. */
	@Transactional
	public void cancelByStaff(Long appointmentId, String reason) {
		Appointment a = bookedAppointment(appointmentId);
		a.cancel(reason.strip(), CancelledBy.STAFF);
		Slot slot = a.getSlotId() == null ? null : slots.findById(a.getSlotId()).orElse(null);
		releaseSlot(a.getSlotId(), a.getDoctorId(), a.getApptDate());
		audit.record(AuditAction.APPOINTMENT_CANCELLED, "APPOINTMENT", a.getId(), "by staff: " + reason.strip());
		notifications.afterCommit(message(Event.CANCELLED, a, view(a, slot)));
	}

	/** Staff reschedule: any open future slot of any doctor; no cutoff and no count limit. */
	@Transactional
	public void rescheduleByStaff(Long appointmentId, Long newSlotId, String reason) {
		Appointment a = bookedAppointment(appointmentId);
		if (newSlotId.equals(a.getSlotId())) {
			throw new BadRequestException("SAME_SLOT", "Please choose a different time");
		}
		Slot newSlot = bookableSlot(newSlotId, false);
		Long oldSlotId = a.getSlotId();
		Long oldDoctorId = a.getDoctorId();
		LocalDate oldDate = a.getApptDate();
		LocalDateTime oldStart = a.getStartAt();
		if (slots.bookIfOpen(newSlot.getId()) == 0) {
			throw slotTaken();
		}
		Appointment moved = appointments.findById(a.getId()).orElseThrow();   // re-read: see class comment
		moved.moveByStaff(newSlot);
		releaseSlot(oldSlotId, oldDoctorId, oldDate);
		String doctorChange = oldDoctorId.equals(newSlot.getDoctorId()) ? "" : " (doctor " + oldDoctorId + " -> " + newSlot.getDoctorId() + ")";
		audit.record(AuditAction.APPOINTMENT_RESCHEDULED, "APPOINTMENT", moved.getId(),
				"by staff from " + oldStart + " to " + newSlot.getStartAt() + doctorChange + ": " + reason.strip());
		notifications.afterCommit(message(Event.RESCHEDULED, moved, view(moved, newSlot)));
	}

	private Appointment bookedAppointment(Long id) {
		Appointment a = appointments.findById(id).orElseThrow(() -> new NotFoundException("Appointment", id));
		if (a.getStatus() != Appointment.Status.BOOKED) {
			throw new ConflictException("INVALID_TRANSITION",
					"This booking is " + a.getStatus().name().toLowerCase(Locale.ROOT).replace('_', ' ') + " and can't be changed");
		}
		return a;
	}

	// ---- rules ----

	private Slot bookableSlot(Long slotId, boolean onlineRules) {
		Slot slot = slots.findById(slotId).orElseThrow(() -> new NotFoundException("Slot", slotId));
		if (slot.getStatus() != Slot.Status.OPEN) {
			throw slotTaken();
		}
		LocalDateTime now = LocalDateTime.now(clock);
		LocalDateTime earliest = onlineRules ? now.plusMinutes(rules.minLeadMinutes()) : now;
		if (!slot.getStartAt().isAfter(earliest)) {
			throw new ConflictException("SLOT_TOO_SOON", onlineRules
					? "This time is too soon to book online. Please choose a later time or call the hospital."
					: "This time has already passed");
		}
		if (onlineRules && slot.getSlotDate().isAfter(reconciler.windowEnd())) {
			throw new BadRequestException("OUT_OF_WINDOW", "Online booking is open only for the next two weeks");
		}
		Doctor doctor = doctors.findWithDetailsById(slot.getDoctorId()).orElseThrow();
		boolean available = doctor.isActive() && doctor.getBranches().stream()
			.anyMatch(b -> b.isActive() && b.getId().equals(slot.getBranchId()));
		if (!available) {
			throw new ConflictException("SLOT_UNAVAILABLE", "This doctor is no longer available at this hospital");
		}
		return slot;
	}

	/** FR-B4: at most N active online bookings per mobile number per day. */
	private void checkMobileLimit(String mobile, LocalDate date, Long excludeAppointmentId) {
		if (appointments.countActiveForMobileOnDate(mobile, date, excludeAppointmentId) >= rules.maxPerMobilePerDay()) {
			throw new ConflictException("TOO_MANY_BOOKINGS", "This mobile number already has " + rules.maxPerMobilePerDay()
					+ " bookings on that day. Please call the hospital to book more.");
		}
	}

	private void requireChangeableOnline(Appointment a) {
		if (a.getStatus() != Appointment.Status.BOOKED) {
			throw new ConflictException("NOT_CHANGEABLE",
					"This booking is " + a.getStatus().name().toLowerCase(Locale.ROOT).replace('_', ' ') + " and can't be changed");
		}
		if (!LocalDateTime.now(clock).isBefore(deadline(a))) {
			throw new ConflictException("TOO_LATE", "Online changes close " + rules.cancelCutoffMinutes()
					+ " minutes before the appointment. Please call the hospital.");
		}
	}

	/** Frees the slot, then lets the reconciler re-block it if that day is now leave / holiday. */
	private void releaseSlot(Long slotId, Long doctorId, LocalDate date) {
		if (slotId != null) {
			slots.releaseIfBooked(slotId);
		}
		reconciler.reconcileAffected(doctorId, date, date);
	}

	private Appointment find(String bookingRef, String mobile) {
		String normalizedMobile = Mobiles.normalize(mobile);
		return appointments.findByBookingRef(BookingRefs.normalize(bookingRef))
			.filter(a -> a.getPatientMobile().equals(normalizedMobile))
			// Same answer for "no such booking" and "wrong mobile": no hints for guessers.
			.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "BOOKING_NOT_FOUND", NOT_FOUND_MESSAGE));
	}

	/** Also used for walk-ins (ReceptionService). */
	String newBookingRef(LocalDate day) {
		for (int i = 0; i < REF_ATTEMPTS; i++) {
			String ref = BookingRefs.generate(day);
			if (!appointments.existsByBookingRef(ref)) {
				return ref;
			}
		}
		throw new IllegalStateException("Could not find a free booking reference");
	}

	private LocalDateTime deadline(Appointment a) {
		return a.getStartAt().minusMinutes(rules.cancelCutoffMinutes());
	}

	private static PatientDetails patient(String name, Integer age, String gender, String mobile, String reason) {
		return new PatientDetails(name.strip().replaceAll("\\s+", " "), age, gender, Mobiles.normalize(mobile), blankToNull(reason));
	}

	private static ConflictException slotTaken() {
		return new ConflictException("SLOT_TAKEN", "Sorry, this time was just booked by someone else. Please choose another time.");
	}

	// ---- views ----

	private AppointmentView view(Appointment a, Slot slot) {
		Doctor d = doctors.findWithDetailsById(a.getDoctorId()).orElseThrow();
		Branch b = branches.findById(a.getBranchId()).orElseThrow();
		LocalDateTime deadline = a.getStartAt() == null ? null : deadline(a);   // walk-ins have no time
		boolean canChange = a.getStatus() == Appointment.Status.BOOKED && deadline != null
				&& LocalDateTime.now(clock).isBefore(deadline);
		int left = Math.max(0, rules.maxReschedules() - a.getRescheduleCount());
		return new AppointmentView(a.getBookingRef(), a.getStatus(), a.getTokenNo(), a.getApptDate(), a.getStartAt(),
				slot == null ? null : slot.getEndAt(),
				new DoctorInfo(d.getId(), d.getFullName(), d.getSpeciality().getName(), d.getConsultationFee()),
				new BranchInfo(b.getCode(), b.getName(), b.getCity(), b.getAddress(), b.getPhone()),
				a.getPatientName(), a.getPatientAge(), a.getPatientGender(), Masking.mobile(a.getPatientMobile()),
				a.getReason(), a.getChannel(), a.getRescheduleCount(), left, canChange, canChange && left > 0, deadline,
				a.isNeedsAction(), a.getCancelReason(), a.getPatientId() == null ? null : patients.uhidOf(a.getPatientId()));
	}

	/** @param byMobile found by mobile → hide the reference; found by reference → only the masked mobile */
	private BookingSummary summary(Appointment a, boolean byMobile) {
		Branch b = branches.findById(a.getBranchId()).orElseThrow();
		return new BookingSummary(byMobile ? Masking.bookingRef(a.getBookingRef()) : a.getBookingRef(),
				Masking.mobile(a.getPatientMobile()), Masking.name(a.getPatientName()), a.getApptDate(), a.getStartAt(),
				a.getTokenNo(), b.getName(), b.getCity(), a.getStatus(), byMobile ? "BOOKING_REF" : "MOBILE");
	}

	private static Message message(Event event, Appointment a, AppointmentView v) {
		return new Message(event, a.getPatientMobile(), a.getPatientName(), a.getBookingRef(), a.getStartAt(),
				a.getTokenNo(), v.doctor().fullName(), v.branch().city());
	}

	private static String blankToNull(String s) {
		return s == null || s.isBlank() ? null : s.strip();
	}

}
