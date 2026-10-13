package com.vihan.hospital.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.vihan.hospital.common.web.RateLimitInterceptor;

/** Uploaded images at /files/** are served by {@code storage.FileController}. */
@Configuration
public class WebConfig implements WebMvcConfigurer {

	private final RateLimitInterceptor rateLimits;

	public WebConfig(RateLimitInterceptor rateLimits) {
		this.rateLimits = rateLimits;
	}

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(rateLimits).addPathPatterns("/api/public/**", "/api/auth/login");
	}

}
