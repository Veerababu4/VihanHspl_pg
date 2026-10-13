package com.vihan.hospital;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * Shared setup for integration tests. Every class using it shares one Spring context and one
 * PostgreSQL container, so the database starts only once per test run.
 * The {@code local} profile loads the fictional sample data, so that script is tested too.
 * Tests share the database: a test that creates doctors must deactivate them afterwards, because
 * other tests count the active (sample) doctors.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, TestCaptchaConfiguration.class })
@ActiveProfiles("local")
@TestPropertySource(properties = {
		"app.auth.jwt-secret=test-secret-for-integration-tests-only-0123456789",
		"app.auth.dev-sample-password=" + VihanIntegrationTest.SAMPLE_PASSWORD,
		"app.auth.bootstrap-admin-password=",
		// Fixed test-only Aadhaar keys (32 bytes each); real keys come from .env
		"app.aadhaar.enc-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
		"app.aadhaar.hash-key=ZmVkY2JhOTg3NjU0MzIxMGZlZGNiYTk4NzY1NDMyMTA=",
		// Tests log in many times from one address; the login limit itself is unit-tested.
		"app.rate-limit.login-per-minute=1000" })
public @interface VihanIntegrationTest {

	/** Password of the sample staff logins (itadmin, reception.mlg, doctor001 …) in tests. */
	String SAMPLE_PASSWORD = "Sample-pass-123";

}
