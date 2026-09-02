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

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.context.properties.bind.Name;

@ConfigurationProperties(prefix = "pulse")
public record PulseProperties(Ingest ingest, Classify classify, Support support, Chat chat,
		Pipeline pipeline, Picks picks) {

	public record Ingest(int pageSize) {
	}

	/**
	 * Daily pipeline tuning: the duplicate-scan similarity threshold (same default the UI
	 * uses) and the minimum number of newly embedded items before a run rebuilds theme
	 * clusters (naming costs one LLM call per cluster, so tiny deltas keep the old clusters).
	 */
	public record Pipeline(@DefaultValue("0.75") double scanThreshold,
			@DefaultValue("10") int clusterMinNewItems) {
	}

	public record Classify(int concurrency, int maxBodyChars,
			@DefaultValue("claude-sonnet-4-6") String sonnetModel, @DefaultValue("false") boolean dualModel) {
	}

	/**
	 * Quick picks: the model that assesses tackleability (the one judgment where a wrong call
	 * wastes the maintainer's morning, so it defaults to the strongest tier), how many top-value
	 * issues form the candidate pool, and how much of each comment thread the model gets to read.
	 */
	public record Picks(@DefaultValue("claude-opus-5") String model, @DefaultValue("50") int poolSize,
			@DefaultValue("25") int maxComments, @DefaultValue("1500") int maxCommentChars) {
	}

	/** OSS support policy: branches whose items belong in the Legacy Review tab. */
	public record Support(@DefaultValue({ "1.0.x", "1.1.x" }) List<String> eolBranches) {
	}

	/**
	 * MVP 9 chat guardrails. {@code public} controls whether POST /api/chat is reachable
	 * without the admin token (the one carve-out in {@code AdminTokenFilter}); the budget and
	 * rate limits apply in BOTH modes — the operator's own use spends the same daily budget.
	 */
	public record Chat(@Name("public") @DefaultValue("false") boolean publicAccess,
			@DefaultValue("1.0") double dailyBudgetUsd, @DefaultValue("10") int questionsPerHour,
			@DefaultValue("3") int maxConcurrent) {
	}

}
