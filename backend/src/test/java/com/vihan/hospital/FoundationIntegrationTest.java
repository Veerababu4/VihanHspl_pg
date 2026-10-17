package com.vihan.hospital;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.vihan.hospital.config.SecurityConfig;

/**
 * M1 end-to-end check on a fresh PostgreSQL: Flyway applies V1/V2 + sample data, Hibernate validates
 * the schema, and the security / error-handling baseline behaves as designed.
 */
@VihanIntegrationTest
class FoundationIntegrationTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate jdbc;

	@Test
	void migrationsCreateReferenceAndSampleData() {
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM branch", Integer.class)).isEqualTo(3);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM speciality", Integer.class)).isEqualTo(18);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM doctor WHERE registration_no LIKE 'SAMPLE-%'", Integer.class)).isEqualTo(20);
		assertThat(jdbc.queryForObject(
				"SELECT COUNT(*) FROM speciality s WHERE NOT EXISTS (SELECT 1 FROM doctor d WHERE d.speciality_id = s.speciality_id)",
				Integer.class)).as("every speciality has a sample doctor").isZero();
	}

	@Test
	void sampleSchedulesNeverOverlapAcrossBranches() {
		Integer overlaps = jdbc.queryForObject("""
				SELECT COUNT(*) FROM doctor_schedule a JOIN doctor_schedule b
				  ON a.doctor_id = b.doctor_id AND a.day_of_week = b.day_of_week AND a.schedule_id < b.schedule_id
				 AND a.start_time < b.end_time AND b.start_time < a.end_time""", Integer.class);
		assertThat(overlaps).isZero();
	}

	@Test
	void publicBranchesAreListedInOrder() throws Exception {
		mvc.perform(get("/api/public/branches"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(3))
			.andExpect(jsonPath("$[0].code").value("MLG"))
			.andExpect(jsonPath("$[1].code").value("HZN"))
			.andExpect(jsonPath("$[2].code").value("KDD"))
			.andExpect(header().exists("X-Request-Id"));
	}

	@Test
	void apiResponsesCarrySecurityHeaders() throws Exception {
		mvc.perform(get("/api/public/branches"))
			.andExpect(status().isOk())
			.andExpect(header().string("Content-Security-Policy", SecurityConfig.API_CSP))
			.andExpect(header().string("X-Content-Type-Options", "nosniff"))
			.andExpect(header().string("X-Frame-Options", "DENY"))
			.andExpect(header().string("Referrer-Policy", "no-referrer"))
			.andExpect(header().string("Permissions-Policy", SecurityConfig.PERMISSIONS_POLICY))
			.andExpect(header().doesNotExist("Strict-Transport-Security"));   // plain HTTP: browsers ignore it
		mvc.perform(get("/api/public/branches").secure(true))
			.andExpect(header().string("Strict-Transport-Security", "max-age=31536000 ; includeSubDomains"));
		mvc.perform(get("/api/staff/dashboard/summary"))   // error answers too
			.andExpect(status().isUnauthorized())
			.andExpect(header().string("Content-Security-Policy", SecurityConfig.API_CSP));
	}

	@Test
	void appInfoReportsSampleData() throws Exception {
		mvc.perform(get("/api/public/app-info"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.sampleData").value(true))
			.andExpect(jsonPath("$.confirmationEmail").value(true))   // local profile: the form is on, emails only logged
			.andExpect(jsonPath("$.testNotice").value(true));
	}

	@Test
	void staffEndpointsRequireLoginWithJsonError() throws Exception {
		mvc.perform(get("/api/staff/dashboard/summary"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.status").value(401))
			.andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
	}

	@Test
	void unknownPublicPathReturnsJson404() throws Exception {
		mvc.perform(get("/api/public/does-not-exist"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("NOT_FOUND"));
	}

	@Test
	void healthIsPublic() throws Exception {
		mvc.perform(get("/actuator/health"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("UP"));
	}

}
