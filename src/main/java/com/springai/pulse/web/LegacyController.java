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

import java.util.List;
import java.util.Map;

import com.springai.pulse.domain.LegacyVerdict;
import com.springai.pulse.domain.ModelIds;
import com.springai.pulse.legacy.LegacyReviewRepository;
import com.springai.pulse.legacy.LegacyReviewService;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * MVP 8 Legacy Review. The scan trigger is metered (Haiku per candidate) and thus guarded by
 * the admin-token filter like every non-GET /api endpoint; the read endpoint is public and
 * whitelists its filter values. Read-only toward GitHub — closing an item there clears it here
 * on the next sync.
 */
@RestController
@RequestMapping("/api")
public class LegacyController {

	private final LegacyReviewService service;

	private final LegacyReviewRepository repo;

	public LegacyController(LegacyReviewService service, LegacyReviewRepository repo) {
		this.service = service;
		this.repo = repo;
	}

	/** Run the AI pass over pending regex-gated issue candidates. Metered; admin-token guarded. */
	@PostMapping("/legacy-scan")
	public LegacyReviewService.ScanResult scan(@RequestParam(defaultValue = "0") int limit) {
		return this.service.scan(Math.max(limit, 0));
	}

	@GetMapping("/legacy-review")
	public Map<String, Object> review(@RequestParam(required = false) String verdict,
			@RequestParam(required = false) String kind, @RequestParam(required = false) String branch) {
		// whitelist every filter — invalid values are ignored, never interpolated
		String safeVerdict = whitelist(verdict,
				List.of(LegacyVerdict.LEGACY_ONLY.name(), LegacyVerdict.APPLIES_TO_MAIN.name(),
						LegacyVerdict.UNCLEAR.name()));
		String safeKind = whitelist(kind, List.of("issue", "pr"));
		String safeBranch = whitelist(branch, this.service.eolBranches());
		List<LegacyReviewRepository.EolPr> prs = "issue".equals(safeKind) ? List.of()
				: this.repo.findEolPrs(this.service.eolBranches(), safeBranch, ModelIds.DEFAULT_CLASSIFIER);
		List<LegacyReviewRepository.LegacyIssue> issues = "pr".equals(safeKind) ? List.of()
				: this.repo.findIssueCandidates(safeVerdict, ModelIds.DEFAULT_CLASSIFIER);
		return Map.of(
				"prs", prs,
				"issues", issues,
				"counts", this.repo.countsByVerdict(),
				"pendingScan", this.service.pendingCount(),
				"eolBranches", this.service.eolBranches());
	}

	private static String whitelist(String value, List<String> allowed) {
		return (value != null && allowed.contains(value)) ? value : null;
	}

}
