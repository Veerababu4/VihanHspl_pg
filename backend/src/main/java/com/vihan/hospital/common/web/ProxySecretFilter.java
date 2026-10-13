package com.vihan.hospital.common.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Cloud deployment (DESIGN.md §16): the website's proxy (Cloudflare Pages Function) adds
 * {@code X-Proxy-Secret} to every request it forwards. With {@code app.proxy-secret} set, any other
 * caller gets 404, so nobody can reach the backend's public URL directly, bypass the website's
 * headers, or fake the visitor IP ({@code X-Forwarded-For}) that the rate limits use.
 * Only the health check stays open, for the host's own monitoring. Blank secret = filter off (local).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ProxySecretFilter extends OncePerRequestFilter {

	static final String HEADER = "X-Proxy-Secret";

	private final byte[] secret;

	public ProxySecretFilter(@Value("${app.proxy-secret:}") String secret) {
		this.secret = secret == null || secret.isBlank() ? null : secret.getBytes(StandardCharsets.UTF_8);
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return secret == null || request.getRequestURI().equals("/actuator/health");
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String given = request.getHeader(HEADER);
		if (given != null && MessageDigest.isEqual(secret, given.getBytes(StandardCharsets.UTF_8))) {
			chain.doFilter(request, response);
		}
		else {
			response.sendError(HttpServletResponse.SC_NOT_FOUND);
		}
	}

}
