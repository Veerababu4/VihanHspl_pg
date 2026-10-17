package com.vihan.hospital.notification.email;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.vihan.hospital.common.util.Masking;

/** Local runs, tests, and production until a provider is set up: writes what would be sent to the log. */
public class LoggingEmailSender implements EmailSender {

	private static final Logger log = LoggerFactory.getLogger("notifications");

	@Override
	public void send(EmailMessage m) {
		log.info("[would send email] to {}: {}", Masking.email(m.to()), m.subject());
	}

}
