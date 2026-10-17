package com.vihan.hospital.appointment;

import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import com.vihan.hospital.common.entity.AuditableEntity;
import com.vihan.hospital.schedule.Slot;

/**
 * A booking (DESIGN.md §9.3, §10.4). Holds the patient details as typed by the booker; the link to
 * a registered patient ({@code patientId}) is made at check-in (M6). Walk-ins have no slot.
 */
@Entity
@Table(name = "appointment")
public class Appointment extends AuditableEntity {

	public enum Status { BOOKED, CHECKED_IN, COMPLETED, CANCELLED, NO_SHOW }

	public enum Channel { ONLINE, COUNTER, PHONE, WALK_IN }

	public enum CancelledBy { PATIENT, STAFF }

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "appointment_id")
	private Long id;

	@Column(name = "booking_ref", nullable = false, length = 20)
	private String bookingRef;

	/** null for walk-ins (M6). */
	@Column(name = "slot_id")
	private Long slotId;

	@Column(name = "doctor_id", nullable = false)
	private Long doctorId;

	@Column(name = "branch_id", nullable = false)
	private Long branchId;

	@Column(name = "appt_date", nullable = false)
	private LocalDate apptDate;

	@Column(name = "start_at")
	private LocalDateTime startAt;

	@Column(name = "token_no")
	private Integer tokenNo;

	@Column(name = "patient_id")
	private Long patientId;

	@Column(name = "patient_name", nullable = false, length = 120)
	private String patientName;

	@Column(name = "patient_mobile", nullable = false, length = 10)
	private String patientMobile;

	@Column(name = "patient_age")
	private Integer patientAge;

	@Column(name = "patient_gender", length = 1)
	private String patientGender;

	@Column(length = 500)
	private String reason;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10)
	private Channel channel;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 12)
	private Status status = Status.BOOKED;

	@Column(name = "cancel_reason", length = 300)
	private String cancelReason;

	@Enumerated(EnumType.STRING)
	@Column(name = "cancelled_by", length = 10)
	private CancelledBy cancelledBy;

	@Column(name = "reschedule_count", nullable = false)
	private int rescheduleCount;

	/** Doctor leave / holiday / removed session hit this booking: reception must call (FR-R7). */
	@Column(name = "needs_action", nullable = false)
	private boolean needsAction;

	@Column(name = "booked_ip", length = 45)
	private String bookedIp;

	@Column(name = "consent_at", nullable = false)
	private LocalDateTime consentAt;

	@Column(name = "checked_in_at")
	private LocalDateTime checkedInAt;

	@Column(name = "completed_at")
	private LocalDateTime completedAt;

	/** Given on the confirmation page, for the confirmation email (and later booking emails). */
	@Column(name = "patient_email", length = 120)
	private String patientEmail;

	/** Confirmation emails asked for so far; capped per booking. */
	@Column(name = "email_sent_count", nullable = false)
	private int emailSentCount;

	@Version
	@Column(nullable = false)
	private long version;

	protected Appointment() {
	}

	/** A new booking on an open slot (the caller has already marked the slot BOOKED). */
	public static Appointment book(String bookingRef, Slot slot, PatientDetails patient, Channel channel, String bookedIp,
			LocalDateTime consentAt) {
		Appointment a = new Appointment();
		a.bookingRef = bookingRef;
		a.placeOn(slot);
		a.patientName = patient.name();
		a.patientMobile = patient.mobile();
		a.patientAge = patient.age();
		a.patientGender = patient.gender();
		a.reason = patient.reason();
		a.channel = channel;
		a.bookedIp = bookedIp;
		a.consentAt = consentAt;
		return a;
	}

	/**
	 * A walk-in without a slot (FR-R5): already checked in, token after the doctor's last one that day.
	 * The patient is registered first (DESIGN.md §19.5), so the details come from the patient record.
	 */
	public static Appointment walkIn(String bookingRef, Long doctorId, Long branchId, LocalDate date, int tokenNo,
			Long patientId, PatientDetails patient, LocalDateTime now) {
		Appointment a = new Appointment();
		a.bookingRef = bookingRef;
		a.doctorId = doctorId;
		a.branchId = branchId;
		a.apptDate = date;
		a.tokenNo = tokenNo;
		a.patientId = patientId;
		a.patientName = patient.name();
		a.patientMobile = patient.mobile();
		a.patientAge = patient.age();
		a.patientGender = patient.gender();
		a.reason = patient.reason();
		a.channel = Channel.WALK_IN;
		a.status = Status.CHECKED_IN;
		a.consentAt = now;
		a.checkedInAt = now;
		return a;
	}

	public void cancel(String reason, CancelledBy by) {
		requireStatus(Status.BOOKED);
		this.status = Status.CANCELLED;
		this.cancelReason = reason;
		this.cancelledBy = by;
		this.needsAction = false;
	}

	/** Patient reschedule: same booking reference, new slot and token; counts toward the online limit. */
	public void moveTo(Slot slot) {
		moveByStaff(slot);
		this.rescheduleCount++;
	}

	/** Staff reschedule (FR-R6): any doctor; does not use up the patient's own reschedules. */
	public void moveByStaff(Slot slot) {
		requireStatus(Status.BOOKED);
		placeOn(slot);
		this.needsAction = false;
	}

	/** Booked with a UHID whose mobile matched (DESIGN.md §19.5); check-in may still pick someone else. */
	public void linkPatient(Long patientId) {
		requireStatus(Status.BOOKED);
		this.patientId = patientId;
	}

	/** Check-in links the booking to a registered patient (DESIGN.md §10.4, FR-R4). */
	public void checkIn(Long patientId, LocalDateTime now) {
		requireStatus(Status.BOOKED);
		this.patientId = patientId;
		this.status = Status.CHECKED_IN;
		this.checkedInAt = now;
		this.needsAction = false;
	}

	public void markNoShow() {
		requireStatus(Status.BOOKED);
		this.status = Status.NO_SHOW;
		this.needsAction = false;
	}

	/** The patient asked for the confirmation by email (counted, so the caller can cap it). */
	public void recordConfirmationEmail(String email) {
		requireStatus(Status.BOOKED);
		this.patientEmail = email;
		this.emailSentCount++;
	}

	public void complete(LocalDateTime now) {
		requireStatus(Status.CHECKED_IN);
		this.status = Status.COMPLETED;
		this.completedAt = now;
	}

	private void placeOn(Slot slot) {
		this.slotId = slot.getId();
		this.doctorId = slot.getDoctorId();
		this.branchId = slot.getBranchId();
		this.apptDate = slot.getSlotDate();
		this.startAt = slot.getStartAt();
		this.tokenNo = slot.getTokenNo();
	}

	private void requireStatus(Status expected) {
		if (status != expected) {
			throw new IllegalStateException("Appointment " + bookingRef + " is " + status + ", expected " + expected);
		}
	}

	public Long getId() {
		return id;
	}

	public String getBookingRef() {
		return bookingRef;
	}

	public Long getSlotId() {
		return slotId;
	}

	public Long getDoctorId() {
		return doctorId;
	}

	public Long getBranchId() {
		return branchId;
	}

	public LocalDate getApptDate() {
		return apptDate;
	}

	public LocalDateTime getStartAt() {
		return startAt;
	}

	public Integer getTokenNo() {
		return tokenNo;
	}

	public Long getPatientId() {
		return patientId;
	}

	public String getPatientName() {
		return patientName;
	}

	public String getPatientMobile() {
		return patientMobile;
	}

	public Integer getPatientAge() {
		return patientAge;
	}

	public String getPatientGender() {
		return patientGender;
	}

	public String getReason() {
		return reason;
	}

	public Channel getChannel() {
		return channel;
	}

	public Status getStatus() {
		return status;
	}

	public String getCancelReason() {
		return cancelReason;
	}

	public CancelledBy getCancelledBy() {
		return cancelledBy;
	}

	public int getRescheduleCount() {
		return rescheduleCount;
	}

	public boolean isNeedsAction() {
		return needsAction;
	}

	public LocalDateTime getConsentAt() {
		return consentAt;
	}

	public LocalDateTime getCheckedInAt() {
		return checkedInAt;
	}

	public String getPatientEmail() {
		return patientEmail;
	}

	public int getEmailSentCount() {
		return emailSentCount;
	}

}
