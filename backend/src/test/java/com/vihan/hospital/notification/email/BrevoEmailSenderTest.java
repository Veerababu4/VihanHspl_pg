package com.vihan.hospital.notification.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.vihan.hospital.common.error.ApiException;
import com.vihan.hospital.config.AppProperties;
import com.vihan.hospital.notification.email.EmailSender.EmailMessage;

/** Brevo's HTTP API against a mock server, and choosing the sender from the settings. */
class BrevoEmailSenderTest {

	private static AppProperties.Email settings(String provider, String apiKey, String from) {
		return new AppProperties.Email(provider, true, apiKey, from, "Vihan Hospital", "http://localhost:5173", 3, true);
	}

	private static AppProperties props(AppProperties.Email email) {
		return new AppProperties(false, List.of(), "x", "/files", null, null, null, null, null, email);
	}

	private final RestClient.Builder builder = RestClient.builder();
	private final MockRestServiceServer brevo = MockRestServiceServer.bindTo(builder).build();
	private final EmailMessage message = new EmailMessage("ravi@example.com", "[TEST] Booking confirmed", "text", "<p>html</p>");

	@Test
	void postsSenderRecipientAndBothBodiesWithTheApiKey() {
		BrevoEmailSender sender = new BrevoEmailSender(builder, settings("brevo", "key-123", "hospital@example.com"));
		brevo.expect(requestTo(BrevoEmailSender.SEND_URL))
			.andExpect(method(HttpMethod.POST))
			.andExpect(header("api-key", "key-123"))
			.andExpect(jsonPath("$.sender.email").value("hospital@example.com"))
			.andExpect(jsonPath("$.sender.name").value("Vihan Hospital"))
			.andExpect(jsonPath("$.to[0].email").value("ravi@example.com"))
			.andExpect(jsonPath("$.subject").value("[TEST] Booking confirmed"))
			.andExpect(jsonPath("$.textContent").value("text"))
			.andExpect(jsonPath("$.htmlContent").value("<p>html</p>"))
			.andRespond(withSuccess("{\"messageId\":\"<1@smtp-relay>\"}", MediaType.APPLICATION_JSON));

		assertThatCode(() -> sender.send(message)).doesNotThrowAnyException();
		brevo.verify();
	}

	@Test
	void aRefusedSendIsA502ForThePatient() {
		BrevoEmailSender sender = new BrevoEmailSender(builder, settings("brevo", "bad-key", "hospital@example.com"));
		brevo.expect(requestTo(BrevoEmailSender.SEND_URL))
			.andRespond(withStatus(HttpStatus.UNAUTHORIZED).body("{\"code\":\"unauthorized\"}").contentType(MediaType.APPLICATION_JSON));

		assertThatThrownBy(() -> sender.send(message))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("code", "EMAIL_FAILED")
			.hasFieldOrPropertyWithValue("status", HttpStatus.BAD_GATEWAY);
	}

	@Test
	void providerIsChosenFromSettingsAndBrevoNeedsKeyAndSender() {
		EmailConfig config = new EmailConfig();
		assertThat(config.emailSender(props(settings("log", "", "")))).isInstanceOf(LoggingEmailSender.class);
		assertThat(config.emailSender(props(settings(" Brevo ", "k", "a@b.c")))).isInstanceOf(BrevoEmailSender.class);
		assertThatThrownBy(() -> config.emailSender(props(settings("brevo", "", "a@b.c"))))
			.isInstanceOf(IllegalStateException.class).hasMessageContaining("BREVO_API_KEY");
		assertThatThrownBy(() -> config.emailSender(props(settings("smtp", "", ""))))
			.isInstanceOf(IllegalStateException.class).hasMessageContaining("Unknown EMAIL_PROVIDER");
	}

}
