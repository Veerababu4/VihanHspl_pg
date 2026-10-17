package com.vihan.hospital.appointment;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.web.util.HtmlUtils;

import com.vihan.hospital.appointment.AppointmentDtos.AppointmentView;
import com.vihan.hospital.config.AppProperties;
import com.vihan.hospital.notification.email.EmailSender.EmailMessage;

/**
 * The booking confirmation email (FR-B2 by email): the same facts as the confirmation page, as plain
 * text and simple HTML (inline styles, a table layout, which email clients handle). Every value from
 * the booking is HTML-escaped. With {@code testBanner} the email says plainly that it comes from a
 * software test project, not a real hospital.
 */
final class ConfirmationEmail {

	static final String TEST_NOTICE = "This is NOT a real hospital. \"Vihan Multi Speciality Hospital\" is a software "
			+ "test / learning project, and this is not a real medical appointment. Please don't visit or call for it. "
			+ "If you didn't book this, you can ignore this email.";

	private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("EEE, d MMM yyyy, h:mm a", Locale.ENGLISH);
	private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.ENGLISH);

	private ConfirmationEmail() {
	}

	static EmailMessage build(String to, AppointmentView a, AppProperties.Email settings) {
		boolean test = settings.testBanner();
		String hospital = settings.fromName();
		String subject = (test ? "[TEST] " : "") + "Booking confirmed " + a.bookingRef() + " – " + hospital;
		String link = settings.siteUrl().replaceAll("/+$", "") + "/appointment-status?ref="
				+ URLEncoder.encode(a.bookingRef(), StandardCharsets.UTF_8);

		List<String[]> rows = new ArrayList<>();
		rows.add(new String[] { "Booking reference", a.bookingRef() });
		if (a.tokenNo() != null) {
			rows.add(new String[] { "Token number", String.valueOf(a.tokenNo()) });
		}
		rows.add(new String[] { "Patient", a.patientName() });
		rows.add(new String[] { "Date & time", a.startAt() != null ? WHEN.format(a.startAt()) : DAY.format(a.date()) });
		rows.add(new String[] { "Doctor", a.doctor().fullName() + " (" + a.doctor().speciality() + ")" });
		String where = a.branch().name() + ", " + a.branch().address() + ", " + a.branch().city()
				+ (a.branch().phone() == null ? "" : " · " + a.branch().phone());
		rows.add(new String[] { "Hospital", where });
		String fee = fee(a.doctor().consultationFee());
		if (fee != null) {
			rows.add(new String[] { "Consultation fee", fee + ", pay at the hospital counter" });
		}
		String arrive = "Please arrive 15 minutes early and show this booking reference at the reception counter.";

		return new EmailMessage(to, subject, text(test, hospital, rows, arrive, link), html(test, hospital, rows, arrive, link));
	}

	private static String text(boolean test, String hospital, List<String[]> rows, String arrive, String link) {
		StringBuilder sb = new StringBuilder();
		if (test) {
			sb.append("*** TEST EMAIL ***\n").append(TEST_NOTICE).append("\n\n");
		}
		sb.append("Your appointment is confirmed – ").append(hospital).append("\n\n");
		for (String[] row : rows) {
			sb.append(row[0]).append(": ").append(row[1]).append('\n');
		}
		sb.append('\n').append(arrive).append("\n\nCheck, cancel or reschedule your booking: ").append(link).append('\n');
		return sb.toString();
	}

	private static String html(boolean test, String hospital, List<String[]> rows, String arrive, String link) {
		StringBuilder sb = new StringBuilder();
		sb.append("<!doctype html><html><body style=\"margin:0;padding:24px;background:#f1f5f9;"
				+ "font-family:Arial,Helvetica,sans-serif;color:#0f172a\">");
		sb.append("<table role=\"presentation\" width=\"100%\" style=\"max-width:600px;margin:0 auto;background:#ffffff;"
				+ "border:1px solid #e2e8f0;border-radius:12px;border-collapse:separate\">");
		if (test) {
			sb.append("<tr><td style=\"padding:16px 24px;background:#fef3c7;border-bottom:2px solid #f59e0b;"
					+ "border-radius:12px 12px 0 0;color:#78350f;font-size:14px;line-height:1.5\">")
				.append("<strong style=\"font-size:15px\">&#9888; TEST EMAIL &ndash; NOT A REAL HOSPITAL</strong><br>")
				.append(esc(TEST_NOTICE)).append("</td></tr>");
		}
		sb.append("<tr><td style=\"padding:24px 24px 8px\"><h1 style=\"margin:0;font-size:22px\">&#9989; Appointment confirmed</h1>")
			.append("<p style=\"margin:4px 0 0;color:#475569\">").append(esc(hospital)).append("</p></td></tr>");
		sb.append("<tr><td style=\"padding:8px 24px\"><table role=\"presentation\" width=\"100%\" style=\"border-collapse:collapse\">");
		for (String[] row : rows) {
			sb.append("<tr><td style=\"padding:8px 12px 8px 0;color:#64748b;font-size:14px;vertical-align:top;white-space:nowrap\">")
				.append(esc(row[0])).append("</td><td style=\"padding:8px 0;font-size:15px;font-weight:bold\">")
				.append(esc(row[1])).append("</td></tr>");
		}
		sb.append("</table></td></tr>");
		sb.append("<tr><td style=\"padding:8px 24px 24px;font-size:14px;line-height:1.5\"><p style=\"margin:0 0 16px\">")
			.append(esc(arrive)).append("</p><a href=\"").append(esc(link))
			.append("\" style=\"display:inline-block;padding:10px 16px;background:#0f766e;color:#ffffff;"
					+ "text-decoration:none;border-radius:8px\">Check, cancel or reschedule</a></td></tr>");
		sb.append("</table></body></html>");
		return sb.toString();
	}

	private static String fee(BigDecimal fee) {
		return fee == null || fee.signum() <= 0 ? null : "₹" + fee.stripTrailingZeros().toPlainString();
	}

	private static String esc(String s) {
		return HtmlUtils.htmlEscape(s, StandardCharsets.UTF_8.name());
	}

}
