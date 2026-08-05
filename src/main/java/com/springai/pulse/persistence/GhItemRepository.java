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

import com.springai.pulse.domain.GhItem;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Persists raw GitHub items (the source of truth). Upserts are idempotent by item number.
 */
@Repository
public class GhItemRepository {

	private final NamedParameterJdbcTemplate jdbc;

	private final ObjectMapper objectMapper;

	public GhItemRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
		this.jdbc = jdbc;
		this.objectMapper = objectMapper;
	}

	public void upsert(GhItem item) {
		var params = new MapSqlParameterSource().addValue("number", item.number())
			.addValue("kind", item.kind().name().toLowerCase())
			.addValue("title", item.title())
			.addValue("body", item.body())
			.addValue("state", item.state())
			.addValue("author", item.author())
			.addValue("authorAssoc", item.authorAssoc())
			.addValue("createdAt", item.createdAt())
			.addValue("updatedAt", item.updatedAt())
			.addValue("closedAt", item.closedAt())
			.addValue("commentsCount", item.commentsCount())
			.addValue("reactionsTotal", item.reactionsTotal())
			.addValue("labels", toJson(item.labels()))
			.addValue("url", item.url())
			.addValue("prDraft", item.prDraft())
			.addValue("prMergeState", item.prMergeState())
			.addValue("contentHash", item.contentHash())
			.addValue("prBaseBranch", item.prBaseBranch())
			.addValue("assignees", toJson(item.assignees()));
		this.jdbc.update("""
				insert into gh_item (number, kind, title, body, state, author, author_assoc, created_at,
						updated_at, closed_at, comments_count, reactions_total, labels, url, pr_draft,
						pr_merge_state, content_hash, last_synced_at, pr_base_branch, assignees)
				values (:number, :kind, :title, :body, :state, :author, :authorAssoc, :createdAt,
						:updatedAt, :closedAt, :commentsCount, :reactionsTotal, cast(:labels as jsonb), :url,
						:prDraft, :prMergeState, :contentHash, now(), :prBaseBranch, cast(:assignees as jsonb))
				on conflict (number) do update set
						kind = excluded.kind, title = excluded.title, body = excluded.body,
						state = excluded.state, author = excluded.author, author_assoc = excluded.author_assoc,
						created_at = excluded.created_at, updated_at = excluded.updated_at,
						closed_at = excluded.closed_at, comments_count = excluded.comments_count,
						reactions_total = excluded.reactions_total, labels = excluded.labels, url = excluded.url,
						pr_draft = excluded.pr_draft, pr_merge_state = excluded.pr_merge_state,
						content_hash = excluded.content_hash, last_synced_at = now(),
						pr_base_branch = coalesce(excluded.pr_base_branch, gh_item.pr_base_branch),
						assignees = excluded.assignees
				""", params);
	}

	/** Update only the base branch for a PR — populated from the /pulls endpoint. */
	public void updatePrBaseBranch(int number, String baseBranch) {
		this.jdbc.update("update gh_item set pr_base_branch = :baseBranch where number = :number",
				new MapSqlParameterSource().addValue("baseBranch", baseBranch).addValue("number", number));
	}

	public long count() {
		Long n = this.jdbc.getJdbcTemplate().queryForObject("select count(*) from gh_item", Long.class);
		return n != null ? n : 0;
	}

	/** All items — minimal projection for embedding. */
	public List<ItemEmbed> findAllForEmbedding() {
		return this.jdbc.getJdbcTemplate().query(
				"select number, kind, title, body from gh_item order by number",
				(rs, n) -> new ItemEmbed(rs.getInt("number"), rs.getString("kind"), rs.getString("title"),
						rs.getString("body")));
	}

	/**
	 * Open items that {@code model} has not classified yet, whose content changed since it last
	 * classified them, or (for PRs) whose review_complexity has not been populated yet.
	 */
	public List<ItemText> findNeedingClassification(String model) {
		return this.jdbc.query("""
				select i.number, i.kind, i.title, i.body, i.content_hash, i.pr_base_branch
				from gh_item i
				left join classification c on c.item_number = i.number and c.model_used = :model
				where i.state = 'open'
					and (c.item_number is null
						or c.content_hash is distinct from i.content_hash
						or (c.type = 'ENHANCEMENT' and c.enhancement_kind is null)
						or (i.kind = 'pr' and c.review_complexity is null))
				order by i.number
				""", new MapSqlParameterSource("model", model), (rs, n) -> new ItemText(rs.getInt("number"),
				rs.getString("kind"), rs.getString("title"), rs.getString("body"), rs.getString("content_hash"),
				rs.getString("pr_base_branch")));
	}

	private String toJson(List<String> values) {
		return this.objectMapper.writeValueAsString(values != null ? values : List.of());
	}

	/** Minimal projection used to feed the classifier. */
	public record ItemText(int number, String kind, String title, String body, String contentHash,
			String prBaseBranch) {
	}

	/** Minimal projection used to feed the embedder. */
	public record ItemEmbed(int number, String kind, String title, String body) {
	}

}
