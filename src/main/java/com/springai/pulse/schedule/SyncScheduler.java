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

import com.springai.pulse.backfill.BackfillService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The "live" part: periodically pulls items updated since the sync cursor and classifies the
 * changed ones. Among other things this is what makes GitHub the resolution surface for
 * duplicates — closing an issue there clears its pairs from the review queue on the next tick.
 *
 * <p>Off by default ({@code pulse.sync.scheduled=false}) — sync is driven manually via
 * Admin → Sync / {@code POST /api/sync} until scheduling is wanted.
 */
@Component
@ConditionalOnProperty(name = "pulse.sync.scheduled", havingValue = "true")
public class SyncScheduler {

	private static final Logger logger = LoggerFactory.getLogger(SyncScheduler.class);

	private final BackfillService backfill;

	public SyncScheduler(BackfillService backfill) {
		this.backfill = backfill;
	}

	@Scheduled(initialDelayString = "${pulse.sync.initial-delay:2m}", fixedDelayString = "${pulse.sync.interval:10m}")
	void incrementalSync() {
		if (this.backfill.triggerSyncAsync()) {
			logger.info("Scheduled incremental sync started");
		}
	}

}
