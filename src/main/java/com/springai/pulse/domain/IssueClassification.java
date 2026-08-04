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
 * The AI-suggested classification of a single item. Drives {@code ChatClient.entity(...)} —
 * Spring AI's {@code BeanOutputConverter} derives a JSON schema from this record (enums become
 * enum constraints), so the model is forced to return exactly these fields.
 *
 * <p>Every field is an interpretation of the item's existing text — never an invented fact.
 * The {@code summary} must be faithful/extractive.
 */
@JsonClassDescription("Classification of a Spring AI GitHub issue or pull request, derived only "
		+ "from its title and body. Do not invent facts.")
public record IssueClassification(

		@JsonPropertyDescription("The kind of work this item represents.") ItemType type,

		@JsonPropertyDescription("Only meaningful when type is ENHANCEMENT: NEW_FEATURE if the item asks "
				+ "for a brand-new capability, integration, or provider not currently in Spring AI; "
				+ "IMPROVEMENT if it refines, extends, or exposes more of an existing feature. Use "
				+ "NOT_APPLICABLE for BUG, QUESTION, DOCUMENTATION, and TASK.") EnhancementKind enhancementKind,

		@JsonPropertyDescription("The single primary functional area, lower-kebab-case, chosen from the "
				+ "Spring AI taxonomy provided in the prompt (e.g. core, chat-client, vector-store, "
				+ "tool-calling, mcp, rag, observability, chat-memory, embedding, structured-output, "
				+ "agents, auto-config). Use 'unknown' only if truly undeterminable.") String area,

		@JsonPropertyDescription("Model providers explicitly involved (e.g. openai, anthropic, bedrock, "
				+ "ollama, vertex, azure, google-genai). Empty if none are mentioned.") List<String> providers,

		@JsonPropertyDescription("Vector stores explicitly involved (e.g. pgvector, redis, milvus, "
				+ "qdrant, chroma). Empty if none are mentioned.") List<String> vectorStores,

		@JsonPropertyDescription("""
				Severity of impact on users of the Spring AI library:
				CRITICAL — security vulnerability, data loss, or complete breakage of a core feature \
				in a released version with no workaround.
				HIGH — important feature is broken or severely degraded for many users and has no \
				reasonable workaround.
				MEDIUM — feature works but has an annoying limitation, edge-case failure, or a \
				workaround exists; includes performance regressions.
				LOW — cosmetic issue, minor inconvenience, documentation gap, or any enhancement / \
				feature request (enhancements are never higher than LOW unless they block a security fix).
				When in doubt, prefer LOW over MEDIUM and MEDIUM over HIGH.""") Severity severity,

		@JsonPropertyDescription("True only if this looks approachable for a first-time contributor "
				+ "(small, well-scoped, low-risk).") boolean goodFirstIssue,

		@JsonPropertyDescription("A faithful one-sentence summary of the item, using only information "
				+ "present in the title and body.") String summary,

		@JsonPropertyDescription("PR review complexity for maintainers. LOW = narrow, well-described change "
				+ "with tests, easy to verify. MEDIUM = moderate scope or partial description. "
				+ "HIGH = broad refactor, architectural change, or unclear scope. "
				+ "NOT_APPLICABLE for issues.") ReviewComplexity reviewComplexity,

		@JsonPropertyDescription("One sentence explaining the review complexity assessment for PRs. "
				+ "Empty string for issues.") String reviewNotes,

		@JsonPropertyDescription("For PRs targeting an old/inactive branch (1.x, 0.x): does this change "
				+ "also apply to main/2.x? YES = bug/fix almost certainly exists in main too. "
				+ "NO = change is specific to the old branch API. UNCLEAR = cannot tell from description. "
				+ "NOT_APPLICABLE for issues or PRs already targeting main/2.x.") MainBranchApplicable mainBranchApplicable,

		@JsonPropertyDescription("One sentence explaining the main-branch applicability verdict for "
				+ "old-branch PRs. Empty string otherwise.") String mainBranchNote) {
}
