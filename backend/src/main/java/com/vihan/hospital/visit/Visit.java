package com.vihan.hospital.visit;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.vihan.hospital.common.entity.AuditableEntity;

/**
 * One consultation (DESIGN.md §9.3). Opened at check-in or for a walk-in (M6); the treating
 * doctor records vitals, diagnosis, notes and the prescription (M8). The clinical fields are read
 * only through the doctor-only {@code /api/clinical} API (§12.3); reception sees date, branch,
 * doctor, type and status.
 */
@Entity
@Table(name = "visit")
public class Visit extends AuditableEntity {

	public enum Type { OP, FOLLOW_UP, EMERGENCY }

	public enum Status { OPEN, COMPLETED }

	/** What the doctor records (FR-C2); every field may be empty. */
	public record ClinicalNote(Integer bpSystolic, Integer bpDiastolic, Integer pulse, BigDecimal temperatureF,
			Integer spo2, BigDecimal weightKg, BigDecimal heightCm, String chiefComplaint, String diagnosis,
			String clinicalNotes, String advice, LocalDate followUpDate) {
	}

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "visit_id")
	private Long id;

	@Column(name = "appointment_id")
	private Long appointmentId;

	@Column(name = "patient_id", nullable = false)
	private Long patientId;

	@Column(name = "doctor_id", nullable = false)
	private Long doctorId;

	@Column(name = "branch_id", nullable = false)
	private Long branchId;

	@Column(name = "visit_date", nullable = false)
	private LocalDate visitDate;

	@Enumerated(EnumType.STRING)
	@Column(name = "visit_type", nullable = false, length = 12)
	private Type visitType = Type.OP;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 12)
	private Status status = Status.OPEN;

	@Column(name = "bp_systolic")
	private Integer bpSystolic;

	@Column(name = "bp_diastolic")
	private Integer bpDiastolic;

	private Integer pulse;

	@Column(name = "temperature_f", precision = 4, scale = 1)
	private BigDecimal temperatureF;

	private Integer spo2;

	@Column(name = "weight_kg", precision = 5, scale = 2)
	private BigDecimal weightKg;

	@Column(name = "height_cm", precision = 5, scale = 1)
	private BigDecimal heightCm;

	@Column(name = "chief_complaint", length = 1000)
	private String chiefComplaint;

	@Column(length = 1000)
	private String diagnosis;

	@JdbcTypeCode(SqlTypes.LONG32VARCHAR)   // PostgreSQL TEXT (a @Lob String would map to an OID large object)
	@Column(name = "clinical_notes")
	private String clinicalNotes;

	@Column(length = 2000)
	private String advice;

	@Column(name = "follow_up_date")
	private LocalDate followUpDate;

	protected Visit() {
	}

	public Visit(Long appointmentId, Long patientId, Long doctorId, Long branchId, LocalDate visitDate) {
		this.appointmentId = appointmentId;
		this.patientId = patientId;
		this.doctorId = doctorId;
		this.branchId = branchId;
		this.visitDate = visitDate;
	}

	public void record(ClinicalNote n) {
		requireOpen();
		this.bpSystolic = n.bpSystolic();
		this.bpDiastolic = n.bpDiastolic();
		this.pulse = n.pulse();
		this.temperatureF = n.temperatureF();
		this.spo2 = n.spo2();
		this.weightKg = n.weightKg();
		this.heightCm = n.heightCm();
		this.chiefComplaint = n.chiefComplaint();
		this.diagnosis = n.diagnosis();
		this.clinicalNotes = n.clinicalNotes();
		this.advice = n.advice();
		this.followUpDate = n.followUpDate();
	}

	public ClinicalNote note() {
		return new ClinicalNote(bpSystolic, bpDiastolic, pulse, temperatureF, spo2, weightKg, heightCm, chiefComplaint,
				diagnosis, clinicalNotes, advice, followUpDate);
	}

	public void complete() {
		this.status = Status.COMPLETED;
	}

	private void requireOpen() {
		if (status != Status.OPEN) {
			throw new IllegalStateException("Visit " + id + " is " + status);
		}
	}

	public Long getId() {
		return id;
	}

	public Long getAppointmentId() {
		return appointmentId;
	}

	public Long getPatientId() {
		return patientId;
	}

	public Long getDoctorId() {
		return doctorId;
	}

	public Long getBranchId() {
		return branchId;
	}

	public LocalDate getVisitDate() {
		return visitDate;
	}

	public Type getVisitType() {
		return visitType;
	}

	public Status getStatus() {
		return status;
	}

}
