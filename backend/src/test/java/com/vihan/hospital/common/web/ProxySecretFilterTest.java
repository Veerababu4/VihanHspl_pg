package com.vihan.hospital.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ProxySecretFilterTest {

	private static MockHttpServletResponse call(ProxySecretFilter filter, String uri, String header) throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
		if (header != null) {
			request.addHeader(ProxySecretFilter.HEADER, header);
		}
		MockHttpServletResponse response = new MockHttpServletResponse();
		filter.doFilter(request, response, new MockFilterChain());
		return response;
	}

	@Test
	void withASecretOnlyTheProxyGetsThrough() throws Exception {
		ProxySecretFilter filter = new ProxySecretFilter("s3cret-value");
		assertThat(call(filter, "/api/public/branches", "s3cret-value").getStatus()).isEqualTo(200);
		assertThat(call(filter, "/api/public/branches", null).getStatus()).isEqualTo(404);
		assertThat(call(filter, "/api/public/branches", "wrong").getStatus()).isEqualTo(404);
		assertThat(call(filter, "/files/doctors/x.jpg", null).getStatus()).isEqualTo(404);
		assertThat(call(filter, "/actuator/health", null).getStatus()).isEqualTo(200);   // host health check
	}

	@Test
	void blankSecretTurnsTheFilterOff() throws Exception {
		assertThat(call(new ProxySecretFilter(""), "/api/public/branches", null).getStatus()).isEqualTo(200);
	}

}
