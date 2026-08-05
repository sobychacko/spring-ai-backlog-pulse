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

import java.util.List;

import tools.jackson.databind.ObjectMapper;

import com.springai.pulse.domain.IssueClassification;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Persists the AI-suggested classification for an item, keyed (via {@code content_hash}) so an
 * unchanged item is never re-classified.
 */
@Repository
public class ClassificationRepository {

	private final NamedParameterJdbcTemplate jdbc;

	private final ObjectMapper objectMapper;

	public ClassificationRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
		this.jdbc = jdbc;
		this.objectMapper = objectMapper;
	}

	public void upsert(int itemNumber, IssueClassification c, String modelUsed, String contentHash) {
		var params = new MapSqlParameterSource().addValue("itemNumber", itemNumber)
			.addValue("type", c.type() != null ? c.type().name() : null)
			.addValue("enhancementKind", c.enhancementKind() != null ? c.enhancementKind().name() : null)
			.addValue("area", c.area())
			.addValue("providers", toJson(c.providers()))
			.addValue("vectorStores", toJson(c.vectorStores()))
			.addValue("severity", c.severity() != null ? c.severity().name() : null)
			.addValue("goodFirstIssue", c.goodFirstIssue())
			.addValue("summary", c.summary())
			.addValue("reviewComplexity", c.reviewComplexity() != null ? c.reviewComplexity().name() : null)
			.addValue("reviewNotes", c.reviewNotes())
			.addValue("mainBranchApplicable",
					c.mainBranchApplicable() != null ? c.mainBranchApplicable().name() : null)
			.addValue("mainBranchNote", c.mainBranchNote())
			.addValue("modelUsed", modelUsed)
			.addValue("contentHash", contentHash);
		this.jdbc.update("""
				insert into classification (item_number, type, enhancement_kind, area, providers, vector_stores,
						severity, good_first_issue, summary, review_complexity, review_notes,
						main_branch_applicable, main_branch_note, model_used, content_hash, classified_at)
				values (:itemNumber, :type, :enhancementKind, :area, cast(:providers as jsonb),
						cast(:vectorStores as jsonb), :severity, :goodFirstIssue, :summary,
						:reviewComplexity, :reviewNotes, :mainBranchApplicable, :mainBranchNote,
						:modelUsed, :contentHash, now())
				on conflict (item_number, model_used) do update set
						type = excluded.type, enhancement_kind = excluded.enhancement_kind, area = excluded.area,
						providers = excluded.providers, vector_stores = excluded.vector_stores,
						severity = excluded.severity, good_first_issue = excluded.good_first_issue,
						summary = excluded.summary, review_complexity = excluded.review_complexity,
						review_notes = excluded.review_notes, main_branch_applicable = excluded.main_branch_applicable,
						main_branch_note = excluded.main_branch_note,
						content_hash = excluded.content_hash, classified_at = now()
				""", params);
	}

	private String toJson(List<String> values) {
		return this.objectMapper.writeValueAsString(values != null ? values : List.of());
	}

}
