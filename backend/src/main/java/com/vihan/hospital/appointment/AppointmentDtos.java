package com.vihan.hospital.appointment;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.vihan.hospital.common.util.Mobiles.IndianMobile;

public final class AppointmentDtos {

	private AppointmentDtos() {
	}

	// ---- free slots (public) ----

	/** One day in the booking window with how many slots are still free. */
	public record SlotDayDto(LocalDate date, int openSlots) {
	}

	public record PublicSlotDto(Long slotId, LocalDateTime startAt, LocalDateTime endAt, int tokenNo) {
	}

	// ---- requests ----

	/**
	 * Online booking (FR-B1). The privacy consent box must be ticked (DESIGN.md §19.1).
	 *
	 * @param uhid optional, for returning patients: linked only if that patient has this mobile (§19.5)
	 */
	public record BookingRequest(
			@NotNull Long slotId,
			@NotBlank @Size(min = 2, max = 120) String patientName,
			@NotNull @Min(0) @Max(120) Integer patientAge,
			@NotBlank @Pattern(regexp = "[MFO]", message = "must be M, F or O") String patientGender,
			@NotBlank @IndianMobile String mobile,
			@Size(max = 500) String reason,
			@AssertTrue(message = "please accept the privacy notice") boolean consent,
			String captchaToken,
			@Size(max = 20) String uhid) {
	}

	/** Counter / phone booking by reception (FR-R3): no captcha, channel recorded; optionally for a registered patient (UHID + same mobile). */
	public record StaffBookingRequest(
			@NotNull Long slotId,
			@NotBlank @Size(min = 2, max = 120) String patientName,
			@NotNull @Min(0) @Max(120) Integer patientAge,
			@NotBlank @Pattern(regexp = "[MFO]", message = "must be M, F or O") String patientGender,
			@NotBlank @IndianMobile String mobile,
			@Size(max = 500) String reason,
			@NotNull @Pattern(regexp = "COUNTER|PHONE", message = "must be COUNTER or PHONE") String channel,
			@AssertTrue(message = "the patient must agree to the privacy notice") boolean consent,
			@Size(max = 20) String uhid) {
	}

	public record LookupRequest(@NotBlank @Size(max = 30) String bookingRef, @NotBlank @Size(max = 20) String mobile) {
	}

	/**
	 * "Check my booking" with only one of the two (FR-B3): exactly one of booking reference or mobile.
	 * Finds bookings but shows only limited, masked details ({@link BookingSummary}); viewing the
	 * full booking, cancelling or rescheduling still needs both (LookupRequest).
	 */
	public record FindRequest(@Size(max = 30) String bookingRef, @Size(max = 20) String mobile, String captchaToken) {

		@AssertTrue(message = "enter either the booking reference or the mobile number")
		public boolean isExactlyOne() {
			return isBlank(bookingRef) != isBlank(mobile);
		}

		private static boolean isBlank(String s) {
			return s == null || s.isBlank();
		}
	}

	public record CancelRequest(@NotBlank @Size(max = 30) String bookingRef, @NotBlank @Size(max = 20) String mobile,
			@Size(max = 300) String reason, String captchaToken) {
	}

	public record RescheduleRequest(@NotBlank @Size(max = 30) String bookingRef, @NotBlank @Size(max = 20) String mobile,
			@NotNull Long newSlotId, String captchaToken) {
	}

	// ---- the booking as shown to the patient ----

	public record DoctorInfo(Long id, String fullName, String speciality, BigDecimal consultationFee) {
	}

	public record BranchInfo(String code, String name, String city, String address, String phone) {
	}

	/**
	 * Confirmation and "check my appointment" view (FR-B2, FR-B3).
	 *
	 * @param changeDeadline last moment the patient may cancel / reschedule online
	 * @param needsAction    the doctor became unavailable; the hospital will call
	 * @param uhid           the registered patient this booking is linked to, if any
	 */
	public record AppointmentView(String bookingRef, Appointment.Status status, Integer tokenNo, LocalDate date, LocalDateTime startAt,
			LocalDateTime endAt, DoctorInfo doctor, BranchInfo branch, String patientName, Integer patientAge,
			String patientGender, String mobileMasked, String reason, Appointment.Channel channel, int rescheduleCount,
			int reschedulesLeft, boolean canCancel, boolean canReschedule, LocalDateTime changeDeadline,
			boolean needsAction, String cancelReason, String uhid) {
	}

	/**
	 * One result of a one-field search (FindRequest). Never reveals the field that wasn't given:
	 * found by mobile → the reference is masked; found by reference → the mobile is masked. The
	 * patient name is always masked and no doctor or speciality is shown (health information).
	 *
	 * @param bookingRef  full when searched by reference, otherwise {@code VMH-261013-****}
	 * @param needs       what the visitor must still enter to open the booking: MOBILE or BOOKING_REF
	 */
	public record BookingSummary(String bookingRef, String mobileMasked, String patientNameMasked, LocalDate date,
			LocalDateTime startAt, Integer tokenNo, String branchName, String branchCity, Appointment.Status status,
			String needs) {
	}

}
