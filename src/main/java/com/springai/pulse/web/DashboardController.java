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

import com.springai.pulse.analytics.AnalyticsRepository;
import com.springai.pulse.cluster.ClusterRepository.ClusterItemView;
import com.springai.pulse.analytics.AnalyticsRepository.HeatmapData;
import com.springai.pulse.analytics.AnalyticsRepository.PulseEntry;
import com.springai.pulse.analytics.AnalyticsRepository.ValueItem;
import com.springai.pulse.cluster.ClusterRepository;
import com.springai.pulse.persistence.ItemLinkRepository;
import com.springai.pulse.persistence.ReadModelRepository;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only JSON for the dashboard. Every value here is computed from GitHub facts in SQL — the
 * LLM contributes no counts.
 */
@RestController
@RequestMapping("/api")
public class DashboardController {

	private final ReadModelRepository readModel;

	private final AnalyticsRepository analytics;

	private final ClusterRepository clusterRepo;

	private final ItemLinkRepository links;

	public DashboardController(ReadModelRepository readModel, AnalyticsRepository analytics,
			ClusterRepository clusterRepo, ItemLinkRepository links) {
		this.readModel = readModel;
		this.analytics = analytics;
		this.clusterRepo = clusterRepo;
		this.links = links;
	}

	@GetMapping("/facets")
	public ReadModelRepository.Facets facets() {
		return this.readModel.facets();
	}

	@GetMapping("/items")
	public List<ReadModelRepository.ItemView> items(@RequestParam(required = false) String type,
			@RequestParam(required = false) String area, @RequestParam(required = false) String weekOf,
			@RequestParam(required = false) String enhancementKind, @RequestParam(required = false) String provider,
			@RequestParam(required = false) String vectorStore, @RequestParam(required = false) String severity,
			@RequestParam(required = false) Integer ageDaysMin, @RequestParam(required = false) Integer ageDaysMax,
			@RequestParam(required = false) Boolean goodFirstIssue,
			@RequestParam(required = false) String kind, @RequestParam(required = false) String search,
			@RequestParam(defaultValue = "100") int limit, @RequestParam(defaultValue = "0") int offset) {
		return this.readModel.items(type, area, weekOf, enhancementKind, provider, vectorStore, severity,
				ageDaysMin, ageDaysMax, goodFirstIssue, kind, search,
				Math.min(Math.max(limit, 1), 500), Math.max(offset, 0));
	}

	@GetMapping("/pulse")
	public List<PulseEntry> pulse() {
		return this.analytics.pulseByArea();
	}

	@GetMapping("/value")
	public List<ValueItem> value(@RequestParam(defaultValue = "50") int limit) {
		return this.analytics.valueQueue(Math.min(Math.max(limit, 1), 100));
	}

	@GetMapping("/clusters")
	public Map<String, Object> clusters() {
		var data = this.clusterRepo.findAllWithEngagement();
		return Map.of("clusters", data, "total", data.size());
	}

	@GetMapping("/clusters/{id}/items")
	public List<ClusterItemView> clusterItems(@PathVariable long id) {
		return this.clusterRepo.findItemsByClusterId(id);
	}

	@GetMapping("/heatmap")
	public HeatmapData heatmap() {
		return this.analytics.heatmap();
	}

	@GetMapping("/duplicates")
	public Map<String, Object> duplicates(@RequestParam(required = false) String type,
			@RequestParam(defaultValue = "50") int limit, @RequestParam(defaultValue = "0") int offset) {
		int safeLimit = Math.min(Math.max(limit, 1), 200);
		int safeOffset = Math.max(offset, 0);
		var pairs = this.links.findPendingCandidates(type, safeLimit, safeOffset);
		long total = this.links.countPendingCandidates(type);
		return Map.of("pairs", pairs, "total", total);
	}

	@PostMapping("/duplicates/{id}/confirm")
	public Map<String, Object> confirmDuplicate(@PathVariable long id) {
		this.links.confirm(id);
		return Map.of("id", id, "confirmed", true);
	}

	@PostMapping("/duplicates/{id}/dismiss")
	public Map<String, Object> dismissDuplicate(@PathVariable long id) {
		this.links.dismiss(id);
		return Map.of("id", id, "dismissed", true);
	}

	@GetMapping("/prs")
	public List<ReadModelRepository.PrView> prs() {
		return this.readModel.openPrs();
	}

}
