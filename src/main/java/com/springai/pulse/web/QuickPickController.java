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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.springai.pulse.domain.ModelIds;
import com.springai.pulse.domain.PickEffort;
import com.springai.pulse.picks.QuickPickRepository;
import com.springai.pulse.picks.QuickPickService;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * "Today's picks": the morning list of high-value issues a maintainer can land on main in about
 * an hour. Read endpoints are public like the rest of the dashboard; the assessment run (metered)
 * and the take/skip decisions are POSTs, so the admin token filter gates them on deployed
 * instances. Read-only toward GitHub.
 */
@RestController
@RequestMapping("/api")
public class QuickPickController {

	private static final int MAX_LIMIT = 100;

	private final QuickPickService service;

	private final QuickPickRepository repo;

	public QuickPickController(QuickPickService service, QuickPickRepository repo) {
		this.service = service;
		this.repo = repo;
	}

	/** Run the AI pass over the pending candidate pool. Metered; admin-token guarded. */
	@PostMapping("/picks-assess")
	public QuickPickService.AssessResult assess(@RequestParam(defaultValue = "0") int limit) {
		return this.service.assess(Math.max(limit, 0));
	}

	/**
	 * The list. {@code effort} may repeat (default ABOUT_AN_HOUR only; add HALF_DAY for a longer
	 * list); unknown values are ignored. Also returns the full undecided assessed set, the
	 * decision history, and the effort counts so the page can render without further calls.
	 */
	@GetMapping("/picks")
	public Map<String, Object> picks(@RequestParam(required = false) List<String> effort,
			@RequestParam(defaultValue = "10") int limit) {
		int safeLimit = Math.min(Math.max(limit, 1), MAX_LIMIT);
		List<String> efforts = new ArrayList<>();
		if (effort != null) {
			for (String e : effort) {
				for (PickEffort known : PickEffort.values()) {
					if (known.name().equals(e)) {
						efforts.add(known.name());
					}
				}
			}
		}
		if (efforts.isEmpty()) {
			efforts.add(PickEffort.ABOUT_AN_HOUR.name());
		}
		String model = ModelIds.DEFAULT_CLASSIFIER;
		Map<String, Object> out = new HashMap<>();
		out.put("picks", this.repo.findPicks(model, efforts, safeLimit));
		out.put("assessed", this.repo.findAssessed(model, MAX_LIMIT));
		out.put("decided", this.repo.findDecided(model, 50));
		out.put("counts", this.repo.countsByEffort(model));
		out.put("pendingAssessment", this.service.pendingCount());
		out.put("lastAssessedAt", this.repo.lastAssessedAt());
		out.put("model", this.service.model());
		return out;
	}

	/**
	 * The maintainer's call on a pick: TAKEN (working on it), SKIPPED (not today, not ever), or
	 * NONE to undo. Decided items leave the list and are never re-assessed.
	 */
	@PostMapping("/picks/{number}/decide")
	public ResponseEntity<Map<String, Object>> decide(@PathVariable int number, @RequestParam String decision) {
		String safe = switch (decision) {
			case "TAKEN", "SKIPPED" -> decision;
			case "NONE" -> null;
			default -> "invalid";
		};
		if ("invalid".equals(safe)) {
			return ResponseEntity.badRequest().body(Map.of("error", "decision must be TAKEN, SKIPPED, or NONE"));
		}
		boolean updated = this.repo.decide(number, safe);
		if (!updated) {
			return ResponseEntity.status(404).body(Map.of("error", "no assessment for #" + number));
		}
		Map<String, Object> body = new HashMap<>();
		body.put("number", number);
		body.put("decision", safe);
		return ResponseEntity.ok(body);
	}

}
