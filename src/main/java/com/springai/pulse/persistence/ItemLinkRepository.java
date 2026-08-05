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

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Persists links between items. Covers GH-native links (MVP 1) and embedding-based
 * duplicate/related candidates (MVP 4).
 *
 * <p>Lifecycle semantics for candidates:
 * <ul>
 *   <li>{@code confirmed=false, decided_at IS NULL} — pending human review
 *   <li>{@code confirmed=true} — maintainer confirmed the relationship
 *   <li>{@code confirmed=false, decided_at IS NOT NULL} — maintainer dismissed
 * </ul>
 */
@Repository
public class ItemLinkRepository {

	private final NamedParameterJdbcTemplate jdbc;

	public ItemLinkRepository(NamedParameterJdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Insert a GH-native link. GH-native links are always {@code confirmed=true} since they
	 * come straight from GitHub.
	 */
	public void insertGhReference(int fromNumber, int toNumber, String type) {
		var params = new MapSqlParameterSource().addValue("from", fromNumber)
			.addValue("to", toNumber)
			.addValue("type", type);
		this.jdbc.update("""
				insert into item_link (from_number, to_number, type, source, confidence, confirmed)
				values (:from, :to, :type, 'gh_reference', 1.0, true)
				on conflict (from_number, to_number, type) do nothing
				""", params);
	}

	/**
	 * Insert an embedding-derived candidate. Idempotent — silently ignored if the
	 * (from, to, type) triple already exists. Candidates land {@code confirmed=false} pending
	 * human sign-off.
	 */
	public void insertEmbeddingCandidate(int fromNumber, int toNumber, String type, double confidence) {
		var params = new MapSqlParameterSource().addValue("from", fromNumber)
			.addValue("to", toNumber)
			.addValue("type", type)
			.addValue("confidence", confidence);
		this.jdbc.update("""
				insert into item_link (from_number, to_number, type, source, confidence, confirmed)
				values (:from, :to, :type, 'embedding', :confidence, false)
				on conflict (from_number, to_number, type) do nothing
				""", params);
	}

	/** Returns the set of (from_number, to_number) pairs that already have a decided outcome. */
	public Set<String> decidedPairs() {
		List<String> pairs = this.jdbc.getJdbcTemplate().query(
				"select from_number || ':' || to_number from item_link where decided_at is not null",
				(rs, n) -> rs.getString(1));
		return new HashSet<>(pairs);
	}

	/** The only link types that exist — user-supplied type filters must match one exactly. */
	private static final java.util.Set<String> LINK_TYPES = java.util.Set.of("duplicate_candidate", "competing_pr",
			"pr_fixes_issue", "related", "closes", "references");

	/** Pending (undecided) candidate pairs with full item detail for the review UI. */
	public List<DuplicatePairView> findPendingCandidates(String typeFilter, int limit, int offset) {
		// whitelist, not escaping: anything outside the known set is ignored
		String typeClause = (typeFilter != null && LINK_TYPES.contains(typeFilter))
				? "and il.type = '" + typeFilter + "'" : "";
		// summaries/areas on the compare cards come from the default (bulk) classifier's rows
		String modelClause = "and %s.model_used = '" + com.springai.pulse.domain.ModelIds.DEFAULT_CLASSIFIER + "'";
		return this.jdbc.getJdbcTemplate().query("""
				select
				    il.id, il.type, il.confidence, il.source,
				    fa.number as from_num, fa.kind as from_kind, fa.title as from_title,
				    fa.url as from_url, fc.area as from_area, fc.summary as from_summary,
				    ta.number as to_num, ta.kind as to_kind, ta.title as to_title,
				    ta.url as to_url, tc.area as to_area, tc.summary as to_summary
				from item_link il
				join gh_item fa on fa.number = il.from_number
				join gh_item ta on ta.number = il.to_number
				left join classification fc on fc.item_number = il.from_number %s
				left join classification tc on tc.item_number = il.to_number %s
				where il.source = 'embedding'
				  and il.decided_at is null
				  and fa.state = 'open' and ta.state = 'open'
				  %s
				order by il.confidence desc, il.id
				limit ? offset ?
				""".formatted(modelClause.formatted("fc"), modelClause.formatted("tc"), typeClause),
				(rs, n) -> new DuplicatePairView(rs.getLong("id"), rs.getString("type"),
				rs.getDouble("confidence"), rs.getString("source"),
				new DuplicateItemView(rs.getInt("from_num"), rs.getString("from_kind"),
						rs.getString("from_title"), rs.getString("from_url"), rs.getString("from_area"),
						rs.getString("from_summary")),
				new DuplicateItemView(rs.getInt("to_num"), rs.getString("to_kind"),
						rs.getString("to_title"), rs.getString("to_url"), rs.getString("to_area"),
						rs.getString("to_summary"))),
				limit, offset);
	}

	public long countPendingCandidates(String typeFilter) {
		String typeClause = (typeFilter != null && LINK_TYPES.contains(typeFilter))
				? "and il.type = '" + typeFilter + "'" : "";
		Long n = this.jdbc.getJdbcTemplate().queryForObject("""
				select count(*) from item_link il
				join gh_item fa on fa.number = il.from_number
				join gh_item ta on ta.number = il.to_number
				where il.source = 'embedding' and il.decided_at is null
				  and fa.state = 'open' and ta.state = 'open'
				""" + typeClause, Long.class);
		return n != null ? n : 0;
	}

	/**
	 * Number of pending (unresolved) embedding candidates involving this item. A pair whose
	 * other side was closed on GitHub is resolved by that closure and no longer counts.
	 */
	public int pendingCandidateCount(int itemNumber) {
		Long n = this.jdbc.getJdbcTemplate().queryForObject("""
				select count(*) from item_link il
				join gh_item fa on fa.number = il.from_number
				join gh_item ta on ta.number = il.to_number
				where il.source = 'embedding' and il.decided_at is null
				  and fa.state = 'open' and ta.state = 'open'
				  and (il.from_number = ? or il.to_number = ?)
				""", Long.class, itemNumber, itemNumber);
		return n != null ? n.intValue() : 0;
	}

	public long count() {
		Long n = this.jdbc.getJdbcTemplate().queryForObject("select count(*) from item_link", Long.class);
		return n != null ? n : 0;
	}

	public record DuplicateItemView(int number, String kind, String title, String url, String area,
			String summary) {
	}

	public record DuplicatePairView(long id, String type, double confidence, String source,
			DuplicateItemView from, DuplicateItemView to) {
	}

}
