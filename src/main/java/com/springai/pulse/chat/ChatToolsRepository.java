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

package com.springai.pulse.chat;

import java.util.Collection;
import java.util.List;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import com.springai.pulse.domain.ModelIds;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * SQL behind the chat tools. Everything here is deterministic aggregation over GitHub facts —
 * the chat LLM routes questions to these queries and narrates the results; it contributes no
 * numbers. Aggregations count open items only (the backlog), and "unique authors" counts item
 * authors — commenters/reactors are in the engagement totals but not identified.
 */
@Repository
public class ChatToolsRepository {

	private static final TypeReference<List<String>> LIST_OF_STRING = new TypeReference<>() {
	};

	private final NamedParameterJdbcTemplate jdbc;

	private final ObjectMapper objectMapper;

	public ChatToolsRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
		this.jdbc = jdbc;
		this.objectMapper = objectMapper;
	}

	/** Aggregate GH facts over a matched set of item numbers (open items only). */
	public ThemeAggregate aggregate(Collection<Integer> numbers) {
		if (numbers.isEmpty()) {
			return new ThemeAggregate(0, 0, 0, 0, 0, 0, null, null, 0, 0);
		}
		return this.jdbc.queryForObject("""
				select count(*)                                                          as items,
				       count(*) filter (where kind = 'issue')                            as issues,
				       count(*) filter (where kind = 'pr')                               as prs,
				       count(distinct author)                                            as unique_authors,
				       coalesce(sum(reactions_total), 0)                                 as reactions,
				       coalesce(sum(comments_count), 0)                                  as comments,
				       to_char(min(created_at), 'YYYY-MM-DD')                            as earliest,
				       to_char(max(created_at), 'YYYY-MM-DD')                            as latest,
				       count(*) filter (where created_at >= now() - interval '30 days')  as opened_30d,
				       count(*) filter (where created_at >= now() - interval '90 days')  as opened_90d
				from gh_item
				where number in (:numbers) and state = 'open'
				""", new MapSqlParameterSource("numbers", numbers),
				(rs, n) -> new ThemeAggregate(rs.getInt("items"), rs.getInt("issues"), rs.getInt("prs"),
						rs.getInt("unique_authors"), rs.getInt("reactions"), rs.getInt("comments"),
						rs.getString("earliest"), rs.getString("latest"), rs.getInt("opened_30d"),
						rs.getInt("opened_90d")));
	}

	/** Item authors in the matched set, ranked by item count then engagement. */
	public List<AuthorStat> topAuthors(Collection<Integer> numbers, int limit) {
		if (numbers.isEmpty()) {
			return List.of();
		}
		return this.jdbc.query("""
				select author, count(*) as items,
				       coalesce(sum(reactions_total + comments_count), 0) as engagement
				from gh_item
				where number in (:numbers) and state = 'open' and author is not null
				group by author
				order by items desc, engagement desc
				limit :limit
				""", new MapSqlParameterSource("numbers", numbers).addValue("limit", limit),
				(rs, n) -> new AuthorStat(rs.getString("author"), rs.getInt("items"), rs.getInt("engagement")));
	}

	/**
	 * Where a matched set stands among ALL discovered theme clusters — the deterministic
	 * grounding for "popular": percentile of the set's size and engagement against the
	 * cluster distribution.
	 */
	public ClusterStanding clusterStanding(int matchedItems, long matchedEngagement) {
		return this.jdbc.queryForObject("""
				with c as (
				  select tc.size,
				         coalesce(sum(i.reactions_total + i.comments_count), 0) as engagement
				  from theme_cluster tc
				  join item_cluster ic on ic.cluster_id = tc.id
				  join gh_item i on i.number = ic.item_number
				  group by tc.id, tc.size
				)
				select count(*)                                                             as cluster_count,
				       coalesce(percentile_cont(0.5) within group (order by size), 0)       as median_size,
				       coalesce(max(size), 0)                                               as max_size,
				       round(100.0 * count(*) filter (where size < :items) / greatest(count(*), 1))
				                                                                            as size_percentile,
				       round(100.0 * count(*) filter (where engagement < :engagement) / greatest(count(*), 1))
				                                                                            as engagement_percentile
				from c
				""", new MapSqlParameterSource("items", matchedItems).addValue("engagement", matchedEngagement),
				(rs, n) -> new ClusterStanding(rs.getInt("cluster_count"), rs.getInt("median_size"),
						rs.getInt("max_size"), rs.getInt("size_percentile"), rs.getInt("engagement_percentile")));
	}

	/** Top open items of a matched set by engagement, in the shape the UI renders as cards. */
	public List<Card> topItems(Collection<Integer> numbers, int limit) {
		if (numbers.isEmpty()) {
			return List.of();
		}
		return this.jdbc.query("""
				select i.number, i.kind, i.title, i.url, i.reactions_total, i.comments_count,
				       c.type, c.area, c.severity, c.summary, c.providers, c.good_first_issue
				from gh_item i
				left join classification c on c.item_number = i.number and c.model_used = :model
				where i.number in (:numbers) and i.state = 'open'
				order by (i.reactions_total + i.comments_count) desc
				limit :limit
				""",
				new MapSqlParameterSource("numbers", numbers).addValue("limit", limit)
					.addValue("model", ModelIds.DEFAULT_CLASSIFIER),
				(rs, n) -> new Card(rs.getInt("number"), rs.getString("kind"), rs.getString("title"),
						rs.getString("url"), rs.getInt("reactions_total"), rs.getInt("comments_count"),
						rs.getString("type"), rs.getString("area"), rs.getString("severity"),
						rs.getString("summary"), parseList(rs.getString("providers")),
						(Boolean) rs.getObject("good_first_issue")));
	}

	/**
	 * Weekly opened vs closed counts. Scope: an area, a matched number set, or the whole
	 * backlog when both are null. Closed counts only reflect closures observed since the app
	 * began syncing (see {@link #closuresTrackedSince()}) — earlier outflow is invisible.
	 */
	public List<TrendPoint> trend(String area, Collection<Integer> numbers, int weeks) {
		String scope = "";
		var params = new MapSqlParameterSource().addValue("weeks", weeks)
			.addValue("model", ModelIds.DEFAULT_CLASSIFIER);
		if (numbers != null) {
			if (numbers.isEmpty()) {
				return List.of();
			}
			scope = "and i.number in (:numbers)";
			params.addValue("numbers", numbers);
		}
		else if (area != null) {
			scope = """
					and exists (select 1 from classification c
					            where c.item_number = i.number and c.model_used = :model and c.area = :area)""";
			params.addValue("area", area);
		}
		return this.jdbc.query("""
				select to_char(w.week, 'YYYY-MM-DD') as week,
				       (select count(*) from gh_item i
				        where date_trunc('week', i.created_at) = w.week %s) as opened,
				       (select count(*) from gh_item i
				        where i.closed_at is not null and date_trunc('week', i.closed_at) = w.week %s) as closed
				from (select generate_series(date_trunc('week', now()) - (:weeks - 1) * interval '1 week',
				                             date_trunc('week', now()), interval '1 week') as week) w
				order by w.week
				""".formatted(scope, scope), params,
				(rs, n) -> new TrendPoint(rs.getString("week"), rs.getInt("opened"), rs.getInt("closed")));
	}

	/** Earliest observed closure — the boundary of outflow visibility. */
	public String closuresTrackedSince() {
		return this.jdbc.queryForObject("select to_char(min(closed_at), 'YYYY-MM-DD') from gh_item",
				new MapSqlParameterSource(), String.class);
	}

	/** GH facts + AI-suggested fields + cluster membership for one item; null if unknown. */
	public Dossier dossier(int number) {
		List<Dossier> rows = this.jdbc.query("""
				select i.number, i.kind, i.title, i.state, i.author, i.url,
				       to_char(i.created_at, 'YYYY-MM-DD') as created,
				       to_char(i.updated_at, 'YYYY-MM-DD') as updated,
				       i.reactions_total, i.comments_count, i.pr_base_branch,
				       left(coalesce(i.body, ''), 800) as body_excerpt,
				       c.type, c.area, c.severity, c.summary, c.providers, c.good_first_issue,
				       lr.verdict as legacy_verdict,
				       tc.label as cluster_label
				from gh_item i
				left join classification c on c.item_number = i.number and c.model_used = :model
				left join legacy_review lr on lr.item_number = i.number
				left join item_cluster ic on ic.item_number = i.number
				left join theme_cluster tc on tc.id = ic.cluster_id
				where i.number = :number
				limit 1
				""",
				new MapSqlParameterSource("number", number).addValue("model", ModelIds.DEFAULT_CLASSIFIER),
				(rs, n) -> new Dossier(rs.getInt("number"), rs.getString("kind"), rs.getString("title"),
						rs.getString("state"), rs.getString("author"), rs.getString("url"), rs.getString("created"),
						rs.getString("updated"), rs.getInt("reactions_total"), rs.getInt("comments_count"),
						rs.getString("pr_base_branch"), rs.getString("body_excerpt"), rs.getString("type"),
						rs.getString("area"), rs.getString("severity"), rs.getString("summary"),
						parseList(rs.getString("providers")), (Boolean) rs.getObject("good_first_issue"),
						rs.getString("legacy_verdict"), rs.getString("cluster_label")));
		return rows.isEmpty() ? null : rows.get(0);
	}

	/** Duplicate/related links touching an item (GH refs + embedding candidates). */
	public List<LinkedItem> links(int number, int limit) {
		return this.jdbc.query("""
				select il.type, il.source, il.confidence, il.confirmed,
				       other.number as other_number, other.title as other_title, other.state as other_state
				from item_link il
				join gh_item other on other.number =
				    case when il.from_number = :number then il.to_number else il.from_number end
				where il.from_number = :number or il.to_number = :number
				order by il.confidence desc nulls last
				limit :limit
				""", new MapSqlParameterSource("number", number).addValue("limit", limit),
				(rs, n) -> new LinkedItem(rs.getString("type"), rs.getString("source"), rs.getDouble("confidence"),
						rs.getBoolean("confirmed"), rs.getInt("other_number"), rs.getString("other_title"),
						rs.getString("other_state")));
	}

	private List<String> parseList(String json) {
		if (json == null || json.isBlank()) {
			return List.of();
		}
		try {
			return this.objectMapper.readValue(json, LIST_OF_STRING);
		}
		catch (Exception ex) {
			return List.of();
		}
	}

	public record ThemeAggregate(int items, int issues, int prs, int uniqueAuthors, int totalReactions,
			int totalComments, String earliestCreated, String latestCreated, int openedLast30d, int openedLast90d) {
	}

	public record AuthorStat(String handle, int items, int engagement) {
	}

	public record ClusterStanding(int clusterCount, int medianClusterSize, int maxClusterSize, int sizePercentile,
			int engagementPercentile) {
	}

	/** Mirrors the UI's {@code ItemView} shape so chat results render as the same item cards. */
	public record Card(int number, String kind, String title, String url, int reactions, int comments, String type,
			String area, String severity, String summary, List<String> providers, Boolean goodFirstIssue) {
	}

	public record TrendPoint(String weekStart, int opened, int closed) {
	}

	public record Dossier(int number, String kind, String title, String state, String author, String url,
			String createdAt, String updatedAt, int reactions, int comments, String prBaseBranch, String bodyExcerpt,
			String type, String area, String severity, String summary, List<String> providers, Boolean goodFirstIssue,
			String legacyVerdict, String clusterLabel) {
	}

	public record LinkedItem(String type, String source, double confidence, boolean confirmed, int otherNumber,
			String otherTitle, String otherState) {
	}

}
