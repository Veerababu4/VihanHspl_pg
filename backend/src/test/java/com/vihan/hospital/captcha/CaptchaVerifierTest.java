package com.vihan.hospital.captcha;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.vihan.hospital.common.error.ApiException;
import com.vihan.hospital.config.AppProperties;

/** Turnstile verification against a mock Cloudflare. */
class CaptchaVerifierTest {

	private static AppProperties props(boolean enabled, String hostname) {
		return new AppProperties(false, List.of(), "x", "/files", null, null, null,
				new AppProperties.Captcha(enabled, "secret-123", hostname), null, null);
	}

	private final RestClient.Builder builder = RestClient.builder();
	private final MockRestServiceServer cloudflare = MockRestServiceServer.bindTo(builder).build();

	@Test
	void acceptsAValidTokenAndSendsSecretTokenAndIp() {
		CaptchaVerifier verifier = new CaptchaVerifier(builder, props(true, ""));
		cloudflare.expect(requestTo(CaptchaVerifier.VERIFY_URL))
			.andExpect(method(HttpMethod.POST))
			.andExpect(content().formDataContains(Map.of("secret", "secret-123", "response", "tok", "remoteip", "1.2.3.4")))
			.andRespond(withSuccess("{\"success\":true,\"error-codes\":[],\"hostname\":\"example.com\"}", MediaType.APPLICATION_JSON));
		assertThatCode(() -> verifier.verify("tok", "1.2.3.4")).doesNotThrowAnyException();
		cloudflare.verify();
	}

	@Test
	void rejectsFailedTokens() {
		CaptchaVerifier verifier = new CaptchaVerifier(builder, props(true, ""));
		cloudflare.expect(requestTo(CaptchaVerifier.VERIFY_URL))
			.andRespond(withSuccess("{\"success\":false,\"error-codes\":[\"timeout-or-duplicate\"]}", MediaType.APPLICATION_JSON));
		assertThatThrownBy(() -> verifier.verify("used-token", null))
			.isInstanceOf(ApiException.class).hasFieldOrPropertyWithValue("code", "CAPTCHA_FAILED");
	}

	@Test
	void rejectsTokensSolvedOnAnotherSite() {
		CaptchaVerifier verifier = new CaptchaVerifier(builder, props(true, "vihanhospital.in"));
		cloudflare.expect(requestTo(CaptchaVerifier.VERIFY_URL))
			.andRespond(withSuccess("{\"success\":true,\"hostname\":\"evil.example\"}", MediaType.APPLICATION_JSON));
		assertThatThrownBy(() -> verifier.verify("tok", null))
			.isInstanceOf(ApiException.class).hasFieldOrPropertyWithValue("code", "CAPTCHA_FAILED");
	}

	@Test
	void failsClosedWhenCloudflareIsDown() {
		CaptchaVerifier verifier = new CaptchaVerifier(builder, props(true, ""));
		cloudflare.expect(requestTo(CaptchaVerifier.VERIFY_URL)).andRespond(withServerError());
		assertThatThrownBy(() -> verifier.verify("tok", null))
			.isInstanceOf(ApiException.class).hasFieldOrPropertyWithValue("code", "CAPTCHA_UNAVAILABLE");
	}

	@Test
	void missingTokenIsRejectedWithoutCallingCloudflare() {
		CaptchaVerifier verifier = new CaptchaVerifier(builder, props(true, ""));
		assertThatThrownBy(() -> verifier.verify(" ", null))
			.isInstanceOf(ApiException.class).hasFieldOrPropertyWithValue("code", "CAPTCHA_REQUIRED");
		cloudflare.verify();   // no request was made
	}

	@Test
	void disabledCheckAcceptsAnything() {
		CaptchaVerifier verifier = new CaptchaVerifier(builder, props(false, ""));
		assertThatCode(() -> verifier.verify(null, null)).doesNotThrowAnyException();
	}

}
