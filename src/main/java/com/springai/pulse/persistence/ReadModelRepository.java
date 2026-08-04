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

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Read-only queries backing the web surface. Every number returned here is computed
 * deterministically from GitHub data (SQL over {@code gh_item}/{@code classification}); the LLM
 * contributes no counts.
 */
@Repository
public class ReadModelRepository {

	private final NamedParameterJdbcTemplate jdbc;

	private final ObjectMapper objectMapper;

	public ReadModelRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
		this.jdbc = jdbc;
		this.objectMapper = objectMapper;
	}

	public Facets facets() {
		var tmpl = this.jdbc.getJdbcTemplate();
		long total = orZero(tmpl.queryForObject("select count(*) from gh_item where state = 'open'", Long.class));
		long issues = orZero(tmpl.queryForObject("select count(*) from gh_item where kind = 'issue' and state = 'open'", Long.class));
		long prs = orZero(tmpl.queryForObject("select count(*) from gh_item where kind = 'pr' and state = 'open'", Long.class));
		long classified = orZero(tmpl.queryForObject("select count(*) from classification c join gh_item i on i.number = c.item_number where i.state = 'open'", Long.class));
		long openItems = total;
		long goodFirstIssueCount = orZero(
				tmpl.queryForObject("select count(*) from classification where good_first_issue = true", Long.class));

		List<FacetCount> byType = tmpl.query("""
				select coalesce(type, 'UNKNOWN') as key, count(*) as cnt
				from classification group by type order by cnt desc
				""", FACET_MAPPER);
		List<FacetCount> byArea = tmpl.query("""
				select coalesce(area, 'unknown') as key, count(*) as cnt
				from classification group by area order by cnt desc
				""", FACET_MAPPER);
		List<FacetCount> byProvider = tmpl.query("""
				select p.value as key, count(*) as cnt
				from classification c, jsonb_array_elements_text(c.providers) as p(value)
				group by p.value order by cnt desc
				""", FACET_MAPPER);
		List<FacetCount> byEnhancementKind = tmpl.query("""
				select coalesce(enhancement_kind, 'UNSET') as key, count(*) as cnt
				from classification where type = 'ENHANCEMENT' group by enhancement_kind order by cnt desc
				""", FACET_MAPPER);
		List<FacetCount> bySeverity = tmpl.query("""
				select coalesce(severity, 'UNKNOWN') as key, count(*) as cnt
				from classification group by severity order by cnt desc
				""", FACET_MAPPER);
		List<FacetCount> byVectorStore = tmpl.query("""
				select v.value as key, count(*) as cnt
				from classification c, jsonb_array_elements_text(c.vector_stores) as v(value)
				group by v.value order by cnt desc
				""", FACET_MAPPER);
		List<FacetCount> ageHistogram = tmpl.query("""
				select key, cnt from (
				  select
				    case
				      when created_at >= now() - interval '7 days'   then '<1 week'
				      when created_at >= now() - interval '30 days'  then '1-4 weeks'
				      when created_at >= now() - interval '180 days' then '1-6 months'
				      when created_at >= now() - interval '365 days' then '6-12 months'
				      else '>1 year'
				    end as key,
				    case
				      when created_at >= now() - interval '7 days'   then 1
				      when created_at >= now() - interval '30 days'  then 2
				      when created_at >= now() - interval '180 days' then 3
				      when created_at >= now() - interval '365 days' then 4
				      else 5
				    end as sort_order,
				    count(*) as cnt
				  from gh_item
				  where state = 'open'
				  group by 1, 2
				) t order by sort_order
				""", FACET_MAPPER);

		return new Facets(total, issues, prs, classified, openItems, goodFirstIssueCount, byType, byArea, byProvider,
				byEnhancementKind, bySeverity, byVectorStore, ageHistogram);
	}

	public List<ItemView> items(String type, String area, String weekOf, String enhancementKind,
			String provider, String vectorStore, String severity, Integer ageDaysMin, Integer ageDaysMax,
			Boolean goodFirstIssue, String kind, String search, int limit, int offset) {
		var params = new MapSqlParameterSource()
			.addValue("type", type)
			.addValue("area", area)
			.addValue("weekOf", weekOf)
			.addValue("enhancementKind", enhancementKind)
			.addValue("provider", provider)
			.addValue("vectorStore", vectorStore)
			.addValue("severity", severity)
			.addValue("ageDaysMin", ageDaysMin)
			.addValue("ageDaysMax", ageDaysMax)
			.addValue("goodFirstIssue", goodFirstIssue)
			.addValue("kind", kind)
			.addValue("search", search)
			.addValue("limit", limit)
			.addValue("offset", offset);
		return this.jdbc.query("""
				select i.number, i.kind, i.title, i.url, i.reactions_total, i.comments_count,
						c.type, c.area, c.providers, c.severity, c.good_first_issue, c.summary
				from gh_item i
				left join classification c on c.item_number = i.number
				where i.state = 'open'
					and (:type::text is null or c.type = :type)
					and (:area::text is null or c.area = :area)
					and (:weekOf::text is null or date_trunc('week', i.created_at) = :weekOf::date)
					and (:enhancementKind::text is null or c.enhancement_kind = :enhancementKind)
					and (:provider::text is null or :provider::text = any(
					        select jsonb_array_elements_text(coalesce(c.providers, '[]'::jsonb))))
					and (:vectorStore::text is null or :vectorStore::text = any(
					        select jsonb_array_elements_text(coalesce(c.vector_stores, '[]'::jsonb))))
					and (:severity::text is null or c.severity = :severity)
					and (:ageDaysMin::int is null or extract(epoch from (now() - i.created_at)) / 86400 >= :ageDaysMin)
					and (:ageDaysMax::int is null or extract(epoch from (now() - i.created_at)) / 86400 < :ageDaysMax)
					and (:goodFirstIssue::boolean is null or c.good_first_issue = :goodFirstIssue)
					and (:kind::text is null or i.kind = :kind)
					and (:search::text is null or i.title ilike '%' || :search || '%'
					        or c.summary ilike '%' || :search || '%')
				order by i.reactions_total desc, i.comments_count desc
				limit :limit offset :offset
				""", params, (rs, n) -> new ItemView(rs.getInt("number"), rs.getString("kind"), rs.getString("title"),
				rs.getString("url"), rs.getInt("reactions_total"), rs.getInt("comments_count"), rs.getString("type"),
				rs.getString("area"), parseList(rs.getString("providers")), rs.getString("severity"),
				(Boolean) rs.getObject("good_first_issue"), rs.getString("summary")));
	}

	public List<PrView> openPrs() {
		return this.jdbc.getJdbcTemplate().query("""
				select i.number, i.title, i.url, i.author, i.author_assoc,
						i.created_at, i.updated_at, i.comments_count, i.reactions_total,
						coalesce(i.pr_draft, false) as pr_draft, i.pr_base_branch,
						c.area, c.summary, c.review_complexity, c.review_notes,
						c.main_branch_applicable, c.main_branch_note,
						extract(days from now() - i.updated_at)::int as days_since_update
				from gh_item i
				left join classification c on c.item_number = i.number
				where i.kind = 'pr' and i.state = 'open'
				order by i.updated_at asc
				""", (rs, n) -> new PrView(rs.getInt("number"), rs.getString("title"), rs.getString("url"),
				rs.getString("author"), rs.getString("author_assoc"),
				rs.getString("created_at"), rs.getString("updated_at"),
				rs.getInt("comments_count"), rs.getInt("reactions_total"),
				rs.getBoolean("pr_draft"), rs.getString("pr_base_branch"),
				rs.getString("area"), rs.getString("summary"),
				rs.getString("review_complexity"), rs.getString("review_notes"),
				rs.getString("main_branch_applicable"), rs.getString("main_branch_note"),
				rs.getInt("days_since_update")));
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

	private static long orZero(Long value) {
		return value != null ? value : 0;
	}

	private static final org.springframework.jdbc.core.RowMapper<FacetCount> FACET_MAPPER = (rs,
			n) -> new FacetCount(rs.getString("key"), rs.getLong("cnt"));

	private static final tools.jackson.core.type.TypeReference<List<String>> LIST_OF_STRING = new tools.jackson.core.type.TypeReference<>() {
	};

	public record FacetCount(String key, long count) {
	}

	public record Facets(long totalItems, long issues, long prs, long classified, long openItems,
			long goodFirstIssueCount, List<FacetCount> byType, List<FacetCount> byArea,
			List<FacetCount> byProvider, List<FacetCount> byEnhancementKind, List<FacetCount> bySeverity,
			List<FacetCount> byVectorStore, List<FacetCount> ageHistogram) {
	}

	public record ItemView(int number, String kind, String title, String url, int reactions, int comments, String type,
			String area, List<String> providers, String severity, Boolean goodFirstIssue, String summary) {
	}

	public record PrView(int number, String title, String url, String author, String authorAssoc,
			String createdAt, String updatedAt, int comments, int reactions, boolean draft,
			String baseBranch, String area, String summary, String reviewComplexity, String reviewNotes,
			String mainBranchApplicable, String mainBranchNote, int daysSinceUpdate) {
	}

}
