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

import java.util.Map;

import com.springai.pulse.cluster.ClusterService;
import com.springai.pulse.embed.DuplicateScanService;
import com.springai.pulse.embed.EmbedService;
import com.springai.pulse.persistence.ItemLinkRepository;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin-only POST triggers for the MVP 4 embedding + clustering pipeline. These are
 * intended to be called manually from the dashboard admin menu, not from a browser
 * navigation flow — they can take seconds to minutes depending on corpus size.
 */
@RestController
@RequestMapping("/api")
public class EmbedController {

	private final EmbedService embed;

	private final DuplicateScanService dupScan;

	private final ClusterService cluster;

	private final ItemLinkRepository links;

	private final com.springai.pulse.config.PulseProperties props;

	public EmbedController(EmbedService embed, DuplicateScanService dupScan, ClusterService cluster,
			ItemLinkRepository links, com.springai.pulse.config.PulseProperties props) {
		this.embed = embed;
		this.dupScan = dupScan;
		this.cluster = cluster;
		this.links = links;
		this.props = props;
	}

	/** Embed all unembedded items into the pgvector store. Free — local ONNX, no API cost. */
	@PostMapping("/embed")
	public Map<String, Object> embed() {
		int count = this.embed.embedAll();
		return Map.of("embedded", count, "total", this.embed.embeddedCount());
	}

	/**
	 * Scan vector store for similar pairs and insert duplicate/related candidates. Uses
	 * the Haiku LLM for PR↔Issue pairs only (to distinguish fix-relationship from
	 * co-occurrence). Pass {@code threshold} to override the default 0.85.
	 */
	@PostMapping("/scan-duplicates")
	public Map<String, Object> scanDuplicates(@RequestParam(required = false) Double threshold) {
		// default comes from pulse.pipeline.scan-threshold so manual and scheduled scans agree;
		// the service clamps to [0.5, 0.99]
		double t = threshold != null ? threshold
				: (this.props.pipeline() != null ? this.props.pipeline().scanThreshold() : 0.75);
		int added = this.dupScan.scan(t);
		// "candidates" = what the Duplicates tab shows (pending, both sides open); "added" = new this run
		return Map.of("added", added, "candidates", this.links.countPendingCandidates(null), "threshold", t);
	}

	/**
	 * AI adjudication for pending duplicate/competing-PR pairs that have no verdict yet (one
	 * Haiku call per pair — the scan already adjudicates its own new pairs, so this is the
	 * backfill / retry entry point).
	 */
	@PostMapping("/adjudicate-duplicates")
	public Map<String, Integer> adjudicateDuplicates() {
		return this.dupScan.adjudicate();
	}

	/**
	 * Rebuild theme clusters from the similarity graph. Free for the graph step; uses
	 * one Haiku call per cluster for naming.
	 */
	@PostMapping("/cluster")
	public ResponseEntity<Map<String, Object>> cluster() {
		int count = this.cluster.buildClusters();
		if (count == 0) {
			return ResponseEntity.ok(Map.of("clusters", 0, "message", "no items embedded yet — run /api/embed first"));
		}
		return ResponseEntity.ok(Map.of("clusters", count));
	}

}
