package com.vihan.hospital;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;
import com.vihan.hospital.appointment.BookingRefs;
import com.vihan.hospital.appointment.ReceptionService;

/**
 * M6 reception, dashboard and reports end to end (DESIGN.md §5.5, §5.6, §10.4, §19.5). Each test
 * creates its own doctor at Miryalaguda and Kodad with a Wednesday 10:00-12:00 session at
 * Miryalaguda (6 slots). Check-in needs a booking for <em>today</em> at any time of day, so those
 * are inserted directly (start times with 7 seconds never clash with generated slots).
 */
@VihanIntegrationTest
class ReceptionIntegrationTest {

	private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	ReceptionService reception;

	String admin;
	String desk;
	long mlg;
	long kdd;
	long doctorId;
	LocalDate today;
	LocalDate wednesday;

	@BeforeEach
	void setUp() throws Exception {
		admin = TestLogin.bearer(mvc, "itadmin");
		desk = TestLogin.bearer(mvc, "reception.mlg");
		mlg = id("SELECT branch_id FROM branch WHERE code = 'MLG'");
		kdd = id("SELECT branch_id FROM branch WHERE code = 'KDD'");
		today = LocalDate.now(IST);
		wednesday = today.plusDays(1).with(TemporalAdjusters.next(DayOfWeek.WEDNESDAY));
		doctorId = createDoctor();
		mvc.perform(post("/api/staff/doctors/{id}/schedules", doctorId).header(HttpHeaders.AUTHORIZATION, admin)
				.contentType(MediaType.APPLICATION_JSON).content("""
						{"branchId":%d,"dayOfWeek":3,"startTime":"10:00","endTime":"12:00","slotMinutes":20,"effectiveFrom":"2026-01-01"}"""
					.formatted(mlg)))
			.andExpect(status().isCreated());
	}

	@AfterEach
	void deactivateTestDoctor() {
		jdbc.update("UPDATE doctor SET is_active = FALSE WHERE doctor_id = ?", doctorId);
	}

	// ---- helpers ----

	private static String newMobile() {
		return "9" + String.format("%09d", ThreadLocalRandom.current().nextInt(1_000_000_000));
	}

	private long id(String sql, Object... args) {
		return jdbc.queryForObject(sql, Long.class, args);
	}

	private long createDoctor() throws Exception {
		long speciality = id("SELECT speciality_id FROM speciality WHERE name = 'ENT'");
		String body = mvc.perform(post("/api/admin/doctors").header(HttpHeaders.AUTHORIZATION, admin)
				.contentType(MediaType.APPLICATION_JSON).content("""
						{"fullName":"Dr. Reception Test","specialityId":%d,"qualification":"MBBS, MS (ENT)","practiceStartYear":2012,
						 "registrationNo":"RECP-%s","branchIds":[%d,%d],"consultationFee":400,"active":true}"""
					.formatted(speciality, UUID.randomUUID().toString().substring(0, 8), mlg, kdd)))
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString();
		return ((Number) JsonPath.read(body, "$.id")).longValue();
	}

	private long wednesdaySlot(String time) {
		return id("SELECT slot_id FROM slot WHERE doctor_id = ? AND start_at = ?", doctorId,
				wednesday.atTime(LocalTime.parse(time)));
	}

	/** A booking made at the counter for one of the Wednesday slots; returns the appointment id. */
	private long counterBooking(long slotId, String mobile) throws Exception {
		String body = mvc.perform(post("/api/staff/appointments").header(HttpHeaders.AUTHORIZATION, desk)
				.contentType(MediaType.APPLICATION_JSON).content("""
						{"slotId":%d,"patientName":"Lakshmi Devi","patientAge":35,"patientGender":"F","mobile":"%s",
						 "channel":"COUNTER","consent":true}""".formatted(slotId, mobile)))
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString();
		return id("SELECT appointment_id FROM appointment WHERE booking_ref = ?", (String) JsonPath.read(body, "$.bookingRef"));
	}

	/** A BOOKED appointment for today at {@code time} (see class comment). */
	private long todayBooking(long doctor, long branch, String time, String name, String mobile) {
		LocalDateTime start = today.atTime(LocalTime.parse(time)).withSecond(7);
		jdbc.update("""
				INSERT INTO slot (doctor_id, branch_id, slot_date, start_at, end_at, token_no, status, source)
				VALUES (?, ?, ?, ?, ?, 90, 'BOOKED', 'MANUAL')""", doctor, branch, today, start, start.plusMinutes(20));
		long slotId = id("SELECT slot_id FROM slot WHERE doctor_id = ? AND start_at = ?", doctor, start);
		String ref = BookingRefs.generate(today);
		jdbc.update("""
				INSERT INTO appointment (booking_ref, slot_id, doctor_id, branch_id, appt_date, start_at, token_no,
				  patient_name, patient_mobile, patient_age, patient_gender, channel, status, consent_at)
				VALUES (?, ?, ?, ?, ?, ?, 90, ?, ?, 40, 'M', 'ONLINE', 'BOOKED', LOCALTIMESTAMP)""",
				ref, slotId, doctor, branch, today, start, name, mobile);
		return id("SELECT appointment_id FROM appointment WHERE booking_ref = ?", ref);
	}

	private long registerPatient(String name, String mobile) throws Exception {
		String body = mvc.perform(post("/api/staff/patients").header(HttpHeaders.AUTHORIZATION, desk)
				.contentType(MediaType.APPLICATION_JSON).content("""
						{"fullName":"%s","age":40,"gender":"M","mobile":"%s","consent":true}""".formatted(name, mobile)))
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString();
		return ((Number) JsonPath.read(body, "$.id")).longValue();
	}

	private ResultActions setStatus(String auth, long appointmentId, String json) throws Exception {
		return mvc.perform(patch("/api/staff/appointments/{id}/status", appointmentId).header(HttpHeaders.AUTHORIZATION, auth)
			.contentType(MediaType.APPLICATION_JSON).content(json));
	}

	private ResultActions checkIn(long appointmentId, long patientId) throws Exception {
		return mvc.perform(post("/api/staff/appointments/{id}/check-in", appointmentId).header(HttpHeaders.AUTHORIZATION, desk)
			.contentType(MediaType.APPLICATION_JSON).content("{\"patientId\":%d}".formatted(patientId)));
	}

	/** The first report row whose cell {@code column} equals {@code value}. */
	private static List<Object> reportRow(String reportJson, int column, String value) {
		List<List<Object>> rows = JsonPath.read(reportJson, "$.rows");
		return rows.stream().filter(r -> value.equals(r.get(column))).findFirst()
			.orElseThrow(() -> new AssertionError("no row with " + value + " in " + reportJson));
	}

	private String slotStatus(long slotId) {
		return jdbc.queryForObject("SELECT status FROM slot WHERE slot_id = ?", String.class, slotId);
	}

	// ---- check-in and patient linking (FR-R4, §19.5) ----

	@Test
	void checkInLinksARegisteredPatientAndOpensAVisit() throws Exception {
		String mobile = newMobile();
		long appt = todayBooking(doctorId, mlg, "00:00", "Ravi Checkintest", mobile);

		mvc.perform(get("/api/staff/appointments/{id}/patient-matches", appt).header(HttpHeaders.AUTHORIZATION, desk))
			.andExpect(jsonPath("$", hasSize(0)));
		long patientId = registerPatient("Ravi  Checkintest", mobile);
		mvc.perform(get("/api/staff/appointments/{id}/patient-matches", appt).header(HttpHeaders.AUTHORIZATION, desk))
			.andExpect(jsonPath("$", hasSize(1)))
			.andExpect(jsonPath("$[0].patient.uhid", matchesPattern("VMH-\\d{2}-\\d{6}")))
			.andExpect(jsonPath("$[0].sameMobile").value(true))
			.andExpect(jsonPath("$[0].sameName").value(true))
			.andExpect(jsonPath("$[0].ageClose").value(true));

		checkIn(appt, patientId)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("CHECKED_IN"))
			.andExpect(jsonPath("$.patientId").value(patientId))
			.andExpect(jsonPath("$.uhid", startsWith("VMH-")))
			.andExpect(jsonPath("$.actions.checkIn").value(false))
			.andExpect(jsonPath("$.actions.complete").value(false));   // reception doesn't complete visits
		assertThat(jdbc.queryForObject("SELECT status FROM visit WHERE appointment_id = ?", String.class, appt)).isEqualTo("OPEN");
		assertThat(id("SELECT COUNT(*) FROM audit_log WHERE action = 'APPOINTMENT_CHECKED_IN' AND entity_id = ?", String.valueOf(appt)))
			.isEqualTo(1);

		checkIn(appt, patientId).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_TRANSITION"));
		setStatus(desk, appt, "{\"status\":\"COMPLETED\"}").andExpect(status().isForbidden());
		setStatus(admin, appt, "{\"status\":\"COMPLETED\"}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("COMPLETED"));
		assertThat(jdbc.queryForObject("SELECT status FROM visit WHERE appointment_id = ?", String.class, appt)).isEqualTo("COMPLETED");
	}

	@Test
	void futureBookingsCantBeCheckedInOrMarkedNoShowYet() throws Exception {
		long appt = counterBooking(wednesdaySlot("10:00"), newMobile());
		long patientId = registerPatient("Lakshmi Devi", newMobile());

		mvc.perform(get("/api/staff/appointments").header(HttpHeaders.AUTHORIZATION, desk)
				.param("date", wednesday.toString()).param("doctor", String.valueOf(doctorId)))
			.andExpect(jsonPath("$", hasSize(1)))
			.andExpect(jsonPath("$[0].mobile", matchesPattern("\\d{10}")))   // staff see the full number
			.andExpect(jsonPath("$[0].actions.checkIn").value(false))
			.andExpect(jsonPath("$[0].actions.noShow").value(false))
			.andExpect(jsonPath("$[0].actions.cancel").value(true))
			.andExpect(jsonPath("$[0].actions.reschedule").value(true));

		checkIn(appt, patientId).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NOT_TODAY"));
		setStatus(desk, appt, "{\"status\":\"NO_SHOW\"}").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TOO_EARLY"));
		setStatus(desk, appt, "{\"status\":\"CHECKED_IN\"}").andExpect(status().isBadRequest());
		setStatus(desk, appt, "{\"status\":\"CANCELLED\",\"reason\":\"  \"}")
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("REASON_REQUIRED"));
	}

	@Test
	void noShowAfterTheStartTimeAndTheEndOfDayJobCatchesTheRest() throws Exception {
		long appt = todayBooking(doctorId, mlg, "00:01", "Late Patient", newMobile());
		setStatus(desk, appt, "{\"status\":\"NO_SHOW\"}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("NO_SHOW"));
		setStatus(desk, appt, "{\"status\":\"CANCELLED\",\"reason\":\"x\"}").andExpect(status().isConflict());

		long forgotten = todayBooking(doctorId, mlg, "00:02", "Forgotten Patient", newMobile());
		jdbc.update("UPDATE appointment SET appt_date = appt_date - 1 WHERE appointment_id = ?", forgotten);
		assertThat(reception.markNoShows(today.minusDays(1))).isGreaterThanOrEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT status FROM appointment WHERE appointment_id = ?", String.class, forgotten))
			.isEqualTo("NO_SHOW");
	}

	// ---- staff cancel / reschedule (FR-R2, FR-R6) ----

	@Test
	void staffRescheduleDoesNotUseUpThePatientsOwnChanges() throws Exception {
		long from = wednesdaySlot("10:00");
		long to = wednesdaySlot("10:20");
		long appt = counterBooking(from, newMobile());
		String json = "{\"newSlotId\":%d,\"reason\":\"%s\"}";

		mvc.perform(post("/api/staff/appointments/{id}/reschedule", appt).header(HttpHeaders.AUTHORIZATION, desk)
				.contentType(MediaType.APPLICATION_JSON).content(json.formatted(to, "")))
			.andExpect(status().isBadRequest());
		mvc.perform(post("/api/staff/appointments/{id}/reschedule", appt).header(HttpHeaders.AUTHORIZATION, desk)
				.contentType(MediaType.APPLICATION_JSON).content(json.formatted(to, "Doctor in surgery")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.startAt").value(wednesday + "T10:20:00"))
			.andExpect(jsonPath("$.tokenNo").value(2))
			.andExpect(jsonPath("$.rescheduleCount").value(0));
		assertThat(slotStatus(from)).isEqualTo("OPEN");
		assertThat(slotStatus(to)).isEqualTo("BOOKED");

		long other = counterBooking(from, newMobile());
		mvc.perform(post("/api/staff/appointments/{id}/reschedule", other).header(HttpHeaders.AUTHORIZATION, desk)
				.contentType(MediaType.APPLICATION_JSON).content(json.formatted(to, "try")))
			.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SLOT_TAKEN"));
		mvc.perform(post("/api/staff/appointments/{id}/reschedule", other).header(HttpHeaders.AUTHORIZATION, TestLogin.bearer(mvc, "doctor001"))
				.contentType(MediaType.APPLICATION_JSON).content(json.formatted(wednesdaySlot("10:40"), "try")))
			.andExpect(status().isForbidden());
	}

	@Test
	void staffCancelNeedsAReasonAndFreesTheSlot() throws Exception {
		long slot = wednesdaySlot("11:00");
		long appt = counterBooking(slot, newMobile());
		setStatus(desk, appt, "{\"status\":\"CANCELLED\",\"reason\":\"Patient phoned\"}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("CANCELLED"))
			.andExpect(jsonPath("$.cancelledBy").value("STAFF"))
			.andExpect(jsonPath("$.cancelReason").value("Patient phoned"));
		assertThat(slotStatus(slot)).isEqualTo("OPEN");
	}

	// ---- walk-ins (FR-R5) ----

	@Test
	void walkInsGetTheNextTokenAndAreCheckedInAtOnce() throws Exception {
		String mobile = newMobile();
		long patientId = registerPatient("Walk In Patient", mobile);
		mvc.perform(get("/api/staff/patients").header(HttpHeaders.AUTHORIZATION, desk).param("mobile", "+91 " + mobile))
			.andExpect(jsonPath("$", hasSize(1)))
			.andExpect(jsonPath("$[0].id").value(patientId));
		mvc.perform(get("/api/staff/patients").header(HttpHeaders.AUTHORIZATION, desk).param("mobile", "12345"))
			.andExpect(status().isBadRequest());
		String walkIn = "{\"patientId\":%d,\"doctorId\":%d,\"branch\":\"%s\",\"reason\":\"Fever\"}";

		String body = mvc.perform(post("/api/staff/appointments/walk-in").header(HttpHeaders.AUTHORIZATION, desk)
				.contentType(MediaType.APPLICATION_JSON).content(walkIn.formatted(patientId, doctorId, "KDD")))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value("CHECKED_IN"))
			.andExpect(jsonPath("$.channel").value("WALK_IN"))
			.andExpect(jsonPath("$.tokenNo").value(1))
			.andExpect(jsonPath("$.startAt").value(nullValue()))
			.andExpect(jsonPath("$.patientName").value("Walk In Patient"))
			.andReturn().getResponse().getContentAsString();
		mvc.perform(post("/api/staff/appointments/walk-in").header(HttpHeaders.AUTHORIZATION, desk)
				.contentType(MediaType.APPLICATION_JSON).content(walkIn.formatted(patientId, doctorId, "KDD")))
			.andExpect(jsonPath("$.tokenNo").value(2));
		long apptId = ((Number) JsonPath.read(body, "$.id")).longValue();
		assertThat(id("SELECT COUNT(*) FROM visit WHERE appointment_id = ?", apptId)).isEqualTo(1);

		// The patient can still look a walk-in up on the website (it has no time slot).
		mvc.perform(post("/api/public/appointments/lookup").with(r -> { r.setRemoteAddr("10.66.0." + ThreadLocalRandom.current().nextInt(1, 255)); return r; })
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"bookingRef\":\"%s\",\"mobile\":\"%s\"}".formatted(JsonPath.read(body, "$.bookingRef"), mobile)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.canCancel").value(false));

		mvc.perform(post("/api/staff/appointments/walk-in").header(HttpHeaders.AUTHORIZATION, desk)
				.contentType(MediaType.APPLICATION_JSON).content(walkIn.formatted(patientId, doctorId, "HZN")))
			.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DOCTOR_UNAVAILABLE"));
	}

	// ---- who sees what (§4) ----

	@Test
	void doctorsSeeAndChangeOnlyTheirOwnAppointments() throws Exception {
		long ownDoctor = id("SELECT doctor_id FROM app_user WHERE username = 'doctor001'");
		long branch = id("SELECT MIN(branch_id) FROM doctor_branch WHERE doctor_id = ?", ownDoctor);
		long appt = todayBooking(ownDoctor, branch, "00:03", "Doctor Own Patient", newMobile());
		checkIn(appt, registerPatient("Doctor Own Patient", newMobile())).andExpect(status().isOk());

		String own = TestLogin.bearer(mvc, "doctor001");
		String other = TestLogin.bearer(mvc, "doctor002");
		mvc.perform(get("/api/staff/appointments").header(HttpHeaders.AUTHORIZATION, own)
				.param("doctor", String.valueOf(doctorId)))   // ignored: a doctor always gets their own list
			.andExpect(jsonPath("$[?(@.id == %d)].actions.complete".formatted(appt)).value(true));
		mvc.perform(get("/api/staff/appointments").header(HttpHeaders.AUTHORIZATION, other))
			.andExpect(jsonPath("$[?(@.id == %d)]".formatted(appt), hasSize(0)));
		setStatus(other, appt, "{\"status\":\"COMPLETED\"}").andExpect(status().isForbidden());
		setStatus(own, appt, "{\"status\":\"COMPLETED\"}").andExpect(status().isOk());

		mvc.perform(get("/api/staff/appointments/needs-action").header(HttpHeaders.AUTHORIZATION, own))
			.andExpect(status().isForbidden());
		mvc.perform(post("/api/staff/patients").header(HttpHeaders.AUTHORIZATION, own).contentType(MediaType.APPLICATION_JSON)
				.content("{\"fullName\":\"X Y\",\"age\":3,\"gender\":\"F\",\"mobile\":\"9876543210\",\"consent\":true}"))
			.andExpect(status().isForbidden());
	}

	// ---- list (FR-R1) ----

	@Test
	void listSearchesByNameMobileOrReferenceAndFlagsFrequentNoShows() throws Exception {
		String mobile = newMobile();
		for (int i = 1; i <= 3; i++) {
			jdbc.update("""
					INSERT INTO appointment (booking_ref, doctor_id, branch_id, appt_date, patient_name, patient_mobile,
					  channel, status, consent_at)
					VALUES (?, ?, ?, ?, 'Habitual Absentee', ?, 'PHONE', 'NO_SHOW', LOCALTIMESTAMP)""",
					BookingRefs.generate(today), doctorId, mlg, today.minusDays(i), mobile);
		}
		long appt = todayBooking(doctorId, mlg, "00:04", "Habitual Absentee", mobile);
		String ref = jdbc.queryForObject("SELECT booking_ref FROM appointment WHERE appointment_id = ?", String.class, appt);

		mvc.perform(get("/api/staff/appointments").header(HttpHeaders.AUTHORIZATION, desk).param("q", "habitual"))
			.andExpect(jsonPath("$", hasSize(1)))
			.andExpect(jsonPath("$[0].frequentNoShow").value(true));
		mvc.perform(get("/api/staff/appointments").header(HttpHeaders.AUTHORIZATION, desk).param("q", mobile.substring(4)))
			.andExpect(jsonPath("$[0].id").value(appt));
		mvc.perform(get("/api/staff/appointments").header(HttpHeaders.AUTHORIZATION, desk)
				.param("date", wednesday.toString()).param("q", ref.replace("-", "").toLowerCase()))
			.andExpect(jsonPath("$", hasSize(1)))   // a full reference finds the booking on any date
			.andExpect(jsonPath("$[0].bookingRef").value(ref));
		mvc.perform(get("/api/staff/appointments").header(HttpHeaders.AUTHORIZATION, desk)
				.param("branch", "HZN").param("q", "habitual"))
			.andExpect(jsonPath("$", hasSize(0)));
		mvc.perform(get("/api/staff/appointments").header(HttpHeaders.AUTHORIZATION, desk).param("branch", "XYZ"))
			.andExpect(status().isBadRequest());
	}

	// ---- dashboard (FR-D1–D5) ----

	@Test
	void dashboardCountsPerDoctorBranchAndNetworkAndExports() throws Exception {
		counterBooking(wednesdaySlot("10:00"), newMobile());
		long cancelled = counterBooking(wednesdaySlot("10:20"), newMobile());
		setStatus(desk, cancelled, "{\"status\":\"CANCELLED\",\"reason\":\"Changed plans\"}").andExpect(status().isOk());

		String row = "$.doctors[?(@.doctorId == %d)].counts.".formatted(doctorId);
		mvc.perform(get("/api/staff/dashboard/summary").header(HttpHeaders.AUTHORIZATION, desk)
				.param("date", wednesday.toString()).param("branch", "MLG"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.branch").value("MLG"))
			.andExpect(jsonPath("$.branches", hasSize(1)))
			.andExpect(jsonPath(row + "booked").value(1))
			.andExpect(jsonPath(row + "cancelled").value(1))
			.andExpect(jsonPath(row + "total").value(1))
			.andExpect(jsonPath(row + "openSlots").value(5));

		mvc.perform(get("/api/staff/dashboard/summary").header(HttpHeaders.AUTHORIZATION, TestLogin.bearer(mvc, "doctor001"))
				.param("date", wednesday.toString()))
			.andExpect(jsonPath("$.doctorOnly").value(true))
			.andExpect(jsonPath("$.doctors[?(@.doctorId == %d)]".formatted(doctorId), hasSize(0)));

		byte[] xlsx = mvc.perform(get("/api/staff/dashboard/summary/export").header(HttpHeaders.AUTHORIZATION, desk)
				.param("date", wednesday.toString()).param("format", "xlsx"))
			.andExpect(status().isOk())
			.andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("dashboard-" + wednesday + ".xlsx")))
			.andReturn().getResponse().getContentAsByteArray();
		assertThat(new String(xlsx, 0, 2, StandardCharsets.US_ASCII)).isEqualTo("PK");   // a zip, as every .xlsx
		mvc.perform(get("/api/staff/dashboard/summary/export").header(HttpHeaders.AUTHORIZATION, TestLogin.bearer(mvc, "doctor001")))
			.andExpect(status().isForbidden());
	}

	// ---- reports (FR-D6) ----

	@Test
	void reportsShowOnScreenAndExportWithAnAuditEntry() throws Exception {
		String mobile = newMobile();
		counterBooking(wednesdaySlot("10:00"), mobile);

		String summary = mvc.perform(get("/api/staff/reports/doctor-summary").header(HttpHeaders.AUTHORIZATION, desk)
				.param("from", wednesday.toString()).param("to", wednesday.toString()).param("branch", "MLG"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.title").value("Doctor-wise summary"))
			.andReturn().getResponse().getContentAsString();
		List<Object> row = reportRow(summary, 1, "Dr. Reception Test");
		assertThat(((Number) row.get(3)).intValue()).isEqualTo(1);           // appointments
		assertThat(((Number) row.get(8)).intValue()).isEqualTo(6);           // slots
		assertThat(((Number) row.get(9)).doubleValue()).isEqualTo(16.7);     // utilisation %

		String register = mvc.perform(get("/api/staff/reports/daily-register").header(HttpHeaders.AUTHORIZATION, desk)
				.param("from", wednesday.toString()).param("branch", "MLG"))
			.andReturn().getResponse().getContentAsString();
		assertThat(reportRow(register, 2, "Dr. Reception Test").get(7)).isEqualTo(mobile.substring(0, 2) + "XXXXXX" + mobile.substring(8));
		assertThat(register).doesNotContain(mobile);

		for (String type : new String[] { "branch-summary", "registrations", "channels", "no-show-cancel" }) {
			mvc.perform(get("/api/staff/reports/{type}", type).header(HttpHeaders.AUTHORIZATION, desk)
					.param("from", today.minusDays(40).toString()).param("to", today.toString()).param("period", "month"))
				.andExpect(status().isOk());
		}

		long before = id("SELECT COUNT(*) FROM audit_log WHERE action = 'REPORT_EXPORTED'");
		mvc.perform(get("/api/staff/reports/channels").header(HttpHeaders.AUTHORIZATION, desk)
				.param("from", wednesday.toString()).param("format", "csv"))
			.andExpect(status().isOk())
			.andExpect(content().contentTypeCompatibleWith("text/csv"))
			.andExpect(content().string(containsString("Branch,Online,Counter,Phone,Walk-in,Total,Online %")));
		assertThat(id("SELECT COUNT(*) FROM audit_log WHERE action = 'REPORT_EXPORTED'")).isEqualTo(before + 1);

		mvc.perform(get("/api/staff/reports/everything").header(HttpHeaders.AUTHORIZATION, desk))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("UNKNOWN_REPORT"));
		mvc.perform(get("/api/staff/reports/channels").header(HttpHeaders.AUTHORIZATION, desk)
				.param("from", "2026-01-01").param("to", "2026-06-30"))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("RANGE_TOO_LONG"));
		mvc.perform(get("/api/staff/reports/channels").header(HttpHeaders.AUTHORIZATION, TestLogin.bearer(mvc, "doctor001")))
			.andExpect(status().isForbidden());
	}

	// ---- registration (FR-PT1, M6 part) ----

	@Test
	void registrationGivesAUhidAndEstimatesTheBirthDateFromTheAge() throws Exception {
		mvc.perform(post("/api/staff/patients").header(HttpHeaders.AUTHORIZATION, desk).contentType(MediaType.APPLICATION_JSON)
				.content("{\"fullName\":\"Sita Rani\",\"age\":30,\"gender\":\"F\",\"mobile\":\"+91 98765 43210\",\"consent\":true}"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.uhid", matchesPattern("VMH-%02d-\\d{6}".formatted(today.getYear() % 100))))
			.andExpect(jsonPath("$.age").value(30))
			.andExpect(jsonPath("$.dobEstimated").value(true))
			.andExpect(jsonPath("$.mobile").value("9876543210"))
			.andExpect(jsonPath("$.registeredBranchCode").value("MLG"));   // the receptionist's home branch

		mvc.perform(post("/api/staff/patients").header(HttpHeaders.AUTHORIZATION, desk).contentType(MediaType.APPLICATION_JSON)
				.content("{\"fullName\":\"No Age\",\"gender\":\"F\",\"mobile\":\"9876543210\",\"consent\":true}"))
			.andExpect(status().isBadRequest());
		mvc.perform(post("/api/staff/patients").header(HttpHeaders.AUTHORIZATION, desk).contentType(MediaType.APPLICATION_JSON)
				.content("{\"fullName\":\"Exact Date\",\"dateOfBirth\":\"1990-02-15\",\"gender\":\"M\",\"mobile\":\"9876543210\",\"branch\":\"kdd\",\"consent\":true}"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.dobEstimated").value(false))
			.andExpect(jsonPath("$.registeredBranchCode").value("KDD"));   // a chosen branch beats the home branch
	}

}
