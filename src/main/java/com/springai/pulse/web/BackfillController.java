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

import java.time.OffsetDateTime;
import java.util.Map;

import com.springai.pulse.backfill.BackfillService;
import com.springai.pulse.persistence.SyncStateRepository;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Triggers and reports on the backfill (ingest + classify) and incremental sync.
 */
@RestController
@RequestMapping("/api")
public class BackfillController {

	private final BackfillService backfill;

	private final SyncStateRepository syncState;

	public BackfillController(BackfillService backfill, SyncStateRepository syncState) {
		this.backfill = backfill;
		this.syncState = syncState;
	}

	@PostMapping("/ingest")
	public Map<String, Object> ingest() {
		int ingested = this.backfill.ingestOnly();
		return Map.of("ingested", ingested);
	}

	@PostMapping("/classify")
	public Map<String, Object> classify(@RequestParam(defaultValue = "0") int limit) {
		int classified = this.backfill.classifyOnly(limit);
		return Map.of("classified", classified);
	}

	@PostMapping("/classify-sonnet")
	public Map<String, Object> classifySonnet(@RequestParam(defaultValue = "0") int limit) {
		int classified = this.backfill.classifySonnet(limit);
		return Map.of("classified", classified);
	}

	@PostMapping("/backfill")
	public ResponseEntity<Map<String, Object>> run() {
		boolean started = this.backfill.triggerAsync();
		return ResponseEntity.status(started ? 202 : 409)
			.body(Map.of("started", started, "message", started ? "backfill started" : "already running"));
	}

	@GetMapping("/backfill/status")
	public Map<String, Object> status() {
		return Map.of("running", this.backfill.isRunning(), "lastResult", this.backfill.lastResult());
	}

	@PostMapping("/ingest-pr-branches")
	public Map<String, Object> ingestPrBranches() {
		int updated = this.backfill.ingestPrBranches();
		return Map.of("updated", updated);
	}

	@PostMapping("/sync")
	public ResponseEntity<Map<String, Object>> sync() {
		boolean started = this.backfill.triggerSyncAsync();
		return ResponseEntity.status(started ? 202 : 409)
			.body(Map.of("started", started, "message", started ? "sync started" : "already running"));
	}

	@GetMapping("/sync/status")
	public Map<String, Object> syncStatus() {
		String cursor = this.syncState.getCursorIso();
		OffsetDateTime lastRunAt = this.syncState.getLastRunAt();
		return Map.of(
			"running", this.backfill.isSyncRunning(),
			"lastResult", this.backfill.lastSyncResult(),
			"cursor", cursor != null ? cursor : "",
			"lastRunAt", lastRunAt != null ? lastRunAt.toString() : ""
		);
	}

}
