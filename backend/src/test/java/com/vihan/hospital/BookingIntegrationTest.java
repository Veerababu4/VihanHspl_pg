package com.vihan.hospital;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;
import com.vihan.hospital.appointment.AppointmentDtos.BookingRequest;
import com.vihan.hospital.appointment.BookingService;
import com.vihan.hospital.common.error.ApiException;

/**
 * M5 booking end to end on a real PostgreSQL (DESIGN.md §5.2, §10.3, §19.2–19.3). Each test creates its
 * own doctor with a Wednesday 10:00-12:00 session at Miryalaguda (6 slots), and sends each public
 * request from a fresh random IP so the real 10/minute booking limit doesn't interfere. Mobile numbers
 * are random too: the "3 bookings per mobile per day" rule applies across doctors, so tests sharing a
 * number on the same Wednesday would (correctly) hit it.
 */
@VihanIntegrationTest
class BookingIntegrationTest {

	private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	BookingService bookingService;

	String admin;
	long mlg;
	long doctorId;
	LocalDate wednesday;
	/** This test's patient mobile, "98xxxxxx10" so its masked form is always 98XXXXXX10. */
	String mobile;

	@BeforeEach
	void setUp() throws Exception {
		admin = TestLogin.bearer(mvc, "itadmin");
		mlg = id("SELECT branch_id FROM branch WHERE code = 'MLG'");
		long kdd = id("SELECT branch_id FROM branch WHERE code = 'KDD'");
		wednesday = LocalDate.now(IST).plusDays(1).with(TemporalAdjusters.next(DayOfWeek.WEDNESDAY));
		doctorId = createDoctor(mlg, kdd);
		mobile = "98" + String.format("%06d", ThreadLocalRandom.current().nextInt(1_000_000)) + "10";
		mvc.perform(post("/api/staff/doctors/{id}/schedules", doctorId).header(HttpHeaders.AUTHORIZATION, admin)
				.contentType(MediaType.APPLICATION_JSON).content("""
						{"branchId":%d,"dayOfWeek":3,"startTime":"10:00","endTime":"12:00","slotMinutes":20,"effectiveFrom":"2026-01-01"}"""
					.formatted(mlg)))
			.andExpect(status().isCreated());
	}

	/** Test doctors are deactivated afterwards so other test classes see only the sample doctors. */
	@AfterEach
	void deactivateTestDoctor() {
		jdbc.update("UPDATE doctor SET is_active = FALSE WHERE doctor_id = ?", doctorId);
	}

	// ---- helpers ----

	/** Another random valid mobile number. */
	private static String newMobile() {
		return "9" + String.format("%09d", ThreadLocalRandom.current().nextInt(1_000_000_000));
	}

	private long id(String sql, Object... args) {
		return jdbc.queryForObject(sql, Long.class, args);
	}

	private long createDoctor(long... branchIds) throws Exception {
		long speciality = id("SELECT speciality_id FROM speciality WHERE name = 'ENT'");
		String branches = String.join(",", java.util.Arrays.stream(branchIds).mapToObj(String::valueOf).toList());
		String body = mvc.perform(post("/api/admin/doctors").header(HttpHeaders.AUTHORIZATION, admin)
				.contentType(MediaType.APPLICATION_JSON).content("""
						{"fullName":"Dr. Booking Test","specialityId":%d,"qualification":"MBBS, MS (ENT)","practiceStartYear":2012,
						 "registrationNo":"BOOK-%s","branchIds":[%s],"consultationFee":400,"active":true}"""
					.formatted(speciality, UUID.randomUUID().toString().substring(0, 8), branches)))
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString();
		return ((Number) JsonPath.read(body, "$.id")).longValue();
	}

	/** Slot of the test doctor on {@link #wednesday} at "HH:mm". */
	private long slot(String time) {
		return id("SELECT slot_id FROM slot WHERE doctor_id = ? AND start_at = ?", doctorId,
				wednesday.atTime(Integer.parseInt(time.substring(0, 2)), Integer.parseInt(time.substring(3))));
	}

	private String slotStatus(long slotId) {
		return jdbc.queryForObject("SELECT status FROM slot WHERE slot_id = ?", String.class, slotId);
	}

	/** A fresh client IP for every request (see class comment). */
	private static RequestPostProcessor anyIp() {
		ThreadLocalRandom r = ThreadLocalRandom.current();
		String ip = "10." + r.nextInt(256) + "." + r.nextInt(256) + "." + r.nextInt(1, 255);
		return request -> {
			request.setRemoteAddr(ip);
			return request;
		};
	}

	private static String bookingJson(long slotId, String mobile, String captcha) {
		return """
				{"slotId":%d,"patientName":"  Ravi   Kumar ","patientAge":42,"patientGender":"M","mobile":"%s",
				 "reason":"Ear pain","consent":true,"captchaToken":%s}"""
			.formatted(slotId, mobile, captcha == null ? "null" : "\"" + captcha + "\"");
	}

	private ResultActions book(long slotId, String mobile) throws Exception {
		return mvc.perform(post("/api/public/appointments").with(anyIp()).contentType(MediaType.APPLICATION_JSON)
			.content(bookingJson(slotId, mobile, TestCaptchaConfiguration.PASS)));
	}

	private String bookRef(long slotId, String mobile) throws Exception {
		String body = book(slotId, mobile).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.bookingRef");
	}

	private ResultActions patientAction(String path, String json) throws Exception {
		return mvc.perform(post("/api/public/appointments/" + path).with(anyIp()).contentType(MediaType.APPLICATION_JSON).content(json));
	}

	/** An open slot of the test doctor starting {@code minutes} from now (seconds set so it never clashes). */
	private long slotStartingIn(int minutes) {
		LocalDateTime start = LocalDateTime.now(IST).plusMinutes(minutes).withSecond(7).withNano(0);
		jdbc.update("""
				INSERT INTO slot (doctor_id, branch_id, slot_date, start_at, end_at, token_no, status, source)
				VALUES (?, ?, ?, ?, ?, 99, 'OPEN', 'MANUAL')""", doctorId, mlg, start.toLocalDate(), start, start.plusMinutes(20));
		return id("SELECT slot_id FROM slot WHERE doctor_id = ? AND start_at = ?", doctorId, start);
	}

	// ---- free slots ----

	@Test
	void wizardSeesOnlyBookableSlots() throws Exception {
		mvc.perform(get("/api/public/doctors/{id}/slot-days", doctorId).with(anyIp()).param("branch", "mlg"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$", hasSize(14)))
			.andExpect(jsonPath("$[?(@.date == '%s')].openSlots".formatted(wednesday)).value(6));
		mvc.perform(get("/api/public/doctors/{id}/slots", doctorId).with(anyIp()).param("branch", "MLG").param("date", wednesday.toString()))
			.andExpect(jsonPath("$", hasSize(6)))
			.andExpect(jsonPath("$[0].tokenNo").value(1));
		mvc.perform(get("/api/public/doctors/{id}/slot-days", doctorId).with(anyIp()).param("branch", "HZN"))
			.andExpect(status().isNotFound());   // the doctor doesn't work there
	}

	// ---- booking ----

	@Test
	void onlineBookingConfirmsWithReferenceAndToken() throws Exception {
		long slotId = slot("10:20");
		RequestPostProcessor ip = request -> {
			request.setRemoteAddr("10.9.8.7");
			return request;
		};
		mvc.perform(post("/api/public/appointments").with(ip).contentType(MediaType.APPLICATION_JSON)
				.content(bookingJson(slotId, "+91 " + mobile.substring(0, 5) + " " + mobile.substring(5), TestCaptchaConfiguration.PASS)))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.bookingRef", matchesPattern("VMH-\\d{6}-[2-9A-HJ-NP-Z]{4}")))
			.andExpect(jsonPath("$.status").value("BOOKED"))
			.andExpect(jsonPath("$.tokenNo").value(2))
			.andExpect(jsonPath("$.patientName").value("Ravi Kumar"))     // tidied
			.andExpect(jsonPath("$.mobileMasked").value("98XXXXXX10"))
			.andExpect(jsonPath("$.doctor.fullName").value("Dr. Booking Test"))
			.andExpect(jsonPath("$.branch.code").value("MLG"))
			.andExpect(jsonPath("$.canCancel").value(true));

		assertThat(slotStatus(slotId)).isEqualTo("BOOKED");
		var row = jdbc.queryForMap("SELECT channel, patient_mobile, booked_ip, consent_at FROM appointment WHERE slot_id = ?", slotId);
		assertThat(row.get("CHANNEL")).isEqualTo("ONLINE");
		assertThat(row.get("PATIENT_MOBILE")).isEqualTo(mobile);
		assertThat(row.get("BOOKED_IP")).isEqualTo("10.9.8.7");
		assertThat(row.get("CONSENT_AT")).isNotNull();

		mvc.perform(get("/api/public/doctors/{id}/slots", doctorId).with(anyIp()).param("branch", "MLG").param("date", wednesday.toString()))
			.andExpect(jsonPath("$[*].slotId", not(hasItem((int) slotId))));
	}

	@Test
	void aSlotCanBeBookedOnlyOnce() throws Exception {
		long slotId = slot("10:00");
		book(slotId, newMobile()).andExpect(status().isCreated());
		book(slotId, newMobile()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SLOT_TAKEN"));
	}

	@Test
	void parallelBookingsOfOneSlotHaveExactlyOneWinner() throws Exception {
		long slotId = slot("11:40");
		int threads = 8;
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CountDownLatch start = new CountDownLatch(1);
		List<Future<String>> results = new ArrayList<>();
		for (int i = 0; i < threads; i++) {
			String mobile = "98000000" + (10 + i);
			results.add(pool.submit(() -> {
				start.await();
				try {
					bookingService.bookOnline(new BookingRequest(slotId, "Parallel Patient", 30, "F", mobile, null, true, "pass", null), "10.0.0.1");
					return "BOOKED";
				}
				catch (ApiException e) {
					return e.getCode();
				}
			}));
		}
		start.countDown();
		List<String> outcomes = new ArrayList<>();
		for (Future<String> f : results) {
			outcomes.add(f.get());
		}
		pool.shutdown();

		assertThat(outcomes).filteredOn("BOOKED"::equals).hasSize(1);
		assertThat(outcomes).filteredOn("SLOT_TAKEN"::equals).hasSize(threads - 1);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM appointment WHERE slot_id = ?", Integer.class, slotId)).isEqualTo(1);
	}

	@Test
	void captchaAndConsentAreRequired() throws Exception {
		long slotId = slot("10:40");
		mvc.perform(post("/api/public/appointments").with(anyIp()).contentType(MediaType.APPLICATION_JSON)
				.content(bookingJson(slotId, mobile, null)))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CAPTCHA_REQUIRED"));
		mvc.perform(post("/api/public/appointments").with(anyIp()).contentType(MediaType.APPLICATION_JSON)
				.content(bookingJson(slotId, mobile, "bot-guess")))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CAPTCHA_FAILED"));
		mvc.perform(post("/api/public/appointments").with(anyIp()).contentType(MediaType.APPLICATION_JSON)
				.content(bookingJson(slotId, "12345", "pass").replace("\"consent\":true", "\"consent\":false")))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.fieldErrors[*].field", hasItem("consent")))
			.andExpect(jsonPath("$.fieldErrors[*].field", hasItem("mobile")));
		assertThat(slotStatus(slotId)).isEqualTo("OPEN");
	}

	@Test
	void oneMobileCanHoldAtMostThreeBookingsPerDay() throws Exception {
		for (String time : List.of("10:00", "10:20", "10:40")) {
			book(slot(time), mobile).andExpect(status().isCreated());
		}
		book(slot("11:00"), "0" + mobile.substring(0, 5) + " " + mobile.substring(5)).andExpect(status().isConflict())   // same number, typed differently
			.andExpect(jsonPath("$.code").value("TOO_MANY_BOOKINGS"));
		book(slot("11:00"), newMobile()).andExpect(status().isCreated());     // another number is fine
	}

	@Test
	void slotsStartingTooSoonCantBeBookedOnline() throws Exception {
		long soon = slotStartingIn(20);
		book(soon, mobile).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SLOT_TOO_SOON"));
	}

	// ---- check / cancel / reschedule ----

	@Test
	void lookupNeedsTheMatchingMobileAndGivesNoHints() throws Exception {
		String ref = bookRef(slot("10:00"), mobile);
		patientAction("lookup", "{\"bookingRef\":\"%s\",\"mobile\":\"0%s %s\"}".formatted(ref.toLowerCase(), mobile.substring(0, 5), mobile.substring(5)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.bookingRef").value(ref))
			.andExpect(jsonPath("$.reschedulesLeft").value(2));
		patientAction("lookup", "{\"bookingRef\":\"%s\",\"mobile\":\"9999999999\"}".formatted(ref))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.message").value("No booking found with that reference and mobile number"));
		patientAction("lookup", "{\"bookingRef\":\"VMH-000000-ZZZZ\",\"mobile\":\"%s\"}".formatted(mobile))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.message").value("No booking found with that reference and mobile number"));
	}

	@Test
	void cancellingFreesTheSlotForSomeoneElse() throws Exception {
		long slotId = slot("10:20");
		String ref = bookRef(slotId, mobile);
		patientAction("cancel", "{\"bookingRef\":\"%s\",\"mobile\":\"%s\",\"reason\":\"Feeling better\",\"captchaToken\":\"pass\"}".formatted(ref, mobile))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("CANCELLED"))
			.andExpect(jsonPath("$.canCancel").value(false));
		assertThat(slotStatus(slotId)).isEqualTo("OPEN");
		book(slotId, newMobile()).andExpect(status().isCreated());   // the unique index allows it again

		patientAction("cancel", "{\"bookingRef\":\"%s\",\"mobile\":\"%s\",\"captchaToken\":\"pass\"}".formatted(ref, mobile))
			.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NOT_CHANGEABLE"));
	}

	@Test
	void onlineChangesCloseOneHourBefore() throws Exception {
		long soon = slotStartingIn(40);
		String reception = TestLogin.bearer(mvc, "reception.mlg");
		String body = mvc.perform(post("/api/staff/appointments").header(HttpHeaders.AUTHORIZATION, reception)
				.contentType(MediaType.APPLICATION_JSON).content("""
						{"slotId":%d,"patientName":"Walk Up","patientAge":60,"patientGender":"F","mobile":"%s",
						 "channel":"COUNTER","consent":true}""".formatted(soon, mobile)))
			.andExpect(status().isCreated())   // reception may book a slot starting soon
			.andExpect(jsonPath("$.channel").value("COUNTER"))
			.andExpect(jsonPath("$.canCancel").value(false))
			.andReturn().getResponse().getContentAsString();
		String ref = JsonPath.read(body, "$.bookingRef");

		patientAction("cancel", "{\"bookingRef\":\"%s\",\"mobile\":\"%s\",\"captchaToken\":\"pass\"}".formatted(ref, mobile))
			.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TOO_LATE"));
	}

	@Test
	void rescheduleKeepsTheReferenceUpToTwice() throws Exception {
		long first = slot("10:00");
		String ref = bookRef(first, mobile);
		String move = "{\"bookingRef\":\"%s\",\"mobile\":\"%s\",\"newSlotId\":%d,\"captchaToken\":\"pass\"}";

		patientAction("reschedule", move.formatted(ref, mobile, slot("11:00")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.bookingRef").value(ref))
			.andExpect(jsonPath("$.tokenNo").value(4))
			.andExpect(jsonPath("$.rescheduleCount").value(1));
		assertThat(slotStatus(first)).isEqualTo("OPEN");
		assertThat(slotStatus(slot("11:00"))).isEqualTo("BOOKED");

		patientAction("reschedule", move.formatted(ref, mobile, slot("11:20"))).andExpect(status().isOk())
			.andExpect(jsonPath("$.reschedulesLeft").value(0)).andExpect(jsonPath("$.canReschedule").value(false));
		patientAction("reschedule", move.formatted(ref, mobile, slot("11:40")))
			.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RESCHEDULE_LIMIT"));
		assertThat(slotStatus(slot("11:20"))).isEqualTo("BOOKED");   // the failed attempt changed nothing
	}

	@Test
	void rescheduleOnlyToAFreeSlotOfTheSameDoctor() throws Exception {
		String ref = bookRef(slot("10:00"), mobile);
		bookRef(slot("10:20"), newMobile());
		String move = "{\"bookingRef\":\"%s\",\"mobile\":\"%s\",\"newSlotId\":%d,\"captchaToken\":\"pass\"}";

		patientAction("reschedule", move.formatted(ref, mobile, slot("10:20")))
			.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SLOT_TAKEN"));
		long otherDoctorSlot = id("SELECT MIN(slot_id) FROM slot WHERE doctor_id <> ? AND status = 'OPEN' AND slot_date = ?", doctorId, wednesday);
		patientAction("reschedule", move.formatted(ref, mobile, otherDoctorSlot))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("DIFFERENT_DOCTOR"));
		assertThat(slotStatus(slot("10:00"))).isEqualTo("BOOKED");
	}

	@Test
	void leaveAfterBookingIsShownAndCancellingThenKeepsTheSlotBlocked() throws Exception {
		long slotId = slot("10:00");
		String ref = bookRef(slotId, mobile);
		mvc.perform(post("/api/staff/doctors/{id}/leaves", doctorId).header(HttpHeaders.AUTHORIZATION, admin)
				.contentType(MediaType.APPLICATION_JSON).content("{\"fromDate\":\"%s\",\"toDate\":\"%s\"}".formatted(wednesday, wednesday)))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.changes.affectedBookings[0].bookingRef").value(ref));

		patientAction("lookup", "{\"bookingRef\":\"%s\",\"mobile\":\"%s\"}".formatted(ref, mobile))
			.andExpect(jsonPath("$.needsAction").value(true));
		patientAction("cancel", "{\"bookingRef\":\"%s\",\"mobile\":\"%s\",\"captchaToken\":\"pass\"}".formatted(ref, mobile))
			.andExpect(status().isOk());
		// Freed, then re-blocked because the doctor is on leave that day.
		assertThat(slotStatus(slotId)).isEqualTo("BLOCKED");
		assertThat(jdbc.queryForObject("SELECT block_reason FROM slot WHERE slot_id = ?", String.class, slotId)).isEqualTo("LEAVE");
	}

	// ---- staff & limits ----

	@Test
	void onlyReceptionAndItAdminBookAtTheCounter() throws Exception {
		String doctor = TestLogin.bearer(mvc, "doctor001");
		mvc.perform(post("/api/staff/appointments").header(HttpHeaders.AUTHORIZATION, doctor)
				.contentType(MediaType.APPLICATION_JSON).content("""
						{"slotId":%d,"patientName":"X Y","patientAge":30,"patientGender":"M","mobile":"%s","channel":"PHONE","consent":true}"""
					.formatted(slot("10:00"), mobile)))
			.andExpect(status().isForbidden());
	}

	@Test
	void tooManyRequestsFromOneIpAreRefused() throws Exception {
		RequestPostProcessor sameIp = request -> {
			request.setRemoteAddr("10.250.250.250");
			return request;
		};
		String json = bookingJson(slot("10:00"), mobile, "bot-guess");
		for (int i = 0; i < 10; i++) {
			mvc.perform(post("/api/public/appointments").with(sameIp).contentType(MediaType.APPLICATION_JSON).content(json))
				.andExpect(status().isBadRequest());
		}
		mvc.perform(post("/api/public/appointments").with(sameIp).contentType(MediaType.APPLICATION_JSON).content(json))
			.andExpect(status().isTooManyRequests())
			.andExpect(jsonPath("$.code").value("RATE_LIMITED"))
			.andExpect(header().exists(HttpHeaders.RETRY_AFTER));
	}

}
