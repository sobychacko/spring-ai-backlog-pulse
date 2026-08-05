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

package com.springai.pulse.domain;

import com.fasterxml.jackson.annotation.JsonCreator;

/**
 * AI-suggested classification of what an item is. Constrains the LLM's structured output.
 */
public enum ItemType {

	BUG, ENHANCEMENT, QUESTION, DOCUMENTATION, TASK;

	/**
	 * Lenient mapping for model output: the LLM occasionally emits an {@link EnhancementKind}
	 * value (IMPROVEMENT / NEW_FEATURE) or a common synonym here instead of failing the whole
	 * classification, coerce the known cases.
	 */
	@JsonCreator
	public static ItemType fromModelOutput(String value) {
		String v = value == null ? "" : value.trim().toUpperCase().replace('-', '_').replace(' ', '_');
		return switch (v) {
			case "IMPROVEMENT", "NEW_FEATURE", "FEATURE", "FEATURE_REQUEST" -> ENHANCEMENT;
			case "DOCS", "DOC" -> DOCUMENTATION;
			case "DEFECT" -> BUG;
			default -> valueOf(v);
		};
	}

}
