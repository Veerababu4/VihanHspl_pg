package com.vihan.hospital.notification.email;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.vihan.hospital.common.error.ApiException;
import com.vihan.hospital.common.util.Masking;
import com.vihan.hospital.config.AppProperties;

/**
 * Sends through Brevo's transactional email HTTP API (free plan: ~300 emails a day). HTTP, not SMTP:
 * Render's free plan blocks outgoing SMTP ports.
 */
public class BrevoEmailSender implements EmailSender {

	static final String SEND_URL = "https://api.brevo.com/v3/smtp/email";
	private static final Logger log = LoggerFactory.getLogger(BrevoEmailSender.class);

	record Address(String email, String name) {
	}

	/** Brevo's request body (only the fields we use). */
	record SendRequest(Address sender, List<Address> to, String subject, String textContent, String htmlContent) {
	}

	private final RestClient http;
	private final AppProperties.Email settings;

	public BrevoEmailSender(AppProperties.Email settings) {
		this(RestClient.builder().requestFactory(requestFactory()), settings);
	}

	/** For tests: lets a mock server stand in for Brevo. */
	BrevoEmailSender(RestClient.Builder builder, AppProperties.Email settings) {
		this.http = builder.build();
		this.settings = settings;
	}

	@Override
	public void send(EmailMessage m) {
		SendRequest body = new SendRequest(new Address(settings.fromAddress(), settings.fromName()),
				List.of(new Address(m.to(), null)), m.subject(), m.text(), m.html());
		try {
			http.post().uri(SEND_URL).header("api-key", settings.brevoApiKey()).contentType(MediaType.APPLICATION_JSON)
				.accept(MediaType.APPLICATION_JSON).body(body).retrieve().toBodilessEntity();
			log.info("Email sent to {}: {}", Masking.email(m.to()), m.subject());
		}
		catch (RestClientException e) {
			// The message may name the address; log only the error type and status line.
			log.error("Email to {} failed: {}", Masking.email(m.to()), e.getClass().getSimpleName() + " " + firstLine(e.getMessage()));
			throw new ApiException(HttpStatus.BAD_GATEWAY, "EMAIL_FAILED",
					"We couldn't send the email right now. Please try again later, or print this page.");
		}
	}

	private static String firstLine(String s) {
		if (s == null) {
			return "";
		}
		int nl = s.indexOf('\n');
		String line = nl < 0 ? s : s.substring(0, nl);
		return line.length() > 120 ? line.substring(0, 120) : line;
	}

	private static JdkClientHttpRequestFactory requestFactory() {
		JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
				HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
		factory.setReadTimeout(Duration.ofSeconds(10));
		return factory;
	}

}
