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

package com.springai.pulse.backfill;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import com.springai.pulse.classify.ClassifyService;
import com.springai.pulse.ingest.IngestService;
import com.springai.pulse.persistence.SyncStateRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Service;

/**
 * Runs a full backfill (ingest then classify) off the request thread — classification of ~1k
 * items takes minutes, so the HTTP trigger returns immediately and progress is observed via the
 * status endpoint, the logs, or the facets endpoint.
 */
@Service
public class BackfillService {

	private static final Logger logger = LoggerFactory.getLogger(BackfillService.class);

	private final IngestService ingest;

	private final ClassifyService classify;

	private final SyncStateRepository syncState;

	private final AtomicBoolean running = new AtomicBoolean(false);

	private final AtomicBoolean syncRunning = new AtomicBoolean(false);

	private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "backfill");
		t.setDaemon(true);
		return t;
	});

	private volatile String lastResult = "never run";

	private volatile String lastSyncResult = "never run";

	public BackfillService(IngestService ingest, ClassifyService classify, SyncStateRepository syncState) {
		this.ingest = ingest;
		this.classify = classify;
		this.syncState = syncState;
	}

	/**
	 * Run ingest only (no classification) synchronously. Free — hits GitHub only — and fast
	 * (~14 requests), so it's safe to run on the request thread. Useful for verifying the
	 * ingest half without spending on the LLM.
	 * @return the number of items ingested
	 */
	public int ingestOnly() {
		return this.ingest.ingestAll();
	}

	/**
	 * Fetch base branches for all open PRs from the {@code /pulls} endpoint. Synchronous and
	 * free (~5 requests). Safe to run on the request thread.
	 * @return number of PRs updated
	 */
	public int ingestPrBranches() {
		return this.ingest.ingestPrBaseBranches();
	}

	/**
	 * Classify pending items only (no ingest), capped at {@code limit} (0 = all). Synchronous —
	 * use a small limit for a smoke test; the full run is better driven via {@link #triggerAsync()}.
	 * @return the number classified
	 */
	public int classifyOnly(int limit) {
		return this.classify.classifyPending(limit);
	}

	public boolean isRunning() {
		return this.running.get();
	}

	public String lastResult() {
		return this.lastResult;
	}

	/**
	 * Start a backfill if one isn't already running.
	 * @return true if started, false if a run was already in progress
	 */
	public boolean triggerAsync() {
		if (!this.running.compareAndSet(false, true)) {
			return false;
		}
		this.worker.submit(() -> {
			long start = System.currentTimeMillis();
			try {
				int ingested = this.ingest.ingestAll();
				int classified = this.classify.classifyPending();
				long secs = (System.currentTimeMillis() - start) / 1000;
				this.lastResult = "ingested=%d, classified=%d, %ds".formatted(ingested, classified, secs);
				logger.info("Backfill finished: {}", this.lastResult);
			}
			catch (Exception ex) {
				this.lastResult = "FAILED: " + ex.getMessage();
				logger.error("Backfill failed", ex);
			}
			finally {
				this.running.set(false);
			}
		});
		return true;
	}

	/**
	 * Start an incremental sync if one isn't already running. Fetches only items updated since
	 * the last cursor, classifies changed items, and advances the cursor.
	 * @return true if started, false if already running
	 */
	public boolean triggerSyncAsync() {
		if (!this.syncRunning.compareAndSet(false, true)) {
			return false;
		}
		this.worker.submit(() -> {
			long start = System.currentTimeMillis();
			try {
				String cursor = this.syncState.getCursorIso();
				if (cursor == null) {
					cursor = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).minusDays(3).toString();
					logger.info("No sync cursor — using 3-day lookback ({})", cursor);
				}
				IngestService.IngestResult result = this.ingest.ingestSince(cursor);
				int classified = 0;
				if (result.count() > 0) {
					classified = this.classify.classifyPending();
					this.syncState.advance(result.latestUpdatedAt());
				}
				else {
					this.syncState.markLastRun();
				}
				long secs = (System.currentTimeMillis() - start) / 1000;
				this.lastSyncResult = "ingested=%d, classified=%d, %ds".formatted(result.count(), classified, secs);
				logger.info("Incremental sync finished: {}", this.lastSyncResult);
			}
			catch (Exception ex) {
				this.lastSyncResult = "FAILED: " + ex.getMessage();
				logger.error("Incremental sync failed", ex);
			}
			finally {
				this.syncRunning.set(false);
			}
		});
		return true;
	}

	public boolean isSyncRunning() {
		return this.syncRunning.get();
	}

	public String lastSyncResult() {
		return this.lastSyncResult;
	}

	@PreDestroy
	void shutdown() {
		this.worker.shutdownNow();
	}

}
