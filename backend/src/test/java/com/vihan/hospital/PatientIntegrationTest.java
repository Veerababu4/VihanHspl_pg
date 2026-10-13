package com.vihan.hospital;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.Random;
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
 * M7 patients end to end (DESIGN.md §5.7, §10.7, §19.1, §19.5): Aadhaar is validated, stored
 * encrypted, searchable by hash and never returned; duplicates are blocked or warned; views, edits
 * and Aadhaar searches are audited; doctors may look but not edit; a UHID on a booking links it.
 * Every test uses its own random mobile and Aadhaar, because the database is shared.
 */
@VihanIntegrationTest
class PatientIntegrationTest {

	private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
	private static final String PATIENTS = "/api/staff/patients";

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate jdbc;

	String desk;
	String doctor;

	@BeforeEach
	void setUp() throws Exception {
		desk = TestLogin.bearer(mvc, "reception.hzn");
		doctor = TestLogin.bearer(mvc, "doctor003");
	}

	// ---- helpers ----

	private static String newMobile() {
		return "9" + String.format("%09d", ThreadLocalRandom.current().nextInt(1_000_000_000));
	}

	/** A random checksum-valid (fictional) Aadhaar number, made the same way as the sample data. */
	private static String newAadhaar() {
		return com.vihan.hospital.patient.SamplePatientSeederAccess.fictionalAadhaar(new Random(ThreadLocalRandom.current().nextLong()));
	}

	private ResultActions register(String json) throws Exception {
		return mvc.perform(post(PATIENTS).header(HttpHeaders.AUTHORIZATION, desk).contentType(MediaType.APPLICATION_JSON).content(json));
	}

	private long registerId(String json) throws Exception {
		String body = register(json).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		return ((Number) JsonPath.read(body, "$.id")).longValue();
	}

	private long count(String sql, Object... args) {
		return jdbc.queryForObject(sql, Long.class, args);
	}

	// ---- registration and Aadhaar (FR-PT1, §10.7) ----

	@Test
	void aadhaarIsStoredEncryptedAndOnlyEverShownMasked() throws Exception {
		String aadhaar = newAadhaar();
		String spaced = aadhaar.substring(0, 4) + " " + aadhaar.substring(4, 8) + " " + aadhaar.substring(8);
		String body = register("""
				{"fullName":"Kavya Encrypted","age":29,"gender":"F","mobile":"%s","consent":true,"aadhaar":"%s",
				 "altMobile":"%s","address":"1-2, Main Road","city":"Huzurnagar","bloodGroup":"AB+",
				 "emergencyName":"Ramu","emergencyPhone":"%s"}""".formatted(newMobile(), spaced, newMobile(), newMobile()))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.aadhaarMasked").value("XXXX-XXXX-" + aadhaar.substring(8)))
			.andExpect(jsonPath("$.registeredBranchCode").value("HZN"))
			.andReturn().getResponse().getContentAsString();
		assertThat(body).doesNotContain(aadhaar);
		long id = ((Number) JsonPath.read(body, "$.id")).longValue();

		var row = jdbc.queryForMap("SELECT aadhaar_enc, aadhaar_hash, aadhaar_last4, blood_group FROM patient WHERE patient_id = ?", id);
		assertThat((String) row.get("AADHAAR_ENC")).isNotBlank().doesNotContain(aadhaar);
		assertThat((String) row.get("AADHAAR_HASH")).hasSize(64);
		assertThat(row.get("AADHAAR_LAST4")).isEqualTo(aadhaar.substring(8));
		assertThat(row.get("BLOOD_GROUP")).isEqualTo("AB+");

		String profile = mvc.perform(get(PATIENTS + "/{id}", id).header(HttpHeaders.AUTHORIZATION, desk))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.aadhaarMasked").value("XXXX-XXXX-" + aadhaar.substring(8)))
			.andExpect(jsonPath("$.city").value("Huzurnagar"))
			.andExpect(jsonPath("$.registeredBranchCity").value("Huzurnagar"))
			.andReturn().getResponse().getContentAsString();
		assertThat(profile).doesNotContain(aadhaar).doesNotContain("aadhaarEnc").doesNotContain("aadhaarHash");
		assertThat(count("SELECT COUNT(*) FROM audit_log WHERE STRPOS(COALESCE(details, ' '), ?) > 0", aadhaar)).isZero();
	}

	@Test
	void invalidAadhaarIsRejectedWithoutEchoingIt() throws Exception {
		String wrong = "234123412345";   // checksum fails
		String body = register("""
				{"fullName":"Wrong Number","age":40,"gender":"M","mobile":"%s","consent":true,"aadhaar":"%s"}"""
				.formatted(newMobile(), wrong))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.fieldErrors[0].field").value("aadhaar"))
			.andReturn().getResponse().getContentAsString();
		assertThat(body).doesNotContain(wrong);
	}

	// ---- duplicates (FR-PT2) ----

	@Test
	void theSameAadhaarIsBlockedAndASimilarNameOnTheSameMobileIsAWarning() throws Exception {
		String mobile = newMobile();
		String aadhaar = newAadhaar();
		registerId("""
				{"fullName":"Ravi Kumar Duplicate","age":40,"gender":"M","mobile":"%s","consent":true,"aadhaar":"%s"}"""
				.formatted(mobile, aadhaar));

		register("""
				{"fullName":"Someone Else","age":30,"gender":"F","mobile":"%s","consent":true,"aadhaar":"%s"}"""
				.formatted(newMobile(), aadhaar))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("AADHAAR_EXISTS"));

		String similar = """
				{"fullName":"K. Ravi Kumar Duplicate","age":41,"gender":"M","mobile":"%s","consent":true%s}""";
		register(similar.formatted(mobile, "")).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("POSSIBLE_DUPLICATE"));
		mvc.perform(post(PATIENTS + "/duplicates").header(HttpHeaders.AUTHORIZATION, desk).contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"K. Ravi Kumar Duplicate\",\"mobile\":\"%s\",\"aadhaar\":\"%s\"}".formatted(mobile, aadhaar)))
			.andExpect(jsonPath("$.aadhaarMatch.fullName").value("Ravi Kumar Duplicate"))
			.andExpect(jsonPath("$.similar", hasSize(1)));
		register(similar.formatted(mobile, ",\"confirmDuplicate\":true")).andExpect(status().isCreated());

		// A family member on the same phone is not a duplicate.
		register("""
				{"fullName":"Sita Kumar Duplicate","age":38,"gender":"F","mobile":"%s","consent":true}""".formatted(mobile))
			.andExpect(status().isCreated());
	}

	// ---- search and view (FR-PT3, FR-PT4) ----

	@Test
	void searchByUhidMobileNameOrAadhaar() throws Exception {
		String mobile = newMobile();
		String aadhaar = newAadhaar();
		String name = "Findme " + UUID.randomUUID().toString().substring(0, 6).replaceAll("\\d", "x");
		String body = register("""
				{"fullName":"%s","age":50,"gender":"M","mobile":"%s","consent":true,"aadhaar":"%s"}""".formatted(name, mobile, aadhaar))
			.andReturn().getResponse().getContentAsString();
		String uhid = JsonPath.read(body, "$.uhid");

		mvc.perform(get(PATIENTS).header(HttpHeaders.AUTHORIZATION, desk).param("uhid", uhid.replace("-", "").toLowerCase()))
			.andExpect(jsonPath("$", hasSize(1))).andExpect(jsonPath("$[0].uhid").value(uhid));
		mvc.perform(get(PATIENTS).header(HttpHeaders.AUTHORIZATION, desk).param("mobile", "+91 " + mobile))
			.andExpect(jsonPath("$[0].uhid").value(uhid));
		mvc.perform(get(PATIENTS).header(HttpHeaders.AUTHORIZATION, doctor).param("name", name.substring(2).toLowerCase()))
			.andExpect(jsonPath("$", hasSize(1)));
		mvc.perform(get(PATIENTS).header(HttpHeaders.AUTHORIZATION, desk))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SEARCH_REQUIRED"));

		long audits = count("SELECT COUNT(*) FROM audit_log WHERE action = 'PATIENT_SEARCHED'");
		mvc.perform(post(PATIENTS + "/search-aadhaar").header(HttpHeaders.AUTHORIZATION, desk).contentType(MediaType.APPLICATION_JSON)
				.content("{\"aadhaar\":\"%s\"}".formatted(aadhaar.substring(0, 4) + "-" + aadhaar.substring(4, 8) + "-" + aadhaar.substring(8))))
			.andExpect(jsonPath("$", hasSize(1))).andExpect(jsonPath("$[0].uhid").value(uhid));
		assertThat(count("SELECT COUNT(*) FROM audit_log WHERE action = 'PATIENT_SEARCHED'")).isEqualTo(audits + 1);
	}

	@Test
	void profileShowsUpcomingBookingsAndVisitsAndEveryViewIsAudited() throws Exception {
		long id = registerId("""
				{"fullName":"Profile Viewer","age":60,"gender":"M","mobile":"%s","consent":true}""".formatted(newMobile()));
		long doctorId = jdbc.queryForObject("SELECT MIN(doctor_id) FROM doctor WHERE is_active = TRUE", Long.class);
		long branchId = jdbc.queryForObject("SELECT MIN(branch_id) FROM doctor_branch WHERE doctor_id = ?", Long.class, doctorId);
		LocalDate today = LocalDate.now(IST);
		jdbc.update("""
				INSERT INTO appointment (booking_ref, doctor_id, branch_id, appt_date, patient_id, patient_name, patient_mobile,
				  channel, status, consent_at)
				VALUES (?, ?, ?, ?, ?, 'Profile Viewer', '9000000000', 'PHONE', 'BOOKED', LOCALTIMESTAMP)""",
				"VMH-PRF-" + id, doctorId, branchId, today.plusDays(3), id);
		jdbc.update("INSERT INTO visit (patient_id, doctor_id, branch_id, visit_date, visit_type, status) VALUES (?, ?, ?, ?, 'OP', 'COMPLETED')",
				id, doctorId, branchId, today.minusDays(10));

		long views = count("SELECT COUNT(*) FROM audit_log WHERE action = 'PATIENT_VIEWED' AND entity_id = ?", String.valueOf(id));
		mvc.perform(get(PATIENTS + "/{id}", id).header(HttpHeaders.AUTHORIZATION, doctor))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.upcoming", hasSize(1)))
			.andExpect(jsonPath("$.upcoming[0].bookingRef").value("VMH-PRF-" + id));
		mvc.perform(get(PATIENTS + "/{id}/visits", id).header(HttpHeaders.AUTHORIZATION, desk))
			.andExpect(jsonPath("$", hasSize(1)))
			.andExpect(jsonPath("$[0].type").value("OP"))
			.andExpect(jsonPath("$[0].status").value("COMPLETED"))
			.andExpect(jsonPath("$[0].diagnosis").doesNotExist());   // non-clinical list only
		assertThat(count("SELECT COUNT(*) FROM audit_log WHERE action = 'PATIENT_VIEWED' AND entity_id = ?", String.valueOf(id)))
			.isEqualTo(views + 1);
		mvc.perform(get(PATIENTS + "/{id}", 999_999_999).header(HttpHeaders.AUTHORIZATION, desk)).andExpect(status().isNotFound());
	}

	// ---- edit (FR-PT5) ----

	@Test
	void editKeepsAadhaarUnlessReplacedOrRemovedAndAuditsFieldNamesOnly() throws Exception {
		String mobile = newMobile();
		String aadhaar = newAadhaar();
		long id = registerId("""
				{"fullName":"Edit Me","age":30,"gender":"F","mobile":"%s","consent":true,"aadhaar":"%s"}""".formatted(mobile, aadhaar));
		LocalDate dob = jdbc.queryForObject("SELECT date_of_birth FROM patient WHERE patient_id = ?", LocalDate.class, id);
		String edit = """
				{"fullName":"Edit Me","age":30,"gender":"F","mobile":"%s","city":"Kodad","address":"Secret Street 5"%s}""";

		mvc.perform(put(PATIENTS + "/{id}", id).header(HttpHeaders.AUTHORIZATION, desk).contentType(MediaType.APPLICATION_JSON)
				.content(edit.formatted(mobile, "")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.aadhaarMasked").value("XXXX-XXXX-" + aadhaar.substring(8)))   // kept
			.andExpect(jsonPath("$.city").value("Kodad"))
			.andExpect(jsonPath("$.age").value(30));
		assertThat(jdbc.queryForObject("SELECT date_of_birth FROM patient WHERE patient_id = ?", LocalDate.class, id))
			.isEqualTo(dob);   // same age: the estimated birth date doesn't move
		String details = jdbc.queryForObject("""
				SELECT details FROM audit_log WHERE action = 'PATIENT_UPDATED' AND entity_id = ? ORDER BY audit_id DESC FETCH FIRST 1 ROW ONLY""",
				String.class, String.valueOf(id));
		assertThat(details).contains("address", "city").doesNotContain("Secret").doesNotContain("Kodad");

		mvc.perform(put(PATIENTS + "/{id}", id).header(HttpHeaders.AUTHORIZATION, desk).contentType(MediaType.APPLICATION_JSON)
				.content(edit.formatted(mobile, ",\"removeAadhaar\":true")))
			.andExpect(jsonPath("$.aadhaarMasked").value(nullValue()));
		assertThat(count("SELECT COUNT(*) FROM patient WHERE patient_id = ? AND aadhaar_hash IS NULL AND aadhaar_enc IS NULL", id)).isEqualTo(1);

		mvc.perform(put(PATIENTS + "/{id}", id).header(HttpHeaders.AUTHORIZATION, doctor).contentType(MediaType.APPLICATION_JSON)
				.content(edit.formatted(mobile, "")))
			.andExpect(status().isForbidden());
	}

	// ---- booking with a UHID (§19.5) ----

	@Test
	void aBookingWithAUhidIsLinkedOnlyWhenTheMobileMatches() throws Exception {
		String mobile = newMobile();
		String body = register("""
				{"fullName":"Returning Patient","age":45,"gender":"M","mobile":"%s","consent":true}""".formatted(mobile))
			.andReturn().getResponse().getContentAsString();
		long patientId = ((Number) JsonPath.read(body, "$.id")).longValue();
		String uhid = JsonPath.read(body, "$.uhid");

		long doctorId = jdbc.queryForObject("SELECT doctor_id FROM app_user WHERE username = 'doctor003'", Long.class);
		LocalDate day = LocalDate.now(IST).plusDays(2).with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
		long branchId = jdbc.queryForObject("SELECT MIN(branch_id) FROM doctor_branch WHERE doctor_id = ?", Long.class, doctorId);
		long slot1 = newSlot(doctorId, branchId, day, "06:01");
		long slot2 = newSlot(doctorId, branchId, day, "06:21");
		long slot3 = newSlot(doctorId, branchId, day, "06:41");

		String staffBooking = """
				{"slotId":%d,"patientName":"Returning Patient","patientAge":45,"patientGender":"M","mobile":"%s",
				 "channel":"COUNTER","consent":true,"uhid":"%s"}""";
		mvc.perform(post("/api/staff/appointments").header(HttpHeaders.AUTHORIZATION, desk).contentType(MediaType.APPLICATION_JSON)
				.content(staffBooking.formatted(slot1, mobile, uhid.toLowerCase())))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.uhid").value(uhid));
		assertThat(count("SELECT COUNT(*) FROM appointment WHERE slot_id = ? AND patient_id = ?", slot1, patientId)).isEqualTo(1);

		mvc.perform(post("/api/staff/appointments").header(HttpHeaders.AUTHORIZATION, desk).contentType(MediaType.APPLICATION_JSON)
				.content(staffBooking.formatted(slot2, newMobile(), uhid)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("UHID_MISMATCH"));
		assertThat(jdbc.queryForObject("SELECT status FROM slot WHERE slot_id = ?", String.class, slot2)).isEqualTo("OPEN");

		// Online, a wrong mobile is ignored silently: the booking is made, just not linked.
		String other = newMobile();
		mvc.perform(post("/api/public/appointments").with(r -> { r.setRemoteAddr("10.77." + ThreadLocalRandom.current().nextInt(256) + ".9"); return r; })
				.contentType(MediaType.APPLICATION_JSON).content("""
						{"slotId":%d,"patientName":"Someone","patientAge":30,"patientGender":"F","mobile":"%s",
						 "consent":true,"captchaToken":"%s","uhid":"%s"}""".formatted(slot3, other, TestCaptchaConfiguration.PASS, uhid)))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.uhid").value(nullValue()));
		assertThat(count("SELECT COUNT(*) FROM appointment WHERE slot_id = ? AND patient_id IS NULL", slot3)).isEqualTo(1);

		mvc.perform(get(PATIENTS + "/{id}", patientId).header(HttpHeaders.AUTHORIZATION, desk))
			.andExpect(jsonPath("$.upcoming", hasSize(greaterThanOrEqualTo(1))))
			.andExpect(jsonPath("$.upcoming[0].doctorName", containsString("Dr.")));
	}

	/** An open slot at an odd time (no clash with generated slots), far enough ahead for online booking. */
	private long newSlot(long doctorId, long branchId, LocalDate day, String time) {
		var start = day.atTime(LocalTime.parse(time)).withSecond(13);
		jdbc.update("""
				INSERT INTO slot (doctor_id, branch_id, slot_date, start_at, end_at, token_no, status, source)
				VALUES (?, ?, ?, ?, ?, 95, 'OPEN', 'MANUAL')""", doctorId, branchId, day, start, start.plusMinutes(20));
		return jdbc.queryForObject("SELECT slot_id FROM slot WHERE doctor_id = ? AND start_at = ?", Long.class, doctorId, start);
	}

}
