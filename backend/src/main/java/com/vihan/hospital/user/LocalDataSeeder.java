package com.vihan.hospital.user;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.vihan.hospital.config.AppProperties;

/**
 * SAMPLE DATA ONLY ({@code app.sample-data}: local profile, or a cloud demo with SAMPLE_DATA=true):
 * sample staff logins for testing (DESIGN.md §9.4), all with the DEV_SAMPLE_PASSWORD from .env and
 * no forced password change. Never enabled where real patients are recorded.
 * Later milestones add sample slots, appointments, patients and visits here.
 *
 * <pre>
 * itadmin                               IT Admin
 * reception.mlg / .hzn / .kdd           Receptionist at that branch
 * doctor001 … doctor020                 Doctor login for sample doctor SAMPLE-001 … SAMPLE-020
 * </pre>
 */
@Component
@ConditionalOnProperty(name = "app.sample-data", havingValue = "true")
@Order(2)
public class LocalDataSeeder implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(LocalDataSeeder.class);

	private record SampleUser(String username, String fullName, Role role, Long doctorId, Long homeBranchId) {
	}

	private final AppUserRepository users;
	private final JdbcTemplate jdbc;
	private final PasswordEncoder passwordEncoder;
	private final TransactionTemplate tx;
	private final String password;

	public LocalDataSeeder(AppUserRepository users, JdbcTemplate jdbc, PasswordEncoder passwordEncoder,
			TransactionTemplate tx, AppProperties props) {
		this.users = users;
		this.jdbc = jdbc;
		this.passwordEncoder = passwordEncoder;
		this.tx = tx;
		this.password = props.auth().devSamplePassword();
	}

	@Override
	public void run(ApplicationArguments args) {
		if (password == null || password.isBlank()) {
			log.info("DEV_SAMPLE_PASSWORD not set: sample staff logins not created");
			return;
		}
		tx.executeWithoutResult(status -> seedUsers());
	}

	private void seedUsers() {
		List<SampleUser> wanted = new ArrayList<>();
		wanted.add(new SampleUser("itadmin", "Sample IT Admin", Role.IT_ADMIN, null, branchId("MLG")));
		for (String code : List.of("MLG", "HZN", "KDD")) {
			wanted.add(new SampleUser("reception." + code.toLowerCase(), "Sample Receptionist " + code,
					Role.RECEPTIONIST, null, branchId(code)));
		}
		jdbc.query("SELECT doctor_id, full_name, registration_no FROM doctor WHERE registration_no LIKE 'SAMPLE-%'"
				+ " ORDER BY registration_no", rs -> {
			String number = rs.getString("registration_no").substring("SAMPLE-".length());
			wanted.add(new SampleUser("doctor" + number, rs.getString("full_name"), Role.DOCTOR,
					rs.getLong("doctor_id"), null));
		});

		List<SampleUser> missing = wanted.stream().filter(u -> !users.existsByUsername(u.username())).toList();
		if (missing.isEmpty()) {
			return;
		}
		String hash = passwordEncoder.encode(password);   // one BCrypt hash for all: same password
		for (SampleUser s : missing) {
			AppUser user = new AppUser(s.username(), hash);
			user.updateProfile(s.fullName(), s.role(), s.doctorId(), s.homeBranchId(), null, null, true);
			user.setPassword(hash, false);
			users.save(user);
		}
		log.info("Created {} sample staff logins (password = DEV_SAMPLE_PASSWORD)", missing.size());
	}

	private Long branchId(String code) {
		return jdbc.queryForObject("SELECT branch_id FROM branch WHERE code = ?", Long.class, code);
	}

}
