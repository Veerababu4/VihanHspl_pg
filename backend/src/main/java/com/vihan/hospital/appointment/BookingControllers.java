package com.vihan.hospital.appointment;

import java.time.LocalDate;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.vihan.hospital.appointment.AppointmentDtos.AppointmentView;
import com.vihan.hospital.appointment.AppointmentDtos.BookingRequest;
import com.vihan.hospital.appointment.AppointmentDtos.CancelRequest;
import com.vihan.hospital.appointment.AppointmentDtos.LookupRequest;
import com.vihan.hospital.appointment.AppointmentDtos.BookingSummary;
import com.vihan.hospital.appointment.AppointmentDtos.FindRequest;
import com.vihan.hospital.appointment.AppointmentDtos.PublicSlotDto;
import com.vihan.hospital.appointment.AppointmentDtos.RescheduleRequest;
import com.vihan.hospital.appointment.AppointmentDtos.SlotDayDto;
import com.vihan.hospital.appointment.AppointmentDtos.StaffBookingRequest;
import com.vihan.hospital.captcha.CaptchaVerifier;

/**
 * Booking endpoints (DESIGN.md §11.1, §11.4). Per-IP rate limits are applied before these run
 * (RateLimitInterceptor); the captcha is verified here, outside the database transaction, so a
 * slow call to Cloudflare never holds a database connection.
 */
public final class BookingControllers {

	private BookingControllers() {
	}

	@RestController
	@RequestMapping("/api/public")
	public static class PublicBookingController {

		private final PublicSlotService slots;
		private final BookingService booking;
		private final CaptchaVerifier captcha;

		public PublicBookingController(PublicSlotService slots, BookingService booking, CaptchaVerifier captcha) {
			this.slots = slots;
			this.booking = booking;
			this.captcha = captcha;
		}

		@GetMapping("/doctors/{doctorId}/slot-days")
		public List<SlotDayDto> slotDays(@PathVariable Long doctorId, @RequestParam String branch) {
			return slots.days(doctorId, branch);
		}

		@GetMapping("/doctors/{doctorId}/slots")
		public List<PublicSlotDto> slots(@PathVariable Long doctorId, @RequestParam String branch,
				@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
			return slots.slots(doctorId, branch, date);
		}

		@PostMapping("/appointments")
		@ResponseStatus(HttpStatus.CREATED)
		public AppointmentView book(@Valid @RequestBody BookingRequest req, HttpServletRequest http) {
			captcha.verify(req.captchaToken(), http.getRemoteAddr());
			return booking.bookOnline(req, http.getRemoteAddr());
		}

		@PostMapping("/appointments/lookup")
		public AppointmentView lookup(@Valid @RequestBody LookupRequest req) {
			return booking.lookup(req.bookingRef(), req.mobile());
		}

		/** One field only: limited, masked results; captcha because a mobile number alone is easy to try. */
	@PostMapping("/appointments/find")
	public List<BookingSummary> find(@Valid @RequestBody FindRequest req, HttpServletRequest http) {
		captcha.verify(req.captchaToken(), http.getRemoteAddr());
		return booking.findByOne(req.bookingRef(), req.mobile());
	}

	@PostMapping("/appointments/cancel")
		public AppointmentView cancel(@Valid @RequestBody CancelRequest req, HttpServletRequest http) {
			captcha.verify(req.captchaToken(), http.getRemoteAddr());
			return booking.cancelByPatient(req.bookingRef(), req.mobile(), req.reason());
		}

		@PostMapping("/appointments/reschedule")
		public AppointmentView reschedule(@Valid @RequestBody RescheduleRequest req, HttpServletRequest http) {
			captcha.verify(req.captchaToken(), http.getRemoteAddr());
			return booking.rescheduleByPatient(req.bookingRef(), req.mobile(), req.newSlotId());
		}

	}

	/** Counter / phone booking (FR-R3). The list and status workflow are in {@link ReceptionController}. */
	@RestController
	@RequestMapping("/api/staff/appointments")
	@PreAuthorize("hasAnyRole('RECEPTIONIST', 'IT_ADMIN')")
	public static class StaffBookingController {

		private final BookingService booking;

		public StaffBookingController(BookingService booking) {
			this.booking = booking;
		}

		@PostMapping
		@ResponseStatus(HttpStatus.CREATED)
		public AppointmentView book(@Valid @RequestBody StaffBookingRequest req) {
			return booking.bookByStaff(req);
		}

	}

}
