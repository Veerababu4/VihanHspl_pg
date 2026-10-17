package com.vihan.hospital.appointment;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

import com.vihan.hospital.appointment.AppointmentDtos.AppointmentView;
import com.vihan.hospital.appointment.AppointmentDtos.BranchInfo;
import com.vihan.hospital.appointment.AppointmentDtos.DoctorInfo;
import com.vihan.hospital.config.AppProperties;
import com.vihan.hospital.notification.email.EmailSender.EmailMessage;

class ConfirmationEmailTest {

	private static AppProperties.Email settings(boolean testBanner) {
		return new AppProperties.Email("log", true, "", "", "Vihan Multi Speciality Hospital", "https://vihanhspl-pg.pages.dev/",
				3, testBanner);
	}

	private static AppointmentView view(String patientName) {
		LocalDateTime start = LocalDateTime.of(2026, 10, 21, 10, 20);
		return new AppointmentView("VMH-261017-7K3Q", Appointment.Status.BOOKED, 2, start.toLocalDate(), start,
				start.plusMinutes(20), new DoctorInfo(1L, "Dr. Ravi Teja", "ENT", new BigDecimal("400.00")),
				new BranchInfo("MLG", "Vihan Miryalaguda", "Miryalaguda", "Main Road", "08689 000000"), patientName, 42, "M",
				"98XXXXXX10", null, Appointment.Channel.ONLINE, 0, 2, true, true, start.minusHours(1), false, null, null);
	}

	@Test
	void testProjectEmailSaysSoInSubjectAndAtTheTop() {
		EmailMessage m = ConfirmationEmail.build("ravi@example.com", view("Ravi Kumar"), settings(true));

		assertThat(m.to()).isEqualTo("ravi@example.com");
		assertThat(m.subject()).startsWith("[TEST] Booking confirmed VMH-261017-7K3Q");
		assertThat(m.text()).startsWith("*** TEST EMAIL ***\nThis is NOT a real hospital.");
		assertThat(m.html()).contains("TEST EMAIL &ndash; NOT A REAL HOSPITAL");
		assertThat(m.html().indexOf("NOT A REAL HOSPITAL")).isLessThan(m.html().indexOf("Appointment confirmed"));
	}

	@Test
	void realEmailHasNoTestNotice() {
		EmailMessage m = ConfirmationEmail.build("ravi@example.com", view("Ravi Kumar"), settings(false));

		assertThat(m.subject()).startsWith("Booking confirmed");
		assertThat(m.text()).doesNotContain("TEST");
		assertThat(m.html()).doesNotContain("NOT A REAL HOSPITAL");
	}

	@Test
	void carriesTheBookingDetailsAndALinkToChangeIt() {
		EmailMessage m = ConfirmationEmail.build("ravi@example.com", view("Ravi Kumar"), settings(true));

		assertThat(m.text())
			.contains("Booking reference: VMH-261017-7K3Q")
			.contains("Token number: 2")
			.contains("Patient: Ravi Kumar")
			.contains("Date & time: Wed, 21 Oct 2026, 10:20 AM")
			.contains("Doctor: Dr. Ravi Teja (ENT)")
			.contains("Hospital: Vihan Miryalaguda, Main Road, Miryalaguda · 08689 000000")
			.contains("Consultation fee: ₹400, pay at the hospital counter")
			.contains("https://vihanhspl-pg.pages.dev/appointment-status?ref=VMH-261017-7K3Q")
			.doesNotContain("98XXXXXX10");   // no mobile in the email
		assertThat(m.html()).contains("href=\"https://vihanhspl-pg.pages.dev/appointment-status?ref=VMH-261017-7K3Q\"");
	}

	@Test
	void escapesHtmlInWhatTheBookerTyped() {
		EmailMessage m = ConfirmationEmail.build("x@example.com", view("<script>alert(1)</script>"), settings(true));

		assertThat(m.html()).doesNotContain("<script>").contains("&lt;script&gt;alert(1)&lt;/script&gt;");
	}

	@Test
	void walkInWithoutTimeShowsTheDay() {
		AppointmentView v = view("Ravi Kumar");
		AppointmentView noTime = new AppointmentView(v.bookingRef(), v.status(), null, LocalDate.of(2026, 10, 21), null, null,
				v.doctor(), v.branch(), v.patientName(), null, null, v.mobileMasked(), null, v.channel(), 0, 2, false, false, null,
				false, null, null);

		EmailMessage m = ConfirmationEmail.build("x@example.com", noTime, settings(true));

		assertThat(m.text()).contains("Date & time: Wed, 21 Oct 2026\n").doesNotContain("Token number");
	}

}
