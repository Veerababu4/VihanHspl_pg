package com.vihan.hospital.report;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.vihan.hospital.audit.AuditAction;
import com.vihan.hospital.audit.AuditService;
import com.vihan.hospital.branch.Branch;
import com.vihan.hospital.branch.BranchLookup;
import com.vihan.hospital.common.error.ApiException.BadRequestException;
import com.vihan.hospital.common.util.Masking;
import com.vihan.hospital.doctor.Doctor;
import com.vihan.hospital.doctor.DoctorRepository;

/**
 * Reception / IT Admin reports (FR-D6): each a date range plus an optional branch, shown on
 * screen and exported to CSV / XLSX. Plain SQL with GROUP BY: these are read-only summaries over
 * many rows, so the database does the counting.
 */
@Service
public class ReportService {

	public enum ReportType {
		DAILY_REGISTER("daily-register", "Daily appointment register", 31),
		DOCTOR_SUMMARY("doctor-summary", "Doctor-wise summary", 92),
		BRANCH_SUMMARY("branch-summary", "Branch-wise summary", 92),
		REGISTRATIONS("registrations", "Patient registrations", 92),
		CHANNELS("channels", "Online vs counter bookings", 92),
		NO_SHOW_CANCEL("no-show-cancel", "No-show and cancellation rates", 92);

		final String slug;
		final String title;
		final int maxDays;

		ReportType(String slug, String title, int maxDays) {
			this.slug = slug;
			this.title = title;
			this.maxDays = maxDays;
		}

		static ReportType of(String slug) {
			return Arrays.stream(values()).filter(t -> t.slug.equals(slug)).findFirst()
				.orElseThrow(() -> new BadRequestException("UNKNOWN_REPORT", "Unknown report: " + slug));
		}
	}

	/** How the "over time" reports group dates; the DATE_TRUNC unit is a constant, never user text. */
	public enum Period {
		DAY("day", DateTimeFormatter.ISO_LOCAL_DATE), MONTH("month", DateTimeFormatter.ofPattern("yyyy-MM"));

		final String trunc;
		final DateTimeFormatter label;

		Period(String trunc, DateTimeFormatter label) {
			this.trunc = trunc;
			this.label = label;
		}

		static Period of(String s) {
			if (s == null || s.isBlank()) {
				return DAY;
			}
			try {
				return valueOf(s.strip().toUpperCase(Locale.ROOT));
			}
			catch (IllegalArgumentException e) {
				throw new BadRequestException("INVALID_PERIOD", "Period must be day or month");
			}
		}
	}

	private static final String NOT_CANCELLED = "COUNT(CASE WHEN a.status <> 'CANCELLED' THEN 1 END)";
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

	/** The resolved filters of one report run. */
	private record Query(ReportType type, LocalDate from, LocalDate to, Long branchId, Period period) {
	}

	private final JdbcTemplate jdbc;
	private final BranchLookup branches;
	private final DoctorRepository doctors;
	private final AuditService audit;
	private final Clock clock;

	public ReportService(JdbcTemplate jdbc, BranchLookup branches, DoctorRepository doctors, AuditService audit,
			Clock clock) {
		this.jdbc = jdbc;
		this.branches = branches;
		this.doctors = doctors;
		this.audit = audit;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public ReportTable run(String type, LocalDate from, LocalDate to, String branchCode, String period) {
		Query q = query(type, from, to, branchCode, period);
		Map<Long, Branch> branchMap = branches.byId();
		List<String> columns;
		List<List<Object>> rows = switch (q.type()) {
			case DAILY_REGISTER -> {
				columns = List.of("Date", "Branch", "Doctor", "Token", "Time", "Booking ref", "Patient", "Mobile", "Status",
						"Channel");
				yield dailyRegister(q);
			}
			case DOCTOR_SUMMARY -> {
				columns = List.of("Branch", "Doctor", "Speciality", "Appointments", "Walk-ins", "Completed", "Cancelled",
						"No-show", "Slots", "Utilisation %");
				yield doctorSummary(q, branchMap);
			}
			case BRANCH_SUMMARY -> {
				columns = List.of("Period", "Branch", "Total", "Booked", "Checked in", "Completed", "Cancelled", "No-show");
				yield branchSummary(q);
			}
			case REGISTRATIONS -> {
				columns = List.of("Period", "Branch", "New patients");
				yield registrations(q);
			}
			case CHANNELS -> {
				columns = List.of("Branch", "Online", "Counter", "Phone", "Walk-in", "Total", "Online %");
				yield channels(q);
			}
			case NO_SHOW_CANCEL -> {
				columns = List.of("Period", "Branch", "Doctor", "All bookings", "Cancelled", "Cancelled %", "No-show",
						"No-show %");
				yield noShowCancel(q, branchMap);
			}
		};
		return new ReportTable(q.type().title, subtitle(q, branchMap), columns, rows);
	}

	@Transactional
	public ResponseEntity<byte[]> export(String type, LocalDate from, LocalDate to, String branchCode, String period,
			String format) {
		TableExport.Format f = TableExport.parse(format);
		ReportTable table = run(type, from, to, branchCode, period);
		Query q = query(type, from, to, branchCode, period);
		String branch = q.branchId() == null ? "ALL" : branchCode.strip().toUpperCase(Locale.ROOT);
		audit.record(AuditAction.REPORT_EXPORTED, "REPORT", q.type().slug,
				q.from() + " to " + q.to() + " branch=" + branch + " format=" + f);
		return TableExport.download(table, f, q.type().slug + "-" + q.from() + "-to-" + q.to()
				+ (q.branchId() == null ? "" : "-" + branch));
	}

	// ---- reports ----

	/** Mobile numbers are masked: this report is printed and shared (FR-D6). */
	private List<List<Object>> dailyRegister(Query q) {
		Filter f = new Filter(q, "a.branch_id");
		return jdbc.query("""
				SELECT a.appt_date, b.city, d.full_name, a.token_no, a.start_at, a.booking_ref, a.patient_name,
				       a.patient_mobile, a.status, a.channel
				FROM appointment a JOIN doctor d ON d.doctor_id = a.doctor_id JOIN branch b ON b.branch_id = a.branch_id
				WHERE a.appt_date BETWEEN ? AND ?""" + f.sql + "\n" + """
				 ORDER BY a.appt_date, b.branch_id, d.full_name, a.token_no""",
				(rs, i) -> {
					LocalDateTime start = rs.getObject("start_at", LocalDateTime.class);
					return row(rs.getObject("appt_date", LocalDate.class).toString(), rs.getString("city"),
							rs.getString("full_name"), rs.getObject("token_no", Integer.class),
							start == null ? "Walk-in" : start.format(TIME), rs.getString("booking_ref"),
							rs.getString("patient_name"), Masking.mobile(rs.getString("patient_mobile")),
							label(rs.getString("status")), label(rs.getString("channel")));
				}, f.args);
	}

	/** Utilisation = booked slots ÷ slots that were bookable (not blocked). */
	private List<List<Object>> doctorSummary(Query q, Map<Long, Branch> branchMap) {
		record Key(long doctorId, long branchId) {
		}
		Filter fa = new Filter(q, "a.branch_id");
		Map<Key, long[]> appts = new HashMap<>();
		jdbc.query("""
				SELECT a.doctor_id, a.branch_id, %s total,
				       COUNT(CASE WHEN a.status <> 'CANCELLED' AND a.slot_id IS NOT NULL THEN 1 END) on_slots,
				       COUNT(CASE WHEN a.channel = 'WALK_IN' THEN 1 END) walk_ins,
				       COUNT(CASE WHEN a.status = 'COMPLETED' THEN 1 END) completed,
				       COUNT(CASE WHEN a.status = 'CANCELLED' THEN 1 END) cancelled,
				       COUNT(CASE WHEN a.status = 'NO_SHOW' THEN 1 END) no_show
				FROM appointment a WHERE a.appt_date BETWEEN ? AND ?""".formatted(NOT_CANCELLED) + fa.sql + "\n" + """
				 GROUP BY a.doctor_id, a.branch_id""",
				rs -> {
					appts.put(new Key(rs.getLong("doctor_id"), rs.getLong("branch_id")), new long[] { rs.getLong("total"),
							rs.getLong("on_slots"), rs.getLong("walk_ins"), rs.getLong("completed"), rs.getLong("cancelled"),
							rs.getLong("no_show") });
				}, fa.args);
		Filter fs = new Filter(q, "s.branch_id");
		Map<Key, Long> slotCounts = new HashMap<>();
		jdbc.query("""
				SELECT s.doctor_id, s.branch_id, COUNT(*) n FROM slot s
				WHERE s.slot_date BETWEEN ? AND ? AND s.status <> 'BLOCKED'""" + fs.sql + "\n" + """
				 GROUP BY s.doctor_id, s.branch_id""",
				rs -> {
					slotCounts.put(new Key(rs.getLong("doctor_id"), rs.getLong("branch_id")), rs.getLong("n"));
				}, fs.args);

		Map<Long, Doctor> doctorMap = doctorMap();
		List<Long> branchOrder = new ArrayList<>(branchMap.keySet());
		List<Key> keys = new ArrayList<>(appts.keySet());
		slotCounts.keySet().stream().filter(k -> !appts.containsKey(k)).forEach(keys::add);
		keys.sort(Comparator.comparing((Key k) -> branchOrder.indexOf(k.branchId()))
			.thenComparing(k -> doctorName(doctorMap, k.doctorId())));
		return keys.stream().map(k -> {
			long[] c = appts.getOrDefault(k, new long[6]);
			long slotTotal = slotCounts.getOrDefault(k, 0L);
			Doctor d = doctorMap.get(k.doctorId());
			return row(branchMap.get(k.branchId()).getCity(), doctorName(doctorMap, k.doctorId()),
					d == null ? "" : d.getSpeciality().getName(), c[0], c[2], c[3], c[4], c[5], slotTotal, pct(c[1], slotTotal));
		}).toList();
	}

	private List<List<Object>> branchSummary(Query q) {
		Filter f = new Filter(q, "a.branch_id");
		return jdbc.query("""
				SELECT CAST(DATE_TRUNC('%1$s', a.appt_date) AS DATE) p, b.branch_id, b.city, %2$s total,
				       COUNT(CASE WHEN a.status = 'BOOKED' THEN 1 END) booked,
				       COUNT(CASE WHEN a.status = 'CHECKED_IN' THEN 1 END) checked_in,
				       COUNT(CASE WHEN a.status = 'COMPLETED' THEN 1 END) completed,
				       COUNT(CASE WHEN a.status = 'CANCELLED' THEN 1 END) cancelled,
				       COUNT(CASE WHEN a.status = 'NO_SHOW' THEN 1 END) no_show
				FROM appointment a JOIN branch b ON b.branch_id = a.branch_id
				WHERE a.appt_date BETWEEN ? AND ?""".formatted(q.period().trunc, NOT_CANCELLED) + f.sql + "\n" + """
				 GROUP BY 1, b.branch_id, b.city ORDER BY 1, 2""",
				(rs, i) -> row(periodLabel(q, rs.getObject("p", LocalDate.class)), rs.getString("city"), rs.getLong("total"),
						rs.getLong("booked"), rs.getLong("checked_in"), rs.getLong("completed"), rs.getLong("cancelled"),
						rs.getLong("no_show")),
				f.args);
	}

	private List<List<Object>> registrations(Query q) {
		List<Object> args = new ArrayList<>(List.of(q.from().atStartOfDay(), q.to().plusDays(1).atStartOfDay()));
		String branchSql = "";
		if (q.branchId() != null) {
			branchSql = " AND p.registered_branch_id = ?";
			args.add(q.branchId());
		}
		return jdbc.query("""
				SELECT CAST(DATE_TRUNC('%1$s', p.created_at) AS DATE) per, b.branch_id, b.city, COUNT(*) n
				FROM patient p JOIN branch b ON b.branch_id = p.registered_branch_id
				WHERE p.created_at >= ? AND p.created_at < ?""".formatted(q.period().trunc) + branchSql + "\n" + """
				 GROUP BY 1, b.branch_id, b.city ORDER BY 1, 2""",
				(rs, i) -> row(periodLabel(q, rs.getObject("per", LocalDate.class)), rs.getString("city"), rs.getLong("n")),
				args.toArray());
	}

	/** All bookings made, cancelled ones included: this shows how people book, not who came. */
	private List<List<Object>> channels(Query q) {
		Filter f = new Filter(q, "a.branch_id");
		List<long[]> counts = new ArrayList<>();
		List<List<Object>> rows = new ArrayList<>(jdbc.query("""
				SELECT b.branch_id, b.city,
				       COUNT(CASE WHEN a.channel = 'ONLINE' THEN 1 END) n_online,
				       COUNT(CASE WHEN a.channel = 'COUNTER' THEN 1 END) n_counter,
				       COUNT(CASE WHEN a.channel = 'PHONE' THEN 1 END) n_phone,
				       COUNT(CASE WHEN a.channel = 'WALK_IN' THEN 1 END) n_walk_in,
				       COUNT(*) total
				FROM appointment a JOIN branch b ON b.branch_id = a.branch_id
				WHERE a.appt_date BETWEEN ? AND ?""" + f.sql + "\n" + """
				 GROUP BY b.branch_id, b.city ORDER BY b.branch_id""",
				(rs, i) -> {
					long[] c = { rs.getLong("n_online"), rs.getLong("n_counter"), rs.getLong("n_phone"), rs.getLong("n_walk_in"),
							rs.getLong("total") };
					counts.add(c);
					return channelRow(rs.getString("city"), c);
				}, f.args));
		if (q.branchId() == null && counts.size() > 1) {
			long[] sum = new long[5];
			counts.forEach(c -> Arrays.setAll(sum, i -> sum[i] + c[i]));
			rows.add(channelRow("All branches", sum));
		}
		return rows;
	}

	/** Cancelled % of all bookings; no-show % of the bookings that were not cancelled. */
	private List<List<Object>> noShowCancel(Query q, Map<Long, Branch> branchMap) {
		Filter f = new Filter(q, "a.branch_id");
		Map<Long, Doctor> doctorMap = doctorMap();
		List<Long> branchOrder = new ArrayList<>(branchMap.keySet());
		record Line(LocalDate period, long branchId, long doctorId, long total, long cancelled, long noShow) {
		}
		List<Line> lines = jdbc.query("""
				SELECT CAST(DATE_TRUNC('%1$s', a.appt_date) AS DATE) p, a.branch_id, a.doctor_id, COUNT(*) total,
				       COUNT(CASE WHEN a.status = 'CANCELLED' THEN 1 END) cancelled,
				       COUNT(CASE WHEN a.status = 'NO_SHOW' THEN 1 END) no_show
				FROM appointment a WHERE a.appt_date BETWEEN ? AND ?""".formatted(q.period().trunc) + f.sql + "\n" + """
				 GROUP BY 1, a.branch_id, a.doctor_id""",
				(rs, i) -> new Line(rs.getObject("p", LocalDate.class), rs.getLong("branch_id"), rs.getLong("doctor_id"),
						rs.getLong("total"), rs.getLong("cancelled"), rs.getLong("no_show")),
				f.args);
		return lines.stream()
			.sorted(Comparator.comparing(Line::period)
				.thenComparing(l -> branchOrder.indexOf(l.branchId()))
				.thenComparing(l -> doctorName(doctorMap, l.doctorId())))
			.map(l -> row(periodLabel(q, l.period()), branchMap.get(l.branchId()).getCity(),
					doctorName(doctorMap, l.doctorId()), l.total(), l.cancelled(), pct(l.cancelled(), l.total()),
					l.noShow(), pct(l.noShow(), l.total() - l.cancelled())))
			.toList();
	}

	// ---- helpers ----

	/** The optional branch condition and the bind values that go with it (dates first). */
	private static final class Filter {

		final String sql;
		final Object[] args;

		Filter(Query q, String column) {
			this.sql = q.branchId() == null ? "" : " AND " + column + " = ?";
			this.args = q.branchId() == null ? new Object[] { q.from(), q.to() }
					: new Object[] { q.from(), q.to(), q.branchId() };
		}
	}

	private Query query(String type, LocalDate from, LocalDate to, String branchCode, String period) {
		ReportType t = ReportType.of(type);
		LocalDate today = LocalDate.now(clock);
		LocalDate start = from == null ? today : from;
		LocalDate end = to == null ? start : to;
		if (end.isBefore(start)) {
			throw new BadRequestException("BAD_RANGE", "The end date is before the start date");
		}
		if (ChronoUnit.DAYS.between(start, end) + 1 > t.maxDays) {
			throw new BadRequestException("RANGE_TOO_LONG", "This report covers at most " + t.maxDays + " days");
		}
		return new Query(t, start, end, branches.idOf(branchCode), Period.of(period));
	}

	private static String subtitle(Query q, Map<Long, Branch> branchMap) {
		String range = q.from().equals(q.to()) ? q.from().toString() : q.from() + " to " + q.to();
		String branch = q.branchId() == null ? "All branches" : branchMap.get(q.branchId()).getCity();
		boolean overTime = q.type() == ReportType.BRANCH_SUMMARY || q.type() == ReportType.REGISTRATIONS
				|| q.type() == ReportType.NO_SHOW_CANCEL;
		return range + " · " + branch + (overTime ? " · by " + q.period().name().toLowerCase(Locale.ROOT) : "");
	}

	private static String periodLabel(Query q, LocalDate d) {
		return d.format(q.period().label);
	}

	private Map<Long, Doctor> doctorMap() {
		return doctors.findAllByOrderByFullNameAsc().stream().collect(Collectors.toMap(Doctor::getId, Function.identity()));
	}

	private static String doctorName(Map<Long, Doctor> doctorMap, long id) {
		Doctor d = doctorMap.get(id);
		return d == null ? "Doctor " + id : d.getFullName();
	}

	private static List<Object> channelRow(String branch, long[] c) {
		return row(branch, c[0], c[1], c[2], c[3], c[4], pct(c[0], c[4]));
	}

	/** One decimal place; null (an empty cell) when there is nothing to divide by. */
	static Double pct(long part, long whole) {
		return whole == 0 ? null : Math.round(part * 1000.0 / whole) / 10.0;
	}

	/** {@code NO_SHOW -> No show}, {@code WALK_IN -> Walk in} */
	private static String label(String code) {
		String s = code.replace('_', ' ').toLowerCase(Locale.ROOT);
		return Character.toUpperCase(s.charAt(0)) + s.substring(1);
	}

	/** Like List.of, but allows null cells. */
	private static List<Object> row(Object... cells) {
		return Arrays.asList(cells);
	}

}
