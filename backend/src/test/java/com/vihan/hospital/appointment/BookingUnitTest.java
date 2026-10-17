package com.vihan.hospital.appointment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.vihan.hospital.common.error.ApiException.TooManyRequestsException;
import com.vihan.hospital.common.util.Mobiles;
import com.vihan.hospital.common.web.RateLimiter;
import com.vihan.hospital.common.web.RateLimiter.Limit;
import com.vihan.hospital.common.web.RateLimitInterceptorAccess;
import com.vihan.hospital.config.AppProperties;

/** Small pure pieces of M5: mobile numbers, booking references, rate limits. */
class BookingUnitTest {

	@Test
	void mobilesAreNormalisedToTenDigits() {
		assertThat(Mobiles.normalize("9876543210")).isEqualTo("9876543210");
		assertThat(Mobiles.normalize("+91 98765 43210")).isEqualTo("9876543210");
		assertThat(Mobiles.normalize("+91-98765-43210")).isEqualTo("9876543210");
		assertThat(Mobiles.normalize("919876543210")).isEqualTo("9876543210");
		assertThat(Mobiles.normalize("09876543210")).isEqualTo("9876543210");
		assertThat(Mobiles.normalize("(98765) 43210")).isEqualTo("9876543210");
	}

	@Test
	void invalidMobilesAreRejected() {
		for (String bad : List.of("5876543210", "987654321", "98765432101", "abcdefghij", "", "+1 9876543210")) {
			assertThat(Mobiles.normalize(bad)).as(bad).isNull();
		}
		assertThat(Mobiles.normalize(null)).isNull();
	}

	@Test
	void bookingRefsHaveTheDesignFormatWithoutLookAlikes() {
		Set<String> seen = new HashSet<>();
		for (int i = 0; i < 2000; i++) {
			String ref = BookingRefs.generate(LocalDate.of(2026, 10, 6));
			assertThat(ref).matches("VMH-261006-[2-9A-HJ-NP-Z]{4}");
			seen.add(ref);
		}
		assertThat(seen.size()).isGreaterThan(1990);   // ~1 million combinations per day
	}

	@Test
	void typedRefsAreCleanedUp() {
		assertThat(BookingRefs.normalize(" vmh-261006-7k3q ")).isEqualTo("VMH-261006-7K3Q");
		assertThat(BookingRefs.normalize("VMH2610067K3Q")).isEqualTo("VMH-261006-7K3Q");
		assertThat(BookingRefs.normalize(null)).isEmpty();
	}

	@Test
	void rateLimiterAllowsTheLimitPerMinutePerIpThenAsksToWait() {
		AppProperties props = new AppProperties(false, List.of(), "x", "/files", null, null, null, null,
				new AppProperties.RateLimit(3, 5, 5, 5), null);
		RateLimiter limiter = new RateLimiter(props, Clock.fixed(Instant.parse("2026-10-06T10:00:00Z"), ZoneId.of("UTC")));

		for (int i = 0; i < 3; i++) {
			limiter.check(Limit.BOOKING, "10.0.0.1");
		}
		assertThatThrownBy(() -> limiter.check(Limit.BOOKING, "10.0.0.1"))
			.isInstanceOf(TooManyRequestsException.class)
			.satisfies(e -> assertThat(((TooManyRequestsException) e).getRetryAfterSeconds()).isBetween(1L, 60L));
		assertThatCode(() -> limiter.check(Limit.BOOKING, "10.0.0.2")).doesNotThrowAnyException();   // other IP
		assertThatCode(() -> limiter.check(Limit.LOOKUP, "10.0.0.1")).doesNotThrowAnyException();    // other limit
	}

	@Test
	void eachEndpointGetsTheRightLimit() {
		assertThat(RateLimitInterceptorAccess.limitFor("POST", "/api/public/appointments")).isEqualTo(Limit.BOOKING);
		assertThat(RateLimitInterceptorAccess.limitFor("POST", "/api/public/appointments/cancel")).isEqualTo(Limit.BOOKING);
		assertThat(RateLimitInterceptorAccess.limitFor("POST", "/api/public/appointments/reschedule")).isEqualTo(Limit.BOOKING);
		assertThat(RateLimitInterceptorAccess.limitFor("POST", "/api/public/appointments/email")).isEqualTo(Limit.BOOKING);
		assertThat(RateLimitInterceptorAccess.limitFor("POST", "/api/public/appointments/lookup")).isEqualTo(Limit.LOOKUP);
		assertThat(RateLimitInterceptorAccess.limitFor("GET", "/api/public/doctors/7/slots")).isEqualTo(Limit.SLOTS);
		assertThat(RateLimitInterceptorAccess.limitFor("GET", "/api/public/doctors/7/slot-days")).isEqualTo(Limit.SLOTS);
		assertThat(RateLimitInterceptorAccess.limitFor("POST", "/api/auth/login")).isEqualTo(Limit.LOGIN);
		assertThat(RateLimitInterceptorAccess.limitFor("GET", "/api/public/doctors")).isNull();
		assertThat(RateLimitInterceptorAccess.limitFor("GET", "/api/public/branches")).isNull();
	}

}
