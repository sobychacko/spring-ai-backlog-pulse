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

package com.springai.pulse.persistence;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Reads and writes the {@code sync_state} singleton row that tracks the incremental-sync cursor.
 */
@Repository
public class SyncStateRepository {

	private final JdbcTemplate jdbc;

	public SyncStateRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Returns the current cursor as an ISO-8601 string, or {@code null} if never set.
	 */
	public String getCursorIso() {
		List<String> result = this.jdbc.query(
				"select cursor_updated_at from sync_state where id = 1",
				(rs, n) -> {
					Timestamp ts = rs.getTimestamp("cursor_updated_at");
					return ts != null ? ts.toInstant().atOffset(ZoneOffset.UTC).toString() : null;
				});
		return result.isEmpty() ? null : result.get(0);
	}

	/**
	 * Returns the last-run timestamp, or {@code null} if the scheduler has never run.
	 */
	public OffsetDateTime getLastRunAt() {
		List<OffsetDateTime> result = this.jdbc.query(
				"select last_run_at from sync_state where id = 1",
				(rs, n) -> {
					Timestamp ts = rs.getTimestamp("last_run_at");
					return ts != null ? ts.toInstant().atOffset(ZoneOffset.UTC) : null;
				});
		return result.isEmpty() ? null : result.get(0);
	}

	/**
	 * Advance the cursor to {@code cursorIso} and record the run time.
	 */
	public void advance(String cursorIso) {
		this.jdbc.update(
				"update sync_state set cursor_updated_at = ?::timestamptz, last_run_at = now() where id = 1",
				cursorIso);
	}

	/**
	 * Record a run that produced no new items (cursor stays the same).
	 */
	public void markLastRun() {
		this.jdbc.update("update sync_state set last_run_at = now() where id = 1");
	}

}
