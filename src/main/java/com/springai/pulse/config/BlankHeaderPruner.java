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

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.ai.model.anthropic.autoconfigure.AnthropicConnectionProperties;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.stereotype.Component;

/**
 * Drops blank entries from {@code spring.ai.anthropic.custom-headers} after binding. The
 * config declares {@code anthropic-workspace-id: ${ANTHROPIC_WORKSPACE_ID:}} so an
 * identity-linked key can be pointed at its workspace through the environment; when the
 * variable is unset the placeholder resolves to an empty string, and sending an empty header
 * to the API is not the same as sending none. Runs after the properties bean is bound and
 * before the chat model that reads it is created.
 */
@Component
class BlankHeaderPruner implements BeanPostProcessor {

	private static final Logger logger = LoggerFactory.getLogger(BlankHeaderPruner.class);

	@Override
	public Object postProcessAfterInitialization(Object bean, String beanName) {
		if (bean instanceof AnthropicConnectionProperties props && props.getCustomHeaders() != null) {
			Map<String, String> kept = new LinkedHashMap<>();
			props.getCustomHeaders().forEach((k, v) -> {
				if (v != null && !v.isBlank()) {
					kept.put(k, v);
				}
			});
			props.setCustomHeaders(kept);
			if (kept.containsKey("anthropic-workspace-id")) {
				logger.info("Anthropic requests carry anthropic-workspace-id");
			}
		}
		return bean;
	}

}
