package com.vihan.hospital.patient;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * SAMPLE DATA ONLY ({@code app.sample-data}): about 40 <b>fictional</b> patients (DESIGN.md §9.4) with checksum-valid
 * fictional Aadhaar numbers, encrypted with the local key like real ones. The numbers come from a
 * fixed random seed, so each sample patient is recognised by its Aadhaar hash and created only
 * once; patients registered by hand are never touched. Every sixth pair shares a mobile number
 * (a family), to show the "same mobile, different person" case. M8 adds about 60 past visits.
 */
@Component
@ConditionalOnProperty(name = "app.sample-data", havingValue = "true")
@Order(3)
public class SamplePatientSeeder implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(SamplePatientSeeder.class);
	private static final int COUNT = 40;

	private static final List<String> MEN = List.of("Ravi", "Srinivas", "Venkatesh", "Naresh", "Mahesh", "Suresh",
			"Ramesh", "Prasad", "Krishna", "Anil", "Kiran", "Raju", "Sai Teja", "Nagaraju", "Shiva", "Harish");
	private static final List<String> WOMEN = List.of("Lakshmi", "Sravani", "Padma", "Swathi", "Anitha", "Kavitha",
			"Sunitha", "Bhavani", "Divya", "Manasa", "Sirisha", "Jyothi", "Renuka", "Saritha", "Madhavi", "Haritha");
	private static final List<String> SURNAMES = List.of("Reddy", "Goud", "Rao", "Naidu", "Yadav", "Chary", "Kumar",
			"Sharma", "Kandula", "Bandi", "Gaddam", "Pasupuleti", "Vemula", "Thota", "Mekala", "Bollam");
	private static final List<String> CITIES = List.of("Miryalaguda", "Huzurnagar", "Kodad", "Nalgonda", "Suryapet");
	private static final List<String> BLOOD_GROUPS = List.of("O+", "B+", "A+", "AB+", "O-", "B-", "A-");

	private final PatientRepository patients;
	private final AadhaarCrypto crypto;
	private final JdbcTemplate jdbc;
	private final TransactionTemplate tx;
	private final Clock clock;

	public SamplePatientSeeder(PatientRepository patients, AadhaarCrypto crypto, JdbcTemplate jdbc, TransactionTemplate tx,
			Clock clock) {
		this.patients = patients;
		this.crypto = crypto;
		this.jdbc = jdbc;
		this.tx = tx;
		this.clock = clock;
	}

	@Override
	public void run(ApplicationArguments args) {
		int[] created = tx.execute(status -> seed());
		if (created != null && created[0] + created[1] > 0) {
			log.info("Created {} fictional sample patients and {} past visits", created[0], created[1]);
		}
	}

	/** @return [patients created, visits created] */
	private int[] seed() {
		Random r = new Random(2026);   // fixed: the same 40 people every time
		List<Long> branchIds = jdbc.queryForList("SELECT branch_id FROM branch ORDER BY branch_id", Long.class);
		LocalDate today = LocalDate.now(clock);
		LocalDateTime now = LocalDateTime.now(clock);
		String familyMobile = null;
		int created = 0;
		List<String> hashes = new ArrayList<>();
		for (int i = 0; i < COUNT; i++) {
			// Draw every value in the same order on every run, created or not, so the people stay the same.
			boolean female = r.nextBoolean();
			String first = (female ? WOMEN : MEN).get(r.nextInt(16));
			String surname = SURNAMES.get(r.nextInt(SURNAMES.size()));
			int age = 1 + r.nextInt(85);
			String ownMobile = "9" + String.format("%09d", r.nextInt(1_000_000_000));
			String aadhaar = fictionalAadhaar(r);
			String city = CITIES.get(r.nextInt(CITIES.size()));
			String blood = BLOOD_GROUPS.get(r.nextInt(BLOOD_GROUPS.size()));
			long branchId = branchIds.get(r.nextInt(branchIds.size()));
			boolean withContact = r.nextInt(3) > 0;

			// Pairs 0-1, 6-7, 12-13 ... are families sharing one phone.
			String mobile = i % 6 == 1 ? familyMobile : ownMobile;
			familyMobile = ownMobile;
			String hash = crypto.hash(aadhaar);
			hashes.add(hash);
			if (patients.findByAadhaarHash(hash).isPresent()) {
				continue;
			}
			String uhid = "VMH-%02d-%06d".formatted(today.getYear() % 100, patients.nextUhidNumber());
			Patient p = new Patient(uhid, first + " " + surname, PatientService.estimatedDob(age, today), true,
					female ? "F" : "M", mobile, branchId, now);
			if (withContact) {
				p.updateContact(null, (10 + i) + "-" + (100 + i) + ", Sample Colony", city, blood,
						(female ? "Sample Husband " : "Sample Wife ") + surname, ownMobile.substring(0, 9) + "1");
			}
			p.setAadhaar(crypto.encrypt(aadhaar), hash, aadhaar.substring(8));
			patients.save(p);
			created++;
		}
		return new int[] { created, seedVisits(hashes) };
	}

	// ---- past visits (M8) ----

	private record Case(String complaint, String diagnosis, String advice, String[][] medicines) {
	}

	private static final List<Case> CASES = List.of(
			new Case("Fever and body pains for 3 days", "Viral fever", "Rest, plenty of fluids",
					new String[][] { { "Paracetamol", "650 mg", "1-1-1", "3 days", "after food" } }),
			new Case("Burning urination", "Urinary tract infection", "Drink 3 litres of water a day",
					new String[][] { { "Nitrofurantoin", "100 mg", "1-0-1", "5 days", "after food" } }),
			new Case("Follow-up for sugar control", "Type 2 diabetes mellitus", "Diet control, walk 30 minutes daily",
					new String[][] { { "Metformin", "500 mg", "1-0-1", "30 days", "after food" } }),
			new Case("Headache, high BP at home", "Essential hypertension", "Less salt, recheck BP in 2 weeks",
					new String[][] { { "Amlodipine", "5 mg", "1-0-0", "30 days", null } }),
			new Case("Cough and cold", "Acute upper respiratory infection", "Steam inhalation",
					new String[][] { { "Cetirizine", "10 mg", "0-0-1", "5 days", null }, { "Paracetamol", "500 mg", "1-0-1", "3 days", "if fever" } }),
			new Case("Knee pain on climbing stairs", "Osteoarthritis of knee", "Weight reduction, physiotherapy",
					new String[][] { { "Aceclofenac", "100 mg", "1-0-1", "7 days", "after food" }, { "Calcium + Vitamin D3", "1 tab", "0-1-0", "30 days", null } }),
			new Case("Acidity after meals", "Gastritis", "Avoid spicy food, small frequent meals",
					new String[][] { { "Pantoprazole", "40 mg", "1-0-0", "14 days", "before breakfast" } }),
			new Case("Itchy rash on arms", "Allergic dermatitis", "Avoid the soap that started it",
					new String[][] { { "Levocetirizine", "5 mg", "0-0-1", "7 days", null }, { "Calamine lotion", null, "twice daily", "7 days", "apply locally" } }));

	/**
	 * About 60 completed past visits for the sample patients (DESIGN.md §9.4), with vitals, a
	 * diagnosis and a prescription, plus a medical profile for some. Only for sample patients that
	 * have no visits yet, so it runs once and never touches real records.
	 */
	private int seedVisits(List<String> sampleHashes) {
		Random r = new Random(2027);
		List<long[]> doctorBranches = jdbc.query("""
				SELECT d.doctor_id, db.branch_id FROM doctor d JOIN doctor_branch db ON db.doctor_id = d.doctor_id
				WHERE d.is_active = TRUE AND d.registration_no LIKE 'SAMPLE-%' ORDER BY d.doctor_id, db.branch_id""",
				(rs, i) -> new long[] { rs.getLong(1), rs.getLong(2) });
		if (doctorBranches.isEmpty()) {
			return 0;
		}
		LocalDate today = LocalDate.now(clock);
		int created = 0;
		for (String hash : sampleHashes) {
			Patient p = patients.findByAadhaarHash(hash).orElse(null);
			int visitCount = 1 + r.nextInt(2);
			boolean profile = r.nextInt(3) == 0;
			if (p == null || jdbc.queryForObject("SELECT COUNT(*) FROM visit WHERE patient_id = ?", Integer.class, p.getId()) > 0) {
				continue;
			}
			for (int v = 0; v < visitCount; v++) {
				long[] db = doctorBranches.get(r.nextInt(doctorBranches.size()));
				Case c = CASES.get(r.nextInt(CASES.size()));
				LocalDate date = today.minusDays(7 + r.nextInt(170));
				SampleVitals row = new SampleVitals(100 + r.nextInt(50), 65 + r.nextInt(30), 60 + r.nextInt(40), 97 + r.nextInt(30) / 10.0,
						94 + r.nextInt(6), 20 + r.nextInt(70));
				jdbc.update("""
						INSERT INTO visit (patient_id, doctor_id, branch_id, visit_date, visit_type, status, bp_systolic, bp_diastolic,
						  pulse, temperature_f, spo2, weight_kg, chief_complaint, diagnosis, clinical_notes, advice)
						VALUES (?, ?, ?, ?, 'OP', 'COMPLETED', ?, ?, ?, ?, ?, ?, ?, ?, 'Sample visit (fictional).', ?)""",
						p.getId(), db[0], db[1], date, row.sys(), row.dia(), row.pulse(), row.temp(), row.spo2(), row.weight(),
						c.complaint(), c.diagnosis(), c.advice());
				Long visitId = jdbc.queryForObject("SELECT MAX(visit_id) FROM visit WHERE patient_id = ?", Long.class, p.getId());
				for (int i = 0; i < c.medicines().length; i++) {
					String[] m = c.medicines()[i];
					jdbc.update("""
							INSERT INTO prescription_item (visit_id, medicine_name, dosage, frequency, duration, instructions, sort_order)
							VALUES (?, ?, ?, ?, ?, ?, ?)""", visitId, m[0], m[1], m[2], m[3], m[4], i);
				}
				created++;
			}
			if (profile) {
				jdbc.update("""
						INSERT INTO patient_medical_profile (patient_id, allergies, chronic_conditions)
						VALUES (?, ?, ?) ON CONFLICT (patient_id) DO NOTHING""", p.getId(), r.nextBoolean() ? "Sulfa drugs" : null,
						r.nextBoolean() ? "Hypertension" : "Type 2 diabetes");
			}
		}
		return created;
	}

	/** Random vitals for one sample visit. */
	private record SampleVitals(int sys, int dia, int pulse, double temp, int spo2, int weight) {
	}

	/** 11 random digits (first one 2-9) plus the Verhoeff check digit. */
	static String fictionalAadhaar(Random r) {
		StringBuilder sb = new StringBuilder().append(2 + r.nextInt(8));
		for (int i = 0; i < 10; i++) {
			sb.append(r.nextInt(10));
		}
		return sb.append(Aadhaar.verhoeffCheckDigit(sb.toString())).toString();
	}

}
