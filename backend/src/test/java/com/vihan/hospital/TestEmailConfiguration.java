package com.vihan.hospital;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import com.vihan.hospital.notification.email.EmailSender;

/**
 * Integration tests never send real email: this stand-in keeps every message so tests can check
 * what would have gone out. (The Brevo call itself is covered by BrevoEmailSenderTest.)
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestEmailConfiguration {

	/** Collects the messages; tests share it, so look for your own booking reference. */
	public static class RecordingEmailSender implements EmailSender {

		private final List<EmailMessage> sent = new CopyOnWriteArrayList<>();

		@Override
		public void send(EmailMessage message) {
			sent.add(message);
		}

		public List<EmailMessage> sentTo(String address) {
			return sent.stream().filter(m -> m.to().equals(address)).toList();
		}

	}

	@Bean
	@Primary
	RecordingEmailSender recordingEmailSender() {
		return new RecordingEmailSender();
	}

}
