package com.vihan.hospital;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;

/**
 * M8 clinical end to end (DESIGN.md §5.8, §11.6, §12.3): only doctors reach /api/clinical; the
 * treating doctor records and completes an open visit; any doctor reads the full history; views
 * and changes are audited without clinical text. Visits are opened as walk-ins (M6), so each test
 * has its own patient and visit.
 */
@VihanIntegrationTest
class ClinicalIntegrationTest {

	private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
	private static final String CLINICAL = "/api/clinical";

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate jdbc;

	String desk;
	String doctorA;
	String doctorB;
	long doctorAId;
	long doctorBId;

	@BeforeEach
	void setUp() throws Exception {
		desk = TestLogin.bearer(mvc, "reception.mlg");
		doctorA = TestLogin.bearer(mvc, "doctor004");
		doctorB = TestLogin.bearer(mvc, "doctor005");
		doctorAId = id("SELECT doctor_id FROM app_user WHERE username = 'doctor004'");
		doctorBId = id("SELECT doctor_id FROM app_user WHERE username = 'doctor005'");
	}

	// ---- helpers ----

	private long id(String sql, Object... args) {
		return jdbc.queryForObject(sql, Long.class, args);
	}

	private long newPatient(String name) throws Exception {
		String mobile = "9" + String.format("%09d", ThreadLocalRandom.current().nextInt(1_000_000_000));
		String body = mvc.perform(post("/api/staff/patients").header(HttpHeaders.AUTHORIZATION, desk).contentType(MediaType.APPLICATION_JSON)
				.content("{\"fullName\":\"%s\",\"age\":50,\"gender\":\"F\",\"mobile\":\"%s\",\"consent\":true}".formatted(name, mobile)))
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString();
		return ((Number) JsonPath.read(body, "$.id")).longValue();
	}

	/** A walk-in for the doctor today: checked in, with an open visit. Returns [appointmentId, visitId]. */
	private long[] walkIn(long patientId, long doctorId) throws Exception {
		String branch = jdbc.queryForObject("""
				SELECT b.code FROM branch b WHERE b.branch_id = (SELECT MIN(branch_id) FROM doctor_branch WHERE doctor_id = ?)""",
				String.class, doctorId);
		String body = mvc.perform(post("/api/staff/appointments/walk-in").header(HttpHeaders.AUTHORIZATION, desk)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"patientId\":%d,\"doctorId\":%d,\"branch\":\"%s\",\"reason\":\"Fever\"}".formatted(patientId, doctorId, branch)))
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString();
		return new long[] { ((Number) JsonPath.read(body, "$.id")).longValue(), ((Number) JsonPath.read(body, "$.visitId")).longValue() };
	}

	private ResultActions saveVisit(String auth, long visitId, String json) throws Exception {
		return mvc.perform(put(CLINICAL + "/visits/{id}", visitId).header(HttpHeaders.AUTHORIZATION, auth)
			.contentType(MediaType.APPLICATION_JSON).content(json));
	}

	private static String unique(String prefix) {
		return prefix + " " + UUID.randomUUID().toString().substring(0, 8);
	}

	// ---- access (§4, §12.3) ----

	@Test
	void onlyDoctorsReachTheClinicalApi() throws Exception {
		long[] visit = walkIn(newPatient("Access Check"), doctorAId);
		for (String user : List.of("reception.mlg", "itadmin")) {
			String auth = TestLogin.bearer(mvc, user);
			mvc.perform(get(CLINICAL + "/queue").header(HttpHeaders.AUTHORIZATION, auth)).andExpect(status().isForbidden());
			mvc.perform(get(CLINICAL + "/visits/{id}", visit[1]).header(HttpHeaders.AUTHORIZATION, auth)).andExpect(status().isForbidden());
		}
		mvc.perform(get(CLINICAL + "/queue")).andExpect(status().isUnauthorized());
	}

	// ---- queue (FR-C1) ----

	@Test
	void theQueueHasTheDoctorsOwnCheckedInPatientsInTokenOrder() throws Exception {
		long[] first = walkIn(newPatient(unique("Queue First")), doctorAId);
		long[] second = walkIn(newPatient(unique("Queue Second")), doctorAId);

		String queue = mvc.perform(get(CLINICAL + "/queue").header(HttpHeaders.AUTHORIZATION, doctorA))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.date").value(LocalDate.now(IST).toString()))
			.andReturn().getResponse().getContentAsString();
		List<Integer> visitIds = JsonPath.read(queue, "$.waiting[*].visitId");
		assertThat(visitIds).contains((int) first[1], (int) second[1]);
		assertThat(visitIds.indexOf((int) first[1])).isLessThan(visitIds.indexOf((int) second[1]));   // token order

		String other = mvc.perform(get(CLINICAL + "/queue").header(HttpHeaders.AUTHORIZATION, doctorB))
			.andReturn().getResponse().getContentAsString();
		List<Integer> otherIds = JsonPath.read(other, "$.waiting[*].visitId");
		assertThat(otherIds).doesNotContain((int) first[1], (int) second[1]);
	}

	// ---- visit (FR-C2, FR-C3) ----

	@Test
	void theTreatingDoctorRecordsTheVisitAndItsPrescription() throws Exception {
		long[] visit = walkIn(newPatient(unique("Visit Record")), doctorAId);
		String diagnosis = unique("Viral fever");
		String json = """
				{"bpSystolic":130,"bpDiastolic":85,"pulse":78,"temperatureF":100.4,"spo2":97,"weightKg":62.5,"heightCm":158,
				 "chiefComplaint":"Fever for 3 days","diagnosis":"%s","clinicalNotes":"Throat congested.","advice":"Rest",
				 "followUpDate":"%s","prescription":[
				   {"medicineName":"Paracetamol","dosage":"650 mg","frequency":"1-1-1","duration":"3 days","instructions":"after food"},
				   {"medicineName":"ORS","frequency":"as needed"}]}""".formatted(diagnosis, LocalDate.now(IST).plusDays(7));

		saveVisit(doctorA, visit[1], json)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.canEdit").value(true))
			.andExpect(jsonPath("$.clinical.diagnosis").value(diagnosis))
			.andExpect(jsonPath("$.clinical.temperatureF").value(100.4))
			.andExpect(jsonPath("$.prescription", hasSize(2)))
			.andExpect(jsonPath("$.prescription[0].medicineName").value("Paracetamol"))
			.andExpect(jsonPath("$.doctor.registrationNo").isNotEmpty())
			.andExpect(jsonPath("$.branch.address").isNotEmpty());

		// Saving again replaces the prescription.
		saveVisit(doctorA, visit[1], "{\"diagnosis\":\"%s\",\"prescription\":[{\"medicineName\":\"Cetirizine\"}]}".formatted(diagnosis))
			.andExpect(jsonPath("$.prescription", hasSize(1)))
			.andExpect(jsonPath("$.clinical.pulse").value(nullValue()));

		saveVisit(doctorA, visit[1], "{\"pulse\":780,\"prescription\":[]}")
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors[0].field").value("pulse"));
		saveVisit(doctorA, visit[1], "{\"followUpDate\":\"2020-01-01\",\"prescription\":[]}")
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BAD_FOLLOW_UP"));
		saveVisit(doctorA, visit[1], "{\"prescription\":[{\"medicineName\":\"\"}]}").andExpect(status().isBadRequest());

		// Another doctor may read (and is audited) but not write.
		saveVisit(doctorB, visit[1], "{\"prescription\":[]}").andExpect(status().isForbidden());
		mvc.perform(get(CLINICAL + "/visits/{id}", visit[1]).header(HttpHeaders.AUTHORIZATION, doctorB))
			.andExpect(status().isOk()).andExpect(jsonPath("$.canEdit").value(false));
		assertThat(id("SELECT COUNT(*) FROM audit_log WHERE action = 'VISIT_VIEWED' AND entity_id = ?", String.valueOf(visit[1])))
			.isEqualTo(1);
		assertThat(id("SELECT COUNT(*) FROM audit_log WHERE STRPOS(COALESCE(details, ' '), ?) > 0", diagnosis)).isZero();
	}

	@Test
	void completingClosesTheVisitAndTheAppointment() throws Exception {
		long[] visit = walkIn(newPatient(unique("Visit Complete")), doctorAId);
		saveVisit(doctorA, visit[1], "{\"diagnosis\":\"Gastritis\",\"prescription\":[{\"medicineName\":\"Pantoprazole\"}]}")
			.andExpect(status().isOk());

		mvc.perform(post(CLINICAL + "/visits/{id}/complete", visit[1]).header(HttpHeaders.AUTHORIZATION, doctorB))
			.andExpect(status().isForbidden());
		mvc.perform(post(CLINICAL + "/visits/{id}/complete", visit[1]).header(HttpHeaders.AUTHORIZATION, doctorA))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("COMPLETED"))
			.andExpect(jsonPath("$.canEdit").value(false));
		assertThat(jdbc.queryForObject("SELECT status FROM appointment WHERE appointment_id = ?", String.class, visit[0]))
			.isEqualTo("COMPLETED");

		saveVisit(doctorA, visit[1], "{\"prescription\":[]}")
			.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VISIT_COMPLETED"));
		String queue = mvc.perform(get(CLINICAL + "/queue").header(HttpHeaders.AUTHORIZATION, doctorA))
			.andReturn().getResponse().getContentAsString();
		assertThat(JsonPath.<List<Integer>>read(queue, "$.done[*].visitId")).contains((int) visit[1]);
		assertThat(JsonPath.<List<Integer>>read(queue, "$.waiting[*].visitId")).doesNotContain((int) visit[1]);
	}

	// ---- history and medical profile (FR-C4) ----

	@Test
	void anyDoctorSeesTheFullHistoryButReceptionOnlyTheVisitList() throws Exception {
		long patientId = newPatient(unique("History Patient"));
		long[] byA = walkIn(patientId, doctorAId);
		long[] byB = walkIn(patientId, doctorBId);
		String diagnosisA = unique("Hypertension");
		saveVisit(doctorA, byA[1], "{\"diagnosis\":\"%s\",\"prescription\":[{\"medicineName\":\"Amlodipine\"}]}".formatted(diagnosisA))
			.andExpect(status().isOk());
		saveVisit(doctorB, byB[1], "{\"diagnosis\":\"Diabetes\",\"prescription\":[]}").andExpect(status().isOk());

		mvc.perform(put(CLINICAL + "/patients/{id}/medical-profile", patientId).header(HttpHeaders.AUTHORIZATION, doctorB)
				.contentType(MediaType.APPLICATION_JSON).content("{\"allergies\":\"Penicillin\",\"chronicConditions\":\"Asthma\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.allergies").value("Penicillin"));
		String profileAudit = jdbc.queryForObject("""
				SELECT details FROM audit_log WHERE action = 'MEDICAL_PROFILE_UPDATED' AND entity_id = ?
				ORDER BY audit_id DESC FETCH FIRST 1 ROW ONLY""", String.class, String.valueOf(patientId));
		assertThat(profileAudit).contains("allergies", "chronicConditions").doesNotContain("Penicillin");

		long views = id("SELECT COUNT(*) FROM audit_log WHERE action = 'HISTORY_VIEWED' AND entity_id = ?", String.valueOf(patientId));
		mvc.perform(get(CLINICAL + "/patients/{id}/history", patientId).header(HttpHeaders.AUTHORIZATION, doctorA))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.medicalProfile.allergies").value("Penicillin"))
			.andExpect(jsonPath("$.visits", hasSize(2)))
			.andExpect(jsonPath("$.visits[?(@.id == %d)].clinical.diagnosis".formatted(byA[1])).value(diagnosisA))
			.andExpect(jsonPath("$.visits[?(@.id == %d)].prescription[0].medicineName".formatted(byA[1])).value("Amlodipine"));
		assertThat(id("SELECT COUNT(*) FROM audit_log WHERE action = 'HISTORY_VIEWED' AND entity_id = ?", String.valueOf(patientId)))
			.isEqualTo(views + 1);

		// The visit screen warns about the allergy.
		mvc.perform(get(CLINICAL + "/visits/{id}", byA[1]).header(HttpHeaders.AUTHORIZATION, doctorA))
			.andExpect(jsonPath("$.allergies").value("Penicillin"));

		// Reception: the non-clinical visit list only.
		String list = mvc.perform(get("/api/staff/patients/{id}/visits", patientId).header(HttpHeaders.AUTHORIZATION, desk))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$", hasSize(2)))
			.andReturn().getResponse().getContentAsString();
		assertThat(list).doesNotContain(diagnosisA).doesNotContain("Amlodipine");
		mvc.perform(get(CLINICAL + "/patients/{id}/history", patientId).header(HttpHeaders.AUTHORIZATION, desk))
			.andExpect(status().isForbidden());
	}

}
