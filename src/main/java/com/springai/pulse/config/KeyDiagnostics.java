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

package com.springai.pulse.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Logs, at startup, a masked fingerprint of the resolved Anthropic API key so you can confirm
 * which key the app is actually using without exposing the secret.
 */
@Component
public class KeyDiagnostics {

	private static final Logger logger = LoggerFactory.getLogger(KeyDiagnostics.class);

	public KeyDiagnostics(@Value("${spring.ai.anthropic.api-key:}") String apiKey) {
		if (apiKey == null || apiKey.isBlank()) {
			logger.warn("Anthropic API key: NOT SET");
		}
		else if (apiKey.length() <= 12) {
			logger.info("Anthropic API key in use: **** (length {})", apiKey.length());
		}
		else {
			String masked = apiKey.substring(0, 8) + "…" + apiKey.substring(apiKey.length() - 4);
			logger.info("Anthropic API key in use: {} (length {})", masked, apiKey.length());
		}
	}

}
