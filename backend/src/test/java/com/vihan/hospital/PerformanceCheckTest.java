package com.vihan.hospital;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * M9 performance check (DESIGN.md §15): about one year of hospital data in the throwaway test
 * database, then the heavy screens' API calls are timed. Never touches the development database.
 * <p>
 * Not part of {@code mvnw test} (tag "perf"); run it on its own, because the extra rows would
 * upset other tests' counts: {@code .\mvnw test -Dgroups=perf -Dperf.excluded=}
 */
@Tag("perf")
@VihanIntegrationTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PerformanceCheckTest {

	/** Target per API call on the test container; the Always Free VM is in the same class of machine. */
	private static final long LIMIT_MS = 1000;

	private static final int PATIENTS = 30_000;
	private static final int DAYS = 365;
	private static final int SLOTS_PER_DAY = 24;

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate jdbc;

	private final LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));

	@BeforeAll
	void loadOneYearOfData() {
		long start = System.nanoTime();
		jdbc.update("""
				INSERT INTO patient (uhid, full_name, date_of_birth, gender, mobile, registered_branch_id, consent_at, created_at)
				SELECT 'VMH-PF-' || LPAD(g.n::text, 6, '0'),
				       INITCAP(SUBSTR(MD5(g.n::text), 1, 6)) || ' ' || INITCAP(SUBSTR(MD5((g.n * 31)::text), 1, 7)),
				       DATE '1950-01-01' + MOD(g.n * 37, 25000)::int,
				       CASE MOD(g.n, 2) WHEN 0 THEN 'M' ELSE 'F' END,
				       '7' || LPAD(MOD(g.n * 7919, 999999999)::text, 9, '0'),
				       (SELECT branch_id FROM branch WHERE code = CASE MOD(g.n, 3) WHEN 0 THEN 'MLG' WHEN 1 THEN 'HZN' ELSE 'KDD' END),
				       LOCALTIMESTAMP, LOCALTIMESTAMP - MOD(g.n, 365) * INTERVAL '1 day'
				  FROM generate_series(1, ?) AS g (n)""", PATIENTS);
		// Past year of slots for every sample doctor at their first hospital; 4 in 5 booked.
		jdbc.update("""
				INSERT INTO slot (doctor_id, branch_id, slot_date, start_at, end_at, token_no, status, source)
				SELECT db.doctor_id, db.branch_id, CURRENT_DATE - d.n,
				       CAST(CURRENT_DATE - d.n AS TIMESTAMP) + (540 + (t.n - 1) * 20) * INTERVAL '1 minute',
				       CAST(CURRENT_DATE - d.n AS TIMESTAMP) + (560 + (t.n - 1) * 20) * INTERVAL '1 minute',
				       t.n, CASE WHEN MOD(t.n + d.n, 5) = 0 THEN 'OPEN' ELSE 'BOOKED' END, 'TEMPLATE'
				  FROM (SELECT x.doctor_id, MIN(x.branch_id) branch_id FROM doctor_branch x
				          JOIN doctor y ON y.doctor_id = x.doctor_id AND y.registration_no LIKE 'SAMPLE-%'
				         GROUP BY x.doctor_id) db
				 CROSS JOIN generate_series(1, ?) AS d (n)
				 CROSS JOIN generate_series(1, ?) AS t (n)
				 WHERE NOT EXISTS (SELECT 1 FROM slot s WHERE s.doctor_id = db.doctor_id AND s.slot_date = CURRENT_DATE - d.n)""",
				DAYS, SLOTS_PER_DAY);
		jdbc.update("""
				INSERT INTO appointment (booking_ref, slot_id, doctor_id, branch_id, appt_date, start_at, token_no, patient_id,
				       patient_name, patient_mobile, patient_age, patient_gender, channel, status, consent_at,
				       checked_in_at, completed_at, created_at)
				SELECT 'PF' || s.slot_id, s.slot_id, s.doctor_id, s.branch_id, s.slot_date, s.start_at, s.token_no, p.patient_id,
				       p.full_name, p.mobile, 40, p.gender,
				       CASE MOD(s.slot_id, 4) WHEN 1 THEN 'COUNTER' WHEN 2 THEN 'PHONE' ELSE 'ONLINE' END,
				       CASE MOD(s.slot_id, 10) WHEN 0 THEN 'NO_SHOW' WHEN 1 THEN 'CANCELLED' ELSE 'COMPLETED' END,
				       s.start_at - INTERVAL '2' DAY,
				       CASE WHEN MOD(s.slot_id, 10) > 1 THEN s.start_at END,
				       CASE WHEN MOD(s.slot_id, 10) > 1 THEN s.end_at END,
				       s.start_at - INTERVAL '2' DAY
				  FROM slot s
				  JOIN patient p ON p.uhid = 'VMH-PF-' || LPAD((MOD(s.slot_id, ?) + 1)::text, 6, '0')
				 WHERE s.status = 'BOOKED' AND s.slot_date < CURRENT_DATE
				   AND NOT EXISTS (SELECT 1 FROM appointment a WHERE a.slot_id = s.slot_id)""", PATIENTS);
		jdbc.update("""
				INSERT INTO visit (appointment_id, patient_id, doctor_id, branch_id, visit_date, status,
				       bp_systolic, bp_diastolic, pulse, chief_complaint, diagnosis, advice)
				SELECT a.appointment_id, a.patient_id, a.doctor_id, a.branch_id, a.appt_date, 'COMPLETED',
				       120, 80, 72, 'Fever and body pains for 3 days', 'Viral fever', 'Rest, fluids, review if not better'
				  FROM appointment a WHERE a.booking_ref LIKE 'PF%' AND a.status = 'COMPLETED'""");
		jdbc.update("""
				INSERT INTO prescription_item (visit_id, medicine_name, dosage, frequency, duration, instructions, sort_order)
				SELECT v.visit_id, CASE n.n WHEN 1 THEN 'Paracetamol' ELSE 'Cetirizine' END, CASE n.n WHEN 1 THEN '650 mg' ELSE '10 mg' END,
				       CASE n.n WHEN 1 THEN '1-1-1' ELSE '0-0-1' END, '5 days', 'after food', n.n
				  FROM visit v CROSS JOIN generate_series(1, 2) AS n (n)
				 WHERE v.appointment_id IN (SELECT appointment_id FROM appointment WHERE booking_ref LIKE 'PF%')""");
		jdbc.update("""
				INSERT INTO audit_log (user_id, action, entity_type, entity_id, details, ip_address, created_at)
				SELECT (SELECT user_id FROM app_user WHERE username = 'itadmin'),
				       CASE MOD(g.n, 3) WHEN 0 THEN 'PATIENT_VIEWED' WHEN 1 THEN 'APPOINTMENT_CHECKED_IN' ELSE 'LOGIN_SUCCESS' END,
				       'PATIENT', g.n::text, NULL, '10.0.0.1', LOCALTIMESTAMP - MOD(g.n, 525600) * INTERVAL '1 minute'
				  FROM generate_series(1, 300000) AS g (n)""");
		jdbc.execute("ANALYZE");

		Map<String, Integer> counts = new LinkedHashMap<>();
		for (String table : List.of("patient", "slot", "appointment", "visit", "prescription_item", "audit_log")) {
			counts.put(table, jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class));
		}
		System.out.printf("%n[perf] data loaded in %d s: %s%n", (System.nanoTime() - start) / 1_000_000_000, counts);
		assertThat(counts.get("appointment")).isGreaterThan(100_000);
	}

	@Test
	void heavyScreensAnswerWithinOneSecond() throws Exception {
		String admin = TestLogin.bearer(mvc, "itadmin");
		String reception = TestLogin.bearer(mvc, "reception.mlg");
		String doctor = TestLogin.bearer(mvc, "doctor001");
		long doctorId = jdbc.queryForObject("SELECT doctor_id FROM doctor WHERE registration_no = 'SAMPLE-001'", Long.class);
		// The busiest perf patient, with the longest history.
		long patientId = jdbc.queryForObject("""
				SELECT patient_id FROM (SELECT patient_id, COUNT(*) c FROM visit GROUP BY patient_id ORDER BY c DESC)
				 FETCH FIRST 1 ROW ONLY""", Long.class);
		String[] someone = jdbc.queryForObject("SELECT full_name, mobile FROM patient WHERE uhid = 'VMH-PF-012345'",
				(rs, i) -> new String[] { rs.getString(1), rs.getString(2) });
		// The last day with generated data (the container clock is UTC, so not always "yesterday" in IST).
		LocalDate yesterday = jdbc.queryForObject("SELECT MAX(appt_date) FROM appointment WHERE booking_ref LIKE 'PF%'", LocalDate.class);
		String doctorBranch = jdbc.queryForObject("""
				SELECT b.code FROM doctor_branch db JOIN branch b ON b.branch_id = db.branch_id
				 WHERE db.doctor_id = ? ORDER BY b.branch_id FETCH FIRST 1 ROW ONLY""", String.class, doctorId);
		LocalDate from92 = yesterday.minusDays(91);
		LocalDate from31 = yesterday.minusDays(30);

		List<String[]> calls = new ArrayList<>();
		calls.add(new String[] { admin, "/api/staff/dashboard/summary?date=" + yesterday });
		calls.add(new String[] { reception, "/api/staff/dashboard/summary?date=" + yesterday + "&branch=MLG" });
		calls.add(new String[] { reception, "/api/staff/appointments?date=" + yesterday + "&branch=" + doctorBranch });
		calls.add(new String[] { reception, "/api/staff/appointments?date=" + yesterday + "&q=" + someone[1].substring(0, 6) });
		for (String type : List.of("doctor-summary", "branch-summary", "registrations", "channels", "no-show-cancel")) {
			calls.add(new String[] { admin, "/api/staff/reports/" + type + "?from=" + from92 + "&to=" + yesterday });
		}
		calls.add(new String[] { admin, "/api/staff/reports/daily-register?from=" + from31 + "&to=" + yesterday });
		calls.add(new String[] { admin, "/api/staff/reports/doctor-summary?from=" + from92 + "&to=" + yesterday + "&format=xlsx" });
		calls.add(new String[] { admin, "/api/staff/doctors/" + doctorId + "/slots?from=" + today.minusDays(30) + "&to=" + today.plusDays(14) });
		calls.add(new String[] { reception, "/api/staff/patients?name=" + someone[0].substring(0, 4) });
		calls.add(new String[] { reception, "/api/staff/patients?mobile=" + someone[1] });
		calls.add(new String[] { reception, "/api/staff/patients/" + patientId });
		calls.add(new String[] { reception, "/api/staff/patients/" + patientId + "/visits" });
		calls.add(new String[] { doctor, "/api/clinical/patients/" + patientId + "/history" });
		calls.add(new String[] { doctor, "/api/clinical/queue" });
		calls.add(new String[] { admin, "/api/admin/audit-logs" });
		calls.add(new String[] { admin, "/api/admin/audit-logs?action=PATIENT_VIEWED&from=" + from31 + "&to=" + yesterday });
		calls.add(new String[] { null, "/api/public/doctors/" + doctorId + "/slot-days?branch=" + doctorBranch });

		List<String> slow = new ArrayList<>();
		System.out.println("[perf] median of 3 runs after one warm-up:");
		for (String[] call : calls) {
			long[] ms = new long[4];
			int bytes = 0;
			for (int run = 0; run < ms.length; run++) {
				var request = get(call[1]);
				if (call[0] != null) {
					request.header("Authorization", call[0]);
				}
				long t0 = System.nanoTime();
				bytes = mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray().length;
				ms[run] = (System.nanoTime() - t0) / 1_000_000;
			}
			long[] measured = Arrays.copyOfRange(ms, 1, ms.length);
			Arrays.sort(measured);
			long median = measured[1];
			System.out.printf("[perf] %5d ms  %8d bytes  %s%n", median, bytes, call[1]);
			if (median > LIMIT_MS) {
				slow.add(call[1] + " took " + median + " ms");
			}
		}
		assertThat(slow).as("calls slower than %d ms", LIMIT_MS).isEmpty();
	}

}
