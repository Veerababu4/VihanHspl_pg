package com.vihan.hospital.notification.email;

import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.vihan.hospital.config.AppProperties;

/** Picks the email sender from {@code app.email.provider}; a half-configured provider stops the start. */
@Configuration(proxyBeanMethods = false)
public class EmailConfig {

	private static final Logger log = LoggerFactory.getLogger(EmailConfig.class);

	@Bean
	EmailSender emailSender(AppProperties props) {
		AppProperties.Email settings = props.email();
		String provider = settings.provider() == null ? "log" : settings.provider().strip().toLowerCase(Locale.ROOT);
		switch (provider) {
			case "log" -> {
				if (settings.formEnabled()) {
					log.warn("Confirmation emails are only written to the log (EMAIL_PROVIDER=log)");
				}
				return new LoggingEmailSender();
			}
			case "brevo" -> {
				if (isBlank(settings.brevoApiKey()) || isBlank(settings.fromAddress())) {
					throw new IllegalStateException("EMAIL_PROVIDER=brevo needs BREVO_API_KEY and MAIL_FROM");
				}
				return new BrevoEmailSender(settings);
			}
			default -> throw new IllegalStateException("Unknown EMAIL_PROVIDER '" + settings.provider() + "': use log or brevo");
		}
	}

	private static boolean isBlank(String s) {
		return s == null || s.isBlank();
	}

}
