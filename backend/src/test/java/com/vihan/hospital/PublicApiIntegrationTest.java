package com.vihan.hospital;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/** M2 public website API, checked against the known fictional sample data. */
@VihanIntegrationTest
class PublicApiIntegrationTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate jdbc;

	private long doctorId(String regNo) {
		return jdbc.queryForObject("SELECT doctor_id FROM doctor WHERE registration_no = ?", Long.class, regNo);
	}

	private long specialityId(String name) {
		return jdbc.queryForObject("SELECT speciality_id FROM speciality WHERE name = ?", Long.class, name);
	}

	@Test
	void directoryListsAllActiveDoctorsSortedByNameIgnoringDrPrefix() throws Exception {
		mvc.perform(get("/api/public/doctors"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(20))
			.andExpect(jsonPath("$[0].fullName").value("Dr. Anitha Gundla"))
			.andExpect(jsonPath("$[1].fullName").value("Dr. Ashok Nerella"))
			.andExpect(jsonPath("$[0].speciality.name").value("Neurology"))
			.andExpect(jsonPath("$[0].experienceYears").isNumber());
	}

	@Test
	void directoryFiltersByBranchSpecialityAndText() throws Exception {
		mvc.perform(get("/api/public/doctors").param("branch", "hzn").param("q", "CARDIO"))
			.andExpect(jsonPath("$[*].fullName", contains("Dr. Venkata Ramana Pulla")));

		mvc.perform(get("/api/public/doctors").param("speciality", String.valueOf(specialityId("Paediatrics"))))
			.andExpect(jsonPath("$[*].fullName", containsInAnyOrder("Dr. Mahesh Chityala", "Dr. Ramesh Banoth")));

		mvc.perform(get("/api/public/doctors").param("branch", "KDD"))
			.andExpect(jsonPath("$.length()").value(9))
			.andExpect(jsonPath("$[*].branches[*].code", hasItem("KDD")));
	}

	@Test
	void profileShowsWeeklyAvailabilityPerBranch() throws Exception {
		// SAMPLE-006 (cardiology) visits all three branches on different days
		mvc.perform(get("/api/public/doctors/{id}", doctorId("SAMPLE-006")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.registrationNo").value("SAMPLE-006"))
			.andExpect(jsonPath("$.branches[*].code", contains("MLG", "HZN", "KDD")))
			.andExpect(jsonPath("$.availability[*].branchCode", contains("MLG", "HZN", "KDD")))
			.andExpect(jsonPath("$.availability[0].days[*].dayOfWeek", contains(1, 4)))
			.andExpect(jsonPath("$.availability[1].days[*].dayOfWeek", contains(2, 5)))
			.andExpect(jsonPath("$.availability[2].days[*].dayOfWeek", contains(3, 6)))
			.andExpect(jsonPath("$.availability[0].days[0].sessions[0].start").value("10:00"))
			.andExpect(jsonPath("$.availability[0].days[0].sessions[0].slotMinutes").value(20));
	}

	@Test
	@Transactional   // rolled back after the test
	void inactiveDoctorIsHiddenFromThePublic() throws Exception {
		long id = doctorId("SAMPLE-001");
		jdbc.update("UPDATE doctor SET is_active = FALSE WHERE doctor_id = ?", id);

		mvc.perform(get("/api/public/doctors"))
			.andExpect(jsonPath("$.length()").value(19))
			.andExpect(jsonPath("$[*].id", not(hasItem((int) id))));
		mvc.perform(get("/api/public/doctors/{id}", id)).andExpect(status().isNotFound());
	}

	@Test
	void branchPageHasDetailsDoctorsAndPhotos() throws Exception {
		mvc.perform(get("/api/public/branches/kdd"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.branch.code").value("KDD"))
			.andExpect(jsonPath("$.branch.city").value("Kodad"))
			.andExpect(jsonPath("$.doctors.length()").value(9))
			.andExpect(jsonPath("$.gallery[*].branchCode", everyItem(org.hamcrest.Matchers.is("KDD"))))
			.andExpect(jsonPath("$.gallery.length()").value(2));

		mvc.perform(get("/api/public/branches/XYZ")).andExpect(status().isNotFound());
	}

	@Test
	void galleryCanBeFilteredByBranch() throws Exception {
		mvc.perform(get("/api/public/gallery")).andExpect(jsonPath("$.length()").value(6));
		mvc.perform(get("/api/public/gallery").param("branch", "HZN"))
			.andExpect(jsonPath("$[*].title", contains("OPD waiting area", "Laboratory")));
		mvc.perform(get("/api/public/gallery").param("branch", "NOPE")).andExpect(status().isNotFound());
	}

	@Test
	void specialitiesAreSortedByName() throws Exception {
		mvc.perform(get("/api/public/specialities"))
			.andExpect(jsonPath("$.length()").value(18))
			.andExpect(jsonPath("$[0].name").value("Anaesthesiology"))
			.andExpect(jsonPath("$[17].name").value("Urology"));
	}

}
