package com.vihan.hospital.common.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.servlet.HandlerInterceptor;

import com.vihan.hospital.common.web.RateLimiter.Limit;

/**
 * Applies the per-IP limits before the request body is even read, so floods of invalid requests
 * are throttled too (DESIGN.md §12.4). Registered for /api/public/** and /api/auth/login.
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

	private static final AntPathMatcher PATHS = new AntPathMatcher();

	private final RateLimiter limiter;

	public RateLimitInterceptor(RateLimiter limiter) {
		this.limiter = limiter;
	}

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		Limit limit = limitFor(request.getMethod(), request.getRequestURI());
		if (limit != null) {
			limiter.check(limit, request.getRemoteAddr());   // throws 429
		}
		return true;
	}

	static Limit limitFor(String method, String path) {
		if ("POST".equals(method)) {
			if (path.equals("/api/public/appointments/lookup") || path.equals("/api/public/appointments/find")) {
				return Limit.LOOKUP;
			}
			if (path.equals("/api/public/appointments") || path.equals("/api/public/appointments/cancel")
					|| path.equals("/api/public/appointments/reschedule")) {
				return Limit.BOOKING;
			}
			if (path.equals("/api/auth/login")) {
				return Limit.LOGIN;
			}
		}
		if ("GET".equals(method) && (PATHS.match("/api/public/doctors/*/slots", path)
				|| PATHS.match("/api/public/doctors/*/slot-days", path))) {
			return Limit.SLOTS;
		}
		return null;
	}

}
