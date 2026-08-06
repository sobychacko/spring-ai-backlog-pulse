/*
 * Copyright 2026-present the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.springai.pulse.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Guards every state-changing (non-GET) /api endpoint with a shared admin token — the app has
 * endpoints that spend money (classification) and mutate curation state, so once deployed
 * publicly they must not be open. GET endpoints stay public: the dashboard is read-only and
 * shows only public GitHub data plus derived labels.
 *
 * <p>Token comes from {@code pulse.admin.token} (env {@code PULSE_ADMIN_TOKEN}); clients send
 * it as the {@code X-Admin-Token} header (a custom header also forces a CORS preflight, which
 * kills cross-site drive-by POSTs). An empty token disables the guard for local single-user
 * use — with a loud warning.
 */
@Component
public class AdminTokenFilter extends OncePerRequestFilter {

	private static final Logger logger = LoggerFactory.getLogger(AdminTokenFilter.class);

	private final String token;

	public AdminTokenFilter(@Value("${pulse.admin.token:}") String token) {
		this.token = token != null ? token : "";
		if (this.token.isBlank()) {
			logger.warn("PULSE_ADMIN_TOKEN not set — POST /api endpoints are UNPROTECTED. "
					+ "Fine locally; set it before deploying anywhere reachable.");
		}
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		// baseline hardening on every response: the admin token lives in the operator's
		// browser, so never allow this UI to be framed (clickjacking → disguised admin clicks)
		response.setHeader("X-Frame-Options", "DENY");
		response.setHeader("X-Content-Type-Options", "nosniff");
		String method = request.getMethod();
		boolean readOnly = "GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method);
		if (!readOnly && request.getRequestURI().startsWith("/api/") && !this.token.isBlank()) {
			String provided = request.getHeader("X-Admin-Token");
			boolean ok = provided != null && MessageDigest.isEqual(this.token.getBytes(StandardCharsets.UTF_8),
					provided.getBytes(StandardCharsets.UTF_8));
			if (!ok) {
				response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
				response.setContentType("application/json");
				response.getWriter().write("{\"error\":\"admin token required\"}");
				return;
			}
		}
		chain.doFilter(request, response);
	}

}
