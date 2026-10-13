package com.vihan.hospital;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;

/**
 * M3 login, tokens, lockout, forced password change, roles and audit, with real tokens.
 * Not @Transactional: these flows depend on committed data. Tests that change an account's
 * state create their own throwaway user so they don't affect each other.
 */
@VihanIntegrationTest
class AuthIntegrationTest {

	private static final String PASSWORD = VihanIntegrationTest.SAMPLE_PASSWORD;

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate jdbc;

	record Session(String accessToken, Cookie refreshCookie, MockHttpServletResponse response) {
		String bearer() {
			return "Bearer " + accessToken;
		}
	}

	private ResultActions loginRequest(String username, String password) throws Exception {
		return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
			.content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, password)));
	}

	private Session login(String username, String password) throws Exception {
		MockHttpServletResponse res = loginRequest(username, password).andExpect(status().isOk()).andReturn().getResponse();
		return new Session(JsonPath.read(res.getContentAsString(), "$.accessToken"), res.getCookie("vihan_refresh"), res);
	}

	private Session refresh(Cookie cookie) throws Exception {
		MockHttpServletResponse res = mvc.perform(post("/api/auth/refresh").cookie(cookie))
			.andExpect(status().isOk()).andReturn().getResponse();
		return new Session(JsonPath.read(res.getContentAsString(), "$.accessToken"), res.getCookie("vihan_refresh"), res);
	}

	/** Creates a receptionist via the admin API; returns [userId, temporaryPassword]. */
	private Object[] createReceptionist(Session admin) throws Exception {
		long mlg = jdbc.queryForObject("SELECT branch_id FROM branch WHERE code = 'MLG'", Long.class);
		String username = "test." + UUID.randomUUID().toString().substring(0, 8);
		String body = mvc.perform(post("/api/admin/users").header(HttpHeaders.AUTHORIZATION, admin.bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"username\":\"%s\",\"fullName\":\"Test Receptionist\",\"role\":\"RECEPTIONIST\",\"homeBranchId\":%d}"
					.formatted(username, mlg)))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.user.mustChangePassword").value(true))
			.andReturn().getResponse().getContentAsString();
		return new Object[] { username, JsonPath.read(body, "$.temporaryPassword"), ((Number) JsonPath.read(body, "$.user.id")).longValue() };
	}

	// ---- login ----

	@Test
	void loginReturnsAccessTokenAndSecureRefreshCookie() throws Exception {
		Session s = login("itadmin", PASSWORD);
		String setCookie = s.response().getHeader(HttpHeaders.SET_COOKIE);
		assertThat(setCookie).contains("vihan_refresh=", "HttpOnly", "Secure", "SameSite=Strict", "Path=/api/auth");
		assertThat(s.response().getContentAsString()).doesNotContain(s.refreshCookie().getValue());   // only in the cookie

		mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, s.bearer()))
			.andExpect(jsonPath("$.username").value("itadmin"))
			.andExpect(jsonPath("$.role").value("IT_ADMIN"))
			.andExpect(jsonPath("$.mustChangePassword").value(false));
		mvc.perform(get("/api/admin/users").header(HttpHeaders.AUTHORIZATION, s.bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[*].username", hasItem("doctor001")));
	}

	@Test
	void usernameIsCaseInsensitive() throws Exception {
		loginRequest("  ItAdmin ", PASSWORD).andExpect(status().isOk());
	}

	@Test
	void wrongPasswordAndUnknownUserGetTheSameAnswer() throws Exception {
		loginRequest("itadmin", "wrong-pass-1").andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
			.andExpect(jsonPath("$.message").value("Invalid username or password"));
		loginRequest("nobody-here", "wrong-pass-1").andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.message").value("Invalid username or password"));
	}

	@Test
	void fiveFailuresLockTheAccountUntilAdminReset() throws Exception {
		Session admin = login("itadmin", PASSWORD);
		Object[] user = createReceptionist(admin);
		String username = (String) user[0];

		for (int i = 1; i <= 4; i++) {
			loginRequest(username, "wrong-pass-" + i).andExpect(status().isUnauthorized());
		}
		loginRequest(username, "wrong-pass-5").andExpect(status().isLocked())
			.andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"));
		loginRequest(username, (String) user[1]).andExpect(status().isLocked());   // even the right password

		String reset = mvc.perform(post("/api/admin/users/{id}/reset-password", user[2])
				.header(HttpHeaders.AUTHORIZATION, admin.bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.user.locked").value(false))
			.andReturn().getResponse().getContentAsString();
		loginRequest(username, JsonPath.read(reset, "$.temporaryPassword")).andExpect(status().isOk())
			.andExpect(jsonPath("$.user.mustChangePassword").value(true));
	}

	// ---- temporary password ----

	@Test
	void temporaryPasswordMustBeChangedBeforeAnythingElse() throws Exception {
		Session admin = login("itadmin", PASSWORD);
		Object[] user = createReceptionist(admin);
		Session temp = login((String) user[0], (String) user[1]);

		mvc.perform(get("/api/staff/anything").header(HttpHeaders.AUTHORIZATION, temp.bearer()))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_REQUIRED"));

		String change = "{\"currentPassword\":\"%s\",\"newPassword\":\"%s\"}";
		mvc.perform(post("/api/auth/change-password").header(HttpHeaders.AUTHORIZATION, temp.bearer())
				.contentType(MediaType.APPLICATION_JSON).content(change.formatted(user[1], "short1")))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("WEAK_PASSWORD"));
		mvc.perform(post("/api/auth/change-password").header(HttpHeaders.AUTHORIZATION, temp.bearer())
				.contentType(MediaType.APPLICATION_JSON).content(change.formatted("not-it-1", "NewPassw0rd")))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("WRONG_PASSWORD"));

		String body = mvc.perform(post("/api/auth/change-password").header(HttpHeaders.AUTHORIZATION, temp.bearer())
				.contentType(MediaType.APPLICATION_JSON).content(change.formatted(user[1], "NewPassw0rd")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.user.mustChangePassword").value(false))
			.andReturn().getResponse().getContentAsString();
		String fresh = "Bearer " + JsonPath.read(body, "$.accessToken");

		mvc.perform(get("/api/staff/anything").header(HttpHeaders.AUTHORIZATION, fresh))
			.andExpect(status().isNotFound());   // past security now; the endpoint just doesn't exist yet
		mvc.perform(post("/api/auth/refresh").cookie(temp.refreshCookie()))
			.andExpect(status().isUnauthorized());   // old sessions ended by the password change
	}

	// ---- roles ----

	@Test
	void rolesAreEnforcedWithRealTokens() throws Exception {
		Session reception = login("reception.mlg", PASSWORD);
		Session doctor = login("doctor003", PASSWORD);

		mvc.perform(get("/api/admin/users").header(HttpHeaders.AUTHORIZATION, reception.bearer()))
			.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
		mvc.perform(get("/api/admin/doctors").header(HttpHeaders.AUTHORIZATION, doctor.bearer()))
			.andExpect(status().isForbidden());
		mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, doctor.bearer()))
			.andExpect(jsonPath("$.role").value("DOCTOR"))
			.andExpect(jsonPath("$.doctorId").isNumber());
	}

	@Test
	void forgedOrMissingTokensAreRejected() throws Exception {
		Session s = login("itadmin", PASSWORD);
		String tampered = s.accessToken().substring(0, s.accessToken().length() - 4) + "AAAA";
		mvc.perform(get("/api/admin/users").header(HttpHeaders.AUTHORIZATION, "Bearer " + tampered))
			.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
		mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
	}

	// ---- refresh tokens ----

	@Test
	void refreshRotatesTheTokenAndLogoutEndsTheSession() throws Exception {
		Session first = login("reception.hzn", PASSWORD);
		Session second = refresh(first.refreshCookie());
		assertThat(second.refreshCookie().getValue()).isNotEqualTo(first.refreshCookie().getValue());
		mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, second.bearer()))
			.andExpect(jsonPath("$.username").value("reception.hzn"));

		mvc.perform(post("/api/auth/logout").cookie(second.refreshCookie()).header(HttpHeaders.AUTHORIZATION, second.bearer()))
			.andExpect(status().isNoContent());
		mvc.perform(post("/api/auth/refresh").cookie(second.refreshCookie()))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("SESSION_EXPIRED"));
	}

	@Test
	void refreshWithoutCookieIsANormalNoSessionAnswer() throws Exception {
		mvc.perform(post("/api/auth/refresh")).andExpect(status().isNoContent());
		mvc.perform(post("/api/auth/refresh").cookie(new Cookie("vihan_refresh", "made-up")))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("SESSION_EXPIRED"));
	}

	@Test
	void reusingAnOldRefreshTokenRevokesAllSessions() throws Exception {
		Session first = login("reception.kdd", PASSWORD);
		Session second = refresh(first.refreshCookie());

		// Within 30 s a second use counts as a two-tab race: refused, but no lockout of other sessions.
		mvc.perform(post("/api/auth/refresh").cookie(first.refreshCookie())).andExpect(status().isUnauthorized());
		refresh(second.refreshCookie());   // still works... and rotates again
		Session third = login("reception.kdd", PASSWORD);

		// Later, the same old token again = stolen copy: every session of the user is ended.
		jdbc.update("UPDATE refresh_token SET updated_at = updated_at - INTERVAL '5' MINUTE WHERE revoked = TRUE");
		mvc.perform(post("/api/auth/refresh").cookie(first.refreshCookie())).andExpect(status().isUnauthorized());
		mvc.perform(post("/api/auth/refresh").cookie(third.refreshCookie())).andExpect(status().isUnauthorized());
		assertThat(jdbc.queryForObject(
				"SELECT COUNT(*) FROM audit_log WHERE action = 'REFRESH_TOKEN_REUSE'", Integer.class)).isPositive();
	}

	// ---- user admin ----

	@Test
	void deactivatingAUserEndsTheirSessionAndBlocksLogin() throws Exception {
		Session admin = login("itadmin", PASSWORD);
		Object[] user = createReceptionist(admin);
		Session victim = login((String) user[0], (String) user[1]);
		long mlg = jdbc.queryForObject("SELECT branch_id FROM branch WHERE code = 'MLG'", Long.class);

		mvc.perform(put("/api/admin/users/{id}", user[2]).header(HttpHeaders.AUTHORIZATION, admin.bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"fullName\":\"Gone\",\"role\":\"RECEPTIONIST\",\"homeBranchId\":%d,\"active\":false}".formatted(mlg)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.active").value(false));

		mvc.perform(post("/api/auth/refresh").cookie(victim.refreshCookie())).andExpect(status().isUnauthorized());
		loginRequest((String) user[0], (String) user[1]).andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
	}

	@Test
	void adminCannotLockThemselvesOutOrBreakRoleRules() throws Exception {
		Session admin = login("itadmin", PASSWORD);
		long adminId = jdbc.queryForObject("SELECT user_id FROM app_user WHERE username = 'itadmin'", Long.class);
		mvc.perform(put("/api/admin/users/{id}", adminId).header(HttpHeaders.AUTHORIZATION, admin.bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"fullName\":\"Me\",\"role\":\"IT_ADMIN\",\"active\":false}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("CANNOT_CHANGE_SELF"));

		mvc.perform(post("/api/admin/users").header(HttpHeaders.AUTHORIZATION, admin.bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"username\":\"dr.nolink\",\"fullName\":\"No Link\",\"role\":\"DOCTOR\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("DOCTOR_REQUIRED"));

		long takenDoctor = jdbc.queryForObject("SELECT doctor_id FROM doctor WHERE registration_no = 'SAMPLE-001'", Long.class);
		mvc.perform(post("/api/admin/users").header(HttpHeaders.AUTHORIZATION, admin.bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"username\":\"dr.dup\",\"fullName\":\"Dup\",\"role\":\"DOCTOR\",\"doctorId\":%d}".formatted(takenDoctor)))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("DOCTOR_HAS_LOGIN"));
	}

	// ---- audit ----

	@Test
	void auditLogRecordsWhoDidWhat() throws Exception {
		Session admin = login("itadmin", PASSWORD);
		long adminId = jdbc.queryForObject("SELECT user_id FROM app_user WHERE username = 'itadmin'", Long.class);
		long ent = jdbc.queryForObject("SELECT speciality_id FROM speciality WHERE name = 'ENT'", Long.class);

		mvc.perform(put("/api/admin/specialities/{id}", ent).header(HttpHeaders.AUTHORIZATION, admin.bearer())
				.contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"ENT\",\"description\":\"Ear, nose and throat\",\"active\":true}"))
			.andExpect(status().isOk());
		assertThat(jdbc.queryForObject("SELECT updated_by FROM speciality WHERE speciality_id = ?", Long.class, ent))
			.isEqualTo(adminId);   // JPA auditing fills updated_by from the token

		mvc.perform(get("/api/admin/audit-logs").param("action", "SPECIALITY_UPDATED").param("userId", String.valueOf(adminId))
				.header(HttpHeaders.AUTHORIZATION, admin.bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].username").value("itadmin"))
			.andExpect(jsonPath("$.items[0].entityId").value(String.valueOf(ent)))
			.andExpect(jsonPath("$.totalItems").isNumber());
		mvc.perform(get("/api/admin/audit-logs").param("action", "LOGIN_SUCCESS").header(HttpHeaders.AUTHORIZATION, admin.bearer()))
			.andExpect(jsonPath("$.items[*].action", hasItem("LOGIN_SUCCESS")));
	}

}
