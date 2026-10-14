package com.vihan.hospital.appointment;

import java.time.LocalDate;
import java.util.List;

import jakarta.validation.Valid;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.vihan.hospital.appointment.ReceptionDtos.CheckInRequest;
import com.vihan.hospital.appointment.ReceptionDtos.StaffAppointmentRow;
import com.vihan.hospital.appointment.ReceptionDtos.StaffRescheduleRequest;
import com.vihan.hospital.appointment.ReceptionDtos.StatusChangeRequest;
import com.vihan.hospital.appointment.ReceptionDtos.WalkInRequest;
import com.vihan.hospital.patient.PatientDtos.PatientMatch;

/**
 * Appointment list and workflow (DESIGN.md §11.4). Counter booking (POST on the same path) is in
 * {@link BookingControllers.StaffBookingController}. Doctors are limited to their own appointments
 * inside {@link ReceptionService}.
 */
@RestController
@RequestMapping("/api/staff/appointments")
public class ReceptionController {

	private final ReceptionService reception;

	public ReceptionController(ReceptionService reception) {
		this.reception = reception;
	}

	@GetMapping
	public List<StaffAppointmentRow> list(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
			@RequestParam(required = false) String branch, @RequestParam(required = false) Long doctor,
			@RequestParam(required = false) String status, @RequestParam(required = false) String q) {
		return reception.list(date, branch, doctor, status, q);
	}

	/** The latest bookings (any date), or the latest matching {@code q}: name, mobile or booking reference. */
	@GetMapping("/recent")
	public List<StaffAppointmentRow> recent(@RequestParam(required = false) String q) {
		return reception.recent(q);
	}

	@GetMapping("/needs-action")
	@PreAuthorize("hasAnyRole('RECEPTIONIST', 'IT_ADMIN')")
	public List<StaffAppointmentRow> needsAction(@RequestParam(required = false) String branch) {
		return reception.needsAction(branch);
	}

	@GetMapping("/{id}/patient-matches")
	@PreAuthorize("hasAnyRole('RECEPTIONIST', 'IT_ADMIN')")
	public List<PatientMatch> patientMatches(@PathVariable Long id) {
		return reception.patientMatches(id);
	}

	@PostMapping("/{id}/check-in")
	@PreAuthorize("hasAnyRole('RECEPTIONIST', 'IT_ADMIN')")
	public StaffAppointmentRow checkIn(@PathVariable Long id, @Valid @RequestBody CheckInRequest req) {
		return reception.checkIn(id, req.patientId());
	}

	@PatchMapping("/{id}/status")
	public StaffAppointmentRow changeStatus(@PathVariable Long id, @Valid @RequestBody StatusChangeRequest req) {
		return reception.changeStatus(id, req);
	}

	@PostMapping("/{id}/reschedule")
	@PreAuthorize("hasAnyRole('RECEPTIONIST', 'IT_ADMIN')")
	public StaffAppointmentRow reschedule(@PathVariable Long id, @Valid @RequestBody StaffRescheduleRequest req) {
		return reception.reschedule(id, req.newSlotId(), req.reason());
	}

	@PostMapping("/walk-in")
	@ResponseStatus(HttpStatus.CREATED)
	@PreAuthorize("hasAnyRole('RECEPTIONIST', 'IT_ADMIN')")
	public StaffAppointmentRow walkIn(@Valid @RequestBody WalkInRequest req) {
		return reception.walkIn(req);
	}

}
