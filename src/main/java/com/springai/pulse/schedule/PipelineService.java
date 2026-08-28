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

package com.springai.pulse.schedule;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import com.springai.pulse.backfill.BackfillService;
import com.springai.pulse.cluster.ClusterService;
import com.springai.pulse.config.PulseProperties;
import com.springai.pulse.embed.DuplicateScanService;
import com.springai.pulse.embed.EmbedService;
import com.springai.pulse.legacy.LegacyReviewService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Service;

/**
 * Runs the whole refresh chain in order — the once-a-day "keep it live" entry point, designed
 * to be kicked by an external scheduler (a GitHub Actions cron curling {@code POST
 * /api/pipeline}): on a serverless deployment the app sleeps when idle, so in-app scheduling
 * never fires; the inbound request is what wakes the service, and it goes back to sleep when
 * the run ends.
 *
 * <p>Steps, each isolated so one failure doesn't stop the rest:
 * <ol>
 * <li>Incremental sync — ingest items updated since the cursor, classify changed ones.
 * <li>Embed new/changed items (local ONNX, free).
 * <li>Duplicate scan — incremental; new pairs are adjudicated by the scan itself.
 * <li>Adjudication sweep — retries pairs whose earlier verdict call failed.
 * <li>Legacy scan — content-hash keyed, only new/changed candidates.
 * <li>Cluster rebuild — only when this run embedded at least
 * {@code pulse.pipeline.cluster-min-new-items} items, because membership is free but naming
 * costs one LLM call per cluster; below the threshold the old clusters stay.
 * </ol>
 * Steady-state cost is cents per run (a handful of Haiku calls for changed items and new
 * pairs); the run summary is exposed at {@code GET /api/pipeline/status}.
 */
@Service
public class PipelineService {

	private static final Logger logger = LoggerFactory.getLogger(PipelineService.class);

	private final BackfillService backfill;

	private final EmbedService embed;

	private final DuplicateScanService dupScan;

	private final LegacyReviewService legacy;

	private final ClusterService cluster;

	private final PulseProperties props;

	private final AtomicBoolean running = new AtomicBoolean(false);

	private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "pipeline");
		t.setDaemon(true);
		return t;
	});

	private volatile String lastResult = "never run";

	private volatile Instant lastRunAt;

	public PipelineService(BackfillService backfill, EmbedService embed, DuplicateScanService dupScan,
			LegacyReviewService legacy, ClusterService cluster, PulseProperties props) {
		this.backfill = backfill;
		this.embed = embed;
		this.dupScan = dupScan;
		this.legacy = legacy;
		this.cluster = cluster;
		this.props = props;
	}

	/**
	 * Start a pipeline run if one isn't already going.
	 * @return true if started, false if a run was already in progress
	 */
	public boolean triggerAsync() {
		if (!this.running.compareAndSet(false, true)) {
			return false;
		}
		this.worker.submit(() -> {
			try {
				run();
			}
			finally {
				this.running.set(false);
			}
		});
		return true;
	}

	private void run() {
		long start = System.currentTimeMillis();
		StringBuilder summary = new StringBuilder();
		logger.info("Pipeline run starting");

		step(summary, "sync", () -> this.backfill.syncNow() ? this.backfill.lastSyncResult()
				: "skipped (sync already running)");

		int[] embedded = { 0 };
		step(summary, "embed", () -> {
			embedded[0] = this.embed.embedAll();
			return embedded[0] + " new";
		});

		step(summary, "scan", () -> this.dupScan.scan(this.props.pipeline().scanThreshold()) + " new pairs");

		step(summary, "adjudicate", () -> {
			Map<String, Integer> a = this.dupScan.adjudicate();
			return a.get("adjudicated") + " judged, " + a.get("failed") + " failed";
		});

		step(summary, "legacy", () -> {
			LegacyReviewService.ScanResult r = this.legacy.scan(0);
			return r.scanned() + " scanned" + (r.failed() > 0 ? ", " + r.failed() + " failed" : "");
		});

		int minNew = this.props.pipeline().clusterMinNewItems();
		step(summary, "clusters", () -> embedded[0] >= minNew ? this.cluster.buildClusters() + " built"
				: "skipped (" + embedded[0] + " new < " + minNew + ")");

		long secs = (System.currentTimeMillis() - start) / 1000;
		this.lastResult = summary + "(" + secs + "s)";
		this.lastRunAt = Instant.now();
		logger.info("Pipeline run finished: {}", this.lastResult);
	}

	private void step(StringBuilder summary, String name, StepBody body) {
		try {
			summary.append(name).append(": ").append(body.run()).append(" · ");
		}
		catch (Exception ex) {
			logger.error("Pipeline step '{}' failed", name, ex);
			summary.append(name).append(": FAILED (").append(ex.getMessage()).append(") · ");
		}
	}

	@FunctionalInterface
	private interface StepBody {

		String run() throws Exception;

	}

	public boolean isRunning() {
		return this.running.get();
	}

	public String lastResult() {
		return this.lastResult;
	}

	public Instant lastRunAt() {
		return this.lastRunAt;
	}

	@PreDestroy
	void shutdown() {
		this.worker.shutdownNow();
	}

}
