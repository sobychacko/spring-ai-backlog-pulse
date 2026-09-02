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

import java.util.List;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * The LLM's structured answer for one quick-pick candidate: how much work an experienced
 * maintainer would need to land it on main, what it would do to the public API, and what
 * would stop them. Every field is an interpretation of the issue text and its comments —
 * never a fact about the codebase the model has not seen. The evidence quote is verified
 * against the source text before the assessment is trusted.
 */
@JsonClassDescription("Assessment of whether a Spring AI GitHub issue is a small, safe change a "
		+ "maintainer could finish in one sitting on the main branch. Judge only from the provided "
		+ "title, body, and comments; do not invent facts about the code.")
public record PickAssessment(

		@JsonPropertyDescription("Effort for a maintainer who knows the codebase to land the change on main "
				+ "with a test: ABOUT_AN_HOUR, HALF_DAY, MULTI_DAY, or CANNOT_TELL.") PickEffort effort,

		@JsonPropertyDescription("Effect on Spring AI's public API: NONE, ADDITIVE, BREAKING, or CANNOT_TELL. "
				+ "Enhancements that add a new option or property are ADDITIVE, not NONE.") ApiRisk apiRisk,

		@JsonPropertyDescription("Every reason the change cannot be picked up and finished right now. Empty "
				+ "list when nothing stands in the way.") List<PickBlocker> blockers,

		@JsonPropertyDescription("Where the change most likely lands, named only from what the text mentions: "
				+ "module, package, class, or 'docs'. One short phrase.") String likelyScope,

		@JsonPropertyDescription("A VERBATIM quote of the single sentence or fragment from the title, body, or "
				+ "comments that best supports the effort estimate. Copy it exactly; never paraphrase.") String evidence,

		@JsonPropertyDescription("Two sentences at most: the concrete first step a maintainer would take, "
				+ "e.g. which class to open or which test to write.") String firstStep,

		@JsonPropertyDescription("How well the text supports this assessment: LOW, MEDIUM, or HIGH.") PickConfidence confidence) {
}
