package com.vihan.hospital.notification.email;

/**
 * Sends one email (DESIGN.md §19.8). Chosen by {@code app.email.provider} in {@link EmailConfig}:
 * {@link LoggingEmailSender} (default) or {@link BrevoEmailSender}.
 */
public interface EmailSender {

	/** One email, with a plain-text and an HTML body. */
	record EmailMessage(String to, String subject, String text, String html) {
	}

	/** @throws com.vihan.hospital.common.error.ApiException 502 when the provider refused it or couldn't be reached */
	void send(EmailMessage message);

}
