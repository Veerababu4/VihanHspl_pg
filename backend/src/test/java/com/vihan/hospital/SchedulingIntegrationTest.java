package com.vihan.hospital;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.UUID;

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

/**
 * M4 scheduling end to end on a real PostgreSQL. Each test creates its own doctor (serving MLG + KDD)
 * so tests don't affect each other or the sample doctors. Dates are relative to today, and every
 * test date is at least one day ahead so "the past is never changed" doesn't interfere.
 */
@VihanIntegrationTest
class SchedulingIntegrationTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate jdbc;

	String admin;
	long mlg;
	long kdd;
	long hzn;
	long doctorId;
	/** A Wednesday 2–8 days ahead: inside the 14-day window, never today. */
	LocalDate wednesday;

	@BeforeEach
	void setUp() throws Exception {
		admin = TestLogin.bearer(mvc, "itadmin");
		mlg = id("SELECT branch_id FROM branch WHERE code = 'MLG'");
		kdd = id("SELECT branch_id FROM branch WHERE code = 'KDD'");
		hzn = id("SELECT branch_id FROM branch WHERE code = 'HZN'");
		wednesday = LocalDate.now().plusDays(1).with(TemporalAdjusters.next(DayOfWeek.WEDNESDAY));
		doctorId = createDoctor();
	}

	/** Test doctors are deactivated afterwards so other test classes see only the sample doctors. */
	@AfterEach
	void deactivateTestDoctor() {
		jdbc.update("UPDATE doctor SET is_active = FALSE WHERE doctor_id = ?", doctorId);
	}

	// ---- helpers ----

	private long id(String sql, Object... args) {
		return jdbc.queryForObject(sql, Long.class, args);
	}

	private long createDoctor() throws Exception {
		long speciality = id("SELECT speciality_id FROM speciality WHERE name = 'General Medicine'");
		String body = mvc.perform(post("/api/admin/doctors").header(HttpHeaders.AUTHORIZATION, admin)
				.contentType(MediaType.APPLICATION_JSON).content("""
						{"fullName":"Dr. Schedule Test","specialityId":%d,"qualification":"MBBS","practiceStartYear":2010,
						 "registrationNo":"TEST-%s","branchIds":[%d,%d],"active":true}"""
					.formatted(speciality, UUID.randomUUID().toString().substring(0, 8), mlg, kdd)))
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString();
		return ((Number) JsonPath.read(body, "$.id")).longValue();
	}

	private ResultActions addTemplate(String auth, long doctor, long branch, int dow, String start, String end) throws Exception {
		return mvc.perform(post("/api/staff/doctors/{id}/schedules", doctor).header(HttpHeaders.AUTHORIZATION, auth)
			.contentType(MediaType.APPLICATION_JSON).content("""
					{"branchId":%d,"dayOfWeek":%d,"startTime":"%s","endTime":"%s","slotMinutes":20,"effectiveFrom":"2026-01-01"}"""
				.formatted(branch, dow, start, end)));
	}

	private int slots(LocalDate date, String status) {
		return jdbc.queryForObject("SELECT COUNT(*) FROM slot WHERE doctor_id = ? AND slot_date = ? AND status = ?",
				Integer.class, doctorId, date, status);
	}

	private List<String> blockReasons(LocalDate date) {
		return jdbc.queryForList("SELECT DISTINCT block_reason FROM slot WHERE doctor_id = ? AND slot_date = ? AND status = 'BLOCKED'",
				String.class, doctorId, date);
	}

	/** Books the first slot of the day the way M5 will: slot BOOKED + appointment row. */
	private long book(LocalDate date) {
		long slotId = id("SELECT MIN(slot_id) FROM slot WHERE doctor_id = ? AND slot_date = ? AND status = 'OPEN'", doctorId, date);
		jdbc.update("UPDATE slot SET status = 'BOOKED', version = version + 1 WHERE slot_id = ?", slotId);
		jdbc.update("""
				INSERT INTO appointment (booking_ref, slot_id, doctor_id, branch_id, appt_date, start_at, token_no,
				  patient_name, patient_mobile, channel, status, consent_at)
				SELECT ?, slot_id, doctor_id, branch_id, slot_date, start_at, token_no, 'Ravi Kumar', '9876543210',
				  'ONLINE', 'BOOKED', LOCALTIMESTAMP FROM slot WHERE slot_id = ?""",
				"T-" + UUID.randomUUID().toString().substring(0, 8), slotId);
		return slotId;
	}

	// ---- weekly timetable ----

	@Test
	void templateCreatesSlotsInTheWindowWithTokens() throws Exception {
		addTemplate(admin, doctorId, mlg, 3, "10:00", "12:00").andExpect(status().isCreated())
			.andExpect(jsonPath("$.item.slotsPerSession").value(6))
			// 6 per Wednesday; 2 Wednesdays in the window (1 if today is a Wednesday afternoon)
			.andExpect(jsonPath("$.changes.slotsCreated", org.hamcrest.Matchers.greaterThanOrEqualTo(6)));

		mvc.perform(get("/api/staff/doctors/{id}/slots", doctorId).header(HttpHeaders.AUTHORIZATION, admin)
				.param("from", wednesday.toString()).param("to", wednesday.toString()))
			.andExpect(jsonPath("$.length()").value(6))
			.andExpect(jsonPath("$[0].tokenNo").value(1))
			.andExpect(jsonPath("$[5].tokenNo").value(6))
			.andExpect(jsonPath("$[*].status", everyItem(is("OPEN"))));

		// Running generation again changes nothing.
		mvc.perform(post("/api/staff/doctors/{id}/slots/generate", doctorId).header(HttpHeaders.AUTHORIZATION, admin)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"from\":\"%s\",\"to\":\"%s\"}".formatted(LocalDate.now(), LocalDate.now().plusDays(13))))
			.andExpect(jsonPath("$.slotsCreated").value(0));
	}

	@Test
	void doctorCannotBeInTwoPlacesAtOnceOrAtABranchTheyDontServe() throws Exception {
		addTemplate(admin, doctorId, mlg, 3, "10:00", "14:00").andExpect(status().isCreated());
		addTemplate(admin, doctorId, kdd, 3, "13:00", "15:00").andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("SCHEDULE_OVERLAP"))
			.andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Wed 10:00-14:00 at Miryalaguda")));
		addTemplate(admin, doctorId, kdd, 3, "14:00", "16:00").andExpect(status().isCreated());   // back to back is fine
		addTemplate(admin, doctorId, hzn, 4, "10:00", "12:00").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("BRANCH_NOT_SERVED"));
	}

	@Test
	void editingAndDeletingATemplateOnlyTouchesFutureOpenSlots() throws Exception {
		String body = addTemplate(admin, doctorId, mlg, 3, "10:00", "12:00").andReturn().getResponse().getContentAsString();
		long scheduleId = ((Number) JsonPath.read(body, "$.item.id")).longValue();
		long booked = book(wednesday);

		// Shorten to 10:00-11:00: the open 11:xx slots go; the booked 10:00 slot stays.
		mvc.perform(put("/api/staff/schedules/{id}", scheduleId).header(HttpHeaders.AUTHORIZATION, admin)
				.contentType(MediaType.APPLICATION_JSON).content("""
						{"branchId":%d,"dayOfWeek":3,"startTime":"10:00","endTime":"11:00","slotMinutes":20,"effectiveFrom":"2026-01-01"}"""
					.formatted(mlg)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.changes.slotsRemoved", org.hamcrest.Matchers.greaterThanOrEqualTo(3)));
		assertThat(slots(wednesday, "OPEN") + slots(wednesday, "BOOKED")).isEqualTo(3);

		// Delete the session: open slots go, the booking is flagged for reception to call.
		mvc.perform(delete("/api/staff/schedules/{id}", scheduleId).header(HttpHeaders.AUTHORIZATION, admin))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.affectedBookings.length()").value(1))
			.andExpect(jsonPath("$.affectedBookings[0].mobile").value("98XXXXXX10"));
		assertThat(slots(wednesday, "OPEN")).isZero();
		assertThat(jdbc.queryForObject("SELECT status FROM slot WHERE slot_id = ?", String.class, booked)).isEqualTo("BOOKED");
		assertThat(jdbc.queryForObject("SELECT needs_action FROM appointment WHERE slot_id = ?", Boolean.class, booked)).isTrue();
	}

	// ---- leave & holidays ----

	@Test
	void leaveBlocksSlotsFlagsBookingsAndRemovingItReopensThem() throws Exception {
		addTemplate(admin, doctorId, mlg, 3, "10:00", "12:00").andExpect(status().isCreated());
		long booked = book(wednesday);

		String body = mvc.perform(post("/api/staff/doctors/{id}/leaves", doctorId).header(HttpHeaders.AUTHORIZATION, admin)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"fromDate\":\"%s\",\"toDate\":\"%s\",\"reason\":\"Conference\"}".formatted(wednesday, wednesday)))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.changes.slotsBlocked").value(5))
			.andExpect(jsonPath("$.changes.affectedBookings[0].patientName").value("Ravi Kumar"))
			.andReturn().getResponse().getContentAsString();
		assertThat(blockReasons(wednesday)).containsExactly("LEAVE");
		assertThat(jdbc.queryForObject("SELECT needs_action FROM appointment WHERE slot_id = ?", Boolean.class, booked)).isTrue();

		// A leave block can't be lifted by hand...
		long blockedSlot = id("SELECT MIN(slot_id) FROM slot WHERE doctor_id = ? AND slot_date = ? AND status = 'BLOCKED'", doctorId, wednesday);
		mvc.perform(patch("/api/staff/slots/{id}", blockedSlot).header(HttpHeaders.AUTHORIZATION, admin)
				.contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"OPEN\"}"))
			.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BLOCKED_BY_LEAVE"));

		// ...only by removing the leave, which reopens exactly those slots.
		long leaveId = ((Number) JsonPath.read(body, "$.item.id")).longValue();
		mvc.perform(delete("/api/staff/leaves/{id}", leaveId).header(HttpHeaders.AUTHORIZATION, admin))
			.andExpect(jsonPath("$.slotsReopened").value(5));
		assertThat(slots(wednesday, "OPEN")).isEqualTo(5);
		assertThat(slots(wednesday, "BOOKED")).isEqualTo(1);
	}

	@Test
	void holidayAppliesToItsBranchOnly() throws Exception {
		addTemplate(admin, doctorId, mlg, 3, "10:00", "11:00").andExpect(status().isCreated());
		LocalDate thursday = wednesday.plusDays(1);
		addTemplate(admin, doctorId, kdd, 4, "10:00", "11:00").andExpect(status().isCreated());

		String body = mvc.perform(post("/api/staff/holidays").header(HttpHeaders.AUTHORIZATION, admin)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"branchId\":%d,\"date\":\"%s\",\"name\":\"Local festival\"}".formatted(mlg, wednesday)))
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString();
		try {
			assertThat(blockReasons(wednesday)).containsExactly("HOLIDAY");
			assertThat(slots(thursday, "OPEN")).isEqualTo(3);   // Kodad is open
			mvc.perform(post("/api/staff/holidays").header(HttpHeaders.AUTHORIZATION, admin)
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"branchId\":%d,\"date\":\"%s\",\"name\":\"Again\"}".formatted(mlg, wednesday)))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("HOLIDAY_EXISTS"));
		}
		finally {
			long holidayId = ((Number) JsonPath.read(body, "$.item.id")).longValue();
			mvc.perform(delete("/api/staff/holidays/{id}", holidayId).header(HttpHeaders.AUTHORIZATION, admin))
				.andExpect(status().isOk());
		}
		assertThat(slots(wednesday, "OPEN")).isEqualTo(3);
	}

	// ---- manual changes ----

	@Test
	void extraSessionsAndManualBlocks() throws Exception {
		addTemplate(admin, doctorId, mlg, 3, "10:00", "11:00").andExpect(status().isCreated());

		mvc.perform(post("/api/staff/doctors/{id}/slots", doctorId).header(HttpHeaders.AUTHORIZATION, admin)
				.contentType(MediaType.APPLICATION_JSON).content("""
						{"branchId":%d,"date":"%s","startTime":"16:00","endTime":"17:00","slotMinutes":30}""".formatted(mlg, wednesday)))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.length()").value(2))
			.andExpect(jsonPath("$[0].source").value("MANUAL"))
			.andExpect(jsonPath("$[0].tokenNo").value(4));   // after the 3 morning tokens

		mvc.perform(post("/api/staff/doctors/{id}/slots", doctorId).header(HttpHeaders.AUTHORIZATION, admin)
				.contentType(MediaType.APPLICATION_JSON).content("""
						{"branchId":%d,"date":"%s","startTime":"10:30","endTime":"11:30","slotMinutes":30}""".formatted(kdd, wednesday)))
			.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SLOT_OVERLAP"));

		mvc.perform(post("/api/staff/doctors/{id}/slots/day-status", doctorId).header(HttpHeaders.AUTHORIZATION, admin)
				.contentType(MediaType.APPLICATION_JSON).content("{\"date\":\"%s\",\"blocked\":true}".formatted(wednesday)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[*].status", everyItem(is("BLOCKED"))));
		assertThat(blockReasons(wednesday)).containsExactly("MANUAL");

		// The nightly job never lifts a manual block.
		mvc.perform(post("/api/staff/doctors/{id}/slots/generate", doctorId).header(HttpHeaders.AUTHORIZATION, admin)
				.contentType(MediaType.APPLICATION_JSON).content("{\"from\":\"%s\",\"to\":\"%s\"}".formatted(wednesday, wednesday)))
			.andExpect(jsonPath("$.slotsReopened").value(0));
		assertThat(slots(wednesday, "OPEN")).isZero();

		mvc.perform(post("/api/staff/doctors/{id}/slots/day-status", doctorId).header(HttpHeaders.AUTHORIZATION, admin)
				.contentType(MediaType.APPLICATION_JSON).content("{\"date\":\"%s\",\"blocked\":false}".formatted(wednesday)))
			.andExpect(jsonPath("$[*].status", everyItem(is("OPEN"))));
	}

	// ---- who may do what ----

	@Test
	void doctorsManageOnlyTheirOwnScheduleAndReceptionistsOnlyLook() throws Exception {
		long ownId = id("SELECT doctor_id FROM app_user WHERE username = 'doctor001'");
		String doctor = TestLogin.bearer(mvc, "doctor001");
		String reception = TestLogin.bearer(mvc, "reception.mlg");

		mvc.perform(get("/api/staff/doctors/{id}/schedules", ownId).header(HttpHeaders.AUTHORIZATION, doctor))
			.andExpect(status().isOk());
		mvc.perform(get("/api/staff/doctors/{id}/schedules", doctorId).header(HttpHeaders.AUTHORIZATION, doctor))
			.andExpect(status().isForbidden());
		addTemplate(doctor, doctorId, mlg, 5, "10:00", "11:00").andExpect(status().isForbidden());

		mvc.perform(get("/api/staff/doctors/{id}/schedules", doctorId).header(HttpHeaders.AUTHORIZATION, reception))
			.andExpect(status().isOk());
		addTemplate(reception, doctorId, mlg, 5, "10:00", "11:00").andExpect(status().isForbidden());
		mvc.perform(post("/api/staff/holidays").header(HttpHeaders.AUTHORIZATION, reception)
				.contentType(MediaType.APPLICATION_JSON).content("{\"date\":\"%s\",\"name\":\"x\"}".formatted(wednesday)))
			.andExpect(status().isForbidden());
	}

	@Test
	void deactivatingADoctorClearsOpenSlotsAndFlagsBookings() throws Exception {
		addTemplate(admin, doctorId, mlg, 3, "10:00", "11:00").andExpect(status().isCreated());
		long booked = book(wednesday);
		long speciality = id("SELECT speciality_id FROM doctor WHERE doctor_id = ?", doctorId);
		String reg = jdbc.queryForObject("SELECT registration_no FROM doctor WHERE doctor_id = ?", String.class, doctorId);

		mvc.perform(put("/api/admin/doctors/{id}", doctorId).header(HttpHeaders.AUTHORIZATION, admin)
				.contentType(MediaType.APPLICATION_JSON).content("""
						{"fullName":"Dr. Schedule Test","specialityId":%d,"qualification":"MBBS","practiceStartYear":2010,
						 "registrationNo":"%s","branchIds":[%d,%d],"active":false}""".formatted(speciality, reg, mlg, kdd)))
			.andExpect(status().isOk());

		assertThat(slots(wednesday, "OPEN")).isZero();
		assertThat(jdbc.queryForObject("SELECT needs_action FROM appointment WHERE slot_id = ?", Boolean.class, booked)).isTrue();
	}

}
