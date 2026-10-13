package com.vihan.hospital;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.jayway.jsonpath.JsonPath;
import com.vihan.hospital.storage.FileStorageService;

/**
 * M2 IT Admin APIs. Login arrives in M3, so these use mock users with roles.
 * Most tests are @Transactional and roll back, keeping the sample data unchanged for other tests.
 */
@VihanIntegrationTest
class AdminApiIntegrationTest {


	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	FileStorageService storage;

	private long id(String sql, Object... args) {
		return jdbc.queryForObject(sql, Long.class, args);
	}

	private String doctorJson(String regNo, int practiceStartYear) {
		long specialityId = id("SELECT speciality_id FROM speciality WHERE name = 'ENT'");
		long hzn = id("SELECT branch_id FROM branch WHERE code = 'HZN'");
		long kdd = id("SELECT branch_id FROM branch WHERE code = 'KDD'");
		return """
				{"fullName":"Dr. Test Kumar","gender":"M","specialityId":%d,"qualification":"MBBS, MS (ENT)",
				 "designation":"Consultant","registrationNo":"%s","practiceStartYear":%d,"languages":"Telugu",
				 "bio":"Test","consultationFee":450.50,"branchIds":[%d,%d],"active":true}"""
			.formatted(specialityId, regNo, practiceStartYear, hzn, kdd);
	}

	// ---- access control ----

	@Test
	void adminApisRequireLogin() throws Exception {
		mvc.perform(get("/api/admin/doctors"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
	}

	@Test
	@WithMockUser(roles = "RECEPTIONIST")
	void adminApisAreForItAdminOnly() throws Exception {
		mvc.perform(get("/api/admin/doctors"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("FORBIDDEN"));
	}

	// ---- doctors ----

	@Test
	@Transactional
	@WithMockUser(roles = "IT_ADMIN")
	void createdDoctorAppearsOnThePublicSite() throws Exception {
		String body = mvc.perform(post("/api/admin/doctors").contentType(MediaType.APPLICATION_JSON)
				.content(doctorJson("TEST-123", 2015)))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.branchIds.length()").value(2))
			.andExpect(jsonPath("$.consultationFee").value(450.50))
			.andReturn().getResponse().getContentAsString();
		int newId = JsonPath.read(body, "$.id");

		mvc.perform(get("/api/public/doctors").param("branch", "HZN"))
			.andExpect(jsonPath("$[*].id", hasItem(newId)));
		mvc.perform(get("/api/public/doctors/{id}", newId))
			.andExpect(jsonPath("$.speciality.name").value("ENT"))
			.andExpect(jsonPath("$.branches[*].code", org.hamcrest.Matchers.contains("HZN", "KDD")));
	}

	@Test
	@Transactional
	@WithMockUser(roles = "IT_ADMIN")
	void duplicateRegistrationNumberIsRejected() throws Exception {
		mvc.perform(post("/api/admin/doctors").contentType(MediaType.APPLICATION_JSON)
				.content(doctorJson("sample-001", 2015)))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("REGISTRATION_NO_EXISTS"));
	}

	@Test
	@WithMockUser(roles = "IT_ADMIN")
	void invalidDoctorIsRejectedWithFieldErrors() throws Exception {
		mvc.perform(post("/api/admin/doctors").contentType(MediaType.APPLICATION_JSON)
				.content("{\"fullName\":\"\",\"gender\":\"X\",\"branchIds\":[],\"active\":true}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.fieldErrors[*].field", hasItem("fullName")))
			.andExpect(jsonPath("$.fieldErrors[*].field", hasItem("branchIds")))
			.andExpect(jsonPath("$.fieldErrors[*].field", hasItem("gender")));

		mvc.perform(post("/api/admin/doctors").contentType(MediaType.APPLICATION_JSON)
				.content(doctorJson("TEST-FUTURE", 2099)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_PRACTICE_YEAR"));
	}

	@Test
	@Transactional
	@WithMockUser(roles = "IT_ADMIN")
	void photoUploadIsResizedAndServed() throws Exception {
		long doctorId = id("SELECT doctor_id FROM doctor WHERE registration_no = 'SAMPLE-002'");
		MockMultipartFile png = new MockMultipartFile("file", "photo.png", "image/png", png(2000, 1000));

		String body = mvc.perform(multipart("/api/admin/doctors/{id}/photo", doctorId).file(png))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.photoUrl", startsWith("/files/doctors/")))
			.andReturn().getResponse().getContentAsString();
		String url = JsonPath.read(body, "$.photoUrl");

		BufferedImage stored = ImageIO.read(new ByteArrayInputStream(stored(url)));
		assertThat(stored.getWidth()).isEqualTo(800);       // scaled down to fit 800x800
		assertThat(stored.getHeight()).isEqualTo(400);      // aspect ratio kept

		byte[] served = mvc.perform(get(url))
			.andExpect(status().isOk())
			.andExpect(content().contentType("image/jpeg"))
			.andReturn().getResponse().getContentAsByteArray();
		assertThat(ImageIO.read(new ByteArrayInputStream(served))).isNotNull();
	}

	@Test
	@WithMockUser(roles = "IT_ADMIN")
	void nonImageUploadIsRejected() throws Exception {
		long doctorId = id("SELECT doctor_id FROM doctor WHERE registration_no = 'SAMPLE-002'");
		MockMultipartFile fake = new MockMultipartFile("file", "photo.jpg", "image/jpeg", "<script>x</script>".getBytes());
		mvc.perform(multipart("/api/admin/doctors/{id}/photo", doctorId).file(fake))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("UNSUPPORTED_IMAGE"));

		MockMultipartFile huge = new MockMultipartFile("file", "big.jpg", "image/jpeg", new byte[6 * 1024 * 1024]);
		mvc.perform(multipart("/api/admin/doctors/{id}/photo", doctorId).file(huge))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("FILE_TOO_LARGE"));
	}

	// ---- specialities ----

	@Test
	@Transactional
	@WithMockUser(roles = "IT_ADMIN")
	void specialityWithActiveDoctorsCannotBeDeactivated() throws Exception {
		long cardiology = id("SELECT speciality_id FROM speciality WHERE name = 'Cardiology'");
		mvc.perform(put("/api/admin/specialities/{id}", cardiology).contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"Cardiology\",\"description\":\"x\",\"active\":false}"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("SPECIALITY_IN_USE"));

		mvc.perform(post("/api/admin/specialities").contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"cardiology\",\"active\":true}"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("SPECIALITY_EXISTS"));
	}

	// ---- branches ----

	@Test
	@WithMockUser(roles = "IT_ADMIN")
	void branchUpdateRefreshesThePublicCache() throws Exception {
		// Not @Transactional: the cache refreshes on commit, which is exactly what this checks.
		long kdd = id("SELECT branch_id FROM branch WHERE code = 'KDD'");
		String original = jdbc.queryForObject("SELECT opening_hours FROM branch WHERE branch_id = ?", String.class, kdd);
		mvc.perform(get("/api/public/branches")).andExpect(status().isOk());   // make sure the cache is loaded
		try {
			mvc.perform(put("/api/admin/branches/{id}", kdd).contentType(MediaType.APPLICATION_JSON)
					.content(branchJson("KDD", "OPD: 24 hours", "https://www.google.com/maps?q=Kodad&output=embed")))
				.andExpect(status().isOk());
			mvc.perform(get("/api/public/branches/KDD"))
				.andExpect(jsonPath("$.branch.openingHours").value("OPD: 24 hours"));
		}
		finally {
			mvc.perform(put("/api/admin/branches/{id}", kdd).contentType(MediaType.APPLICATION_JSON)
					.content(branchJson("KDD", original, "https://www.google.com/maps?q=Kodad,Telangana&output=embed")))
				.andExpect(status().isOk());
		}
	}

	@Test
	@WithMockUser(roles = "IT_ADMIN")
	void branchCodeIsFixedAndMapMustBeGoogleMaps() throws Exception {
		long kdd = id("SELECT branch_id FROM branch WHERE code = 'KDD'");
		mvc.perform(put("/api/admin/branches/{id}", kdd).contentType(MediaType.APPLICATION_JSON)
				.content(branchJson("KOD", "x", null)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("BRANCH_CODE_FIXED"));

		mvc.perform(put("/api/admin/branches/{id}", kdd).contentType(MediaType.APPLICATION_JSON)
				.content(branchJson("KDD", "x", "https://evil.example.com/maps")))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.fieldErrors[0].field").value("mapUrl"));
	}

	private static String branchJson(String code, String hours, String mapUrl) {
		return """
				{"code":"%s","name":"Vihan Multi Speciality Hospital - Kodad","address":"Address to be confirmed",
				 "city":"Kodad","district":"Suryapet","phone":"0000000003","emergencyPhone":"0000000103",
				 "mapUrl":%s,"openingHours":"%s","active":true}"""
			.formatted(code, mapUrl == null ? "null" : "\"" + mapUrl + "\"", hours);
	}

	// ---- gallery ----

	@Test
	@WithMockUser(roles = "IT_ADMIN")
	void galleryUploadCreatesThumbnailAndDeleteRemovesFiles() throws Exception {
		long hzn = id("SELECT branch_id FROM branch WHERE code = 'HZN'");
		MockMultipartFile jpeg = new MockMultipartFile("file", "ward.jpg", "image/jpeg", jpeg(1200, 900));

		String body = mvc.perform(multipart("/api/admin/gallery").file(jpeg)
				.param("branchId", String.valueOf(hzn)).param("title", "New ward").param("active", "true"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.thumbUrl", startsWith("/files/gallery/")))
			.andReturn().getResponse().getContentAsString();
		int imageId = JsonPath.read(body, "$.id");
		String image = JsonPath.read(body, "$.imageUrl");
		String thumb = JsonPath.read(body, "$.thumbUrl");
		assertThat(ImageIO.read(new ByteArrayInputStream(stored(thumb))).getWidth()).isEqualTo(480);

		mvc.perform(get("/api/public/gallery").param("branch", "HZN"))
			.andExpect(jsonPath("$[*].title", hasItem("New ward")));

		mvc.perform(delete("/api/admin/gallery/{id}", imageId)).andExpect(status().isNoContent());
		mvc.perform(get(image)).andExpect(status().isNotFound());
		mvc.perform(get(thumb)).andExpect(status().isNotFound());
	}

	/** The stored bytes behind a {@code /files/...} URL. */
	private byte[] stored(String url) {
		return storage.load(url.substring("/files/".length())).orElseThrow();
	}

	// ---- test images ----

	/** PNG with a transparent area, to check it is flattened onto white. */
	private static byte[] png(int width, int height) throws Exception {
		BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setColor(new Color(15, 133, 126));
		g.fillRect(0, 0, width / 2, height);
		g.dispose();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(img, "png", out);
		return out.toByteArray();
	}

	private static byte[] jpeg(int width, int height) throws Exception {
		BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(img, "jpg", out);
		return out.toByteArray();
	}

}
