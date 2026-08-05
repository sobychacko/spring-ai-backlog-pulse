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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import tools.jackson.databind.ObjectMapper;

import com.springai.pulse.domain.ModelIds;

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

	public Facets facets(String model) {
		var tmpl = this.jdbc.getJdbcTemplate();
		Map<String, String> m = Map.of("model", modelOrDefault(model));
		long total = orZero(tmpl.queryForObject("select count(*) from gh_item where state = 'open'", Long.class));
		long issues = orZero(tmpl.queryForObject("select count(*) from gh_item where kind = 'issue' and state = 'open'", Long.class));
		long prs = orZero(tmpl.queryForObject("select count(*) from gh_item where kind = 'pr' and state = 'open'", Long.class));
		long classified = orZero(this.jdbc.queryForObject(
				"select count(*) from classification c join gh_item i on i.number = c.item_number where i.state = 'open' and c.model_used = :model",
				m, Long.class));
		long openItems = total;
		long goodFirstIssueCount = orZero(this.jdbc.queryForObject(
				"select count(*) from classification where good_first_issue = true and model_used = :model", m,
				Long.class));

		List<FacetCount> byType = this.jdbc.query("""
				select coalesce(type, 'UNKNOWN') as key, count(*) as cnt
				from classification where model_used = :model group by type order by cnt desc
				""", m, FACET_MAPPER);
		List<FacetCount> byArea = this.jdbc.query("""
				select coalesce(area, 'unknown') as key, count(*) as cnt
				from classification where model_used = :model group by area order by cnt desc
				""", m, FACET_MAPPER);
		List<FacetCount> byProvider = this.jdbc.query("""
				select p.value as key, count(*) as cnt
				from classification c, jsonb_array_elements_text(c.providers) as p(value)
				where c.model_used = :model
				group by p.value order by cnt desc
				""", m, FACET_MAPPER);
		List<FacetCount> byEnhancementKind = this.jdbc.query("""
				select coalesce(enhancement_kind, 'UNSET') as key, count(*) as cnt
				from classification where type = 'ENHANCEMENT' and model_used = :model
				group by enhancement_kind order by cnt desc
				""", m, FACET_MAPPER);
		List<FacetCount> bySeverity = this.jdbc.query("""
				select coalesce(severity, 'UNKNOWN') as key, count(*) as cnt
				from classification where model_used = :model group by severity order by cnt desc
				""", m, FACET_MAPPER);
		List<FacetCount> byVectorStore = this.jdbc.query("""
				select v.value as key, count(*) as cnt
				from classification c, jsonb_array_elements_text(c.vector_stores) as v(value)
				where c.model_used = :model
				group by v.value order by cnt desc
				""", m, FACET_MAPPER);
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
			Boolean goodFirstIssue, String kind, String search, int limit, int offset, String model) {
		var params = new MapSqlParameterSource()
			.addValue("model", modelOrDefault(model))
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
				left join classification c on c.item_number = i.number and c.model_used = :model
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

	public List<PrView> openPrs(String model) {
		return this.jdbc.query("""
				select i.number, i.title, i.url, i.author, i.author_assoc,
						i.created_at, i.updated_at, i.comments_count, i.reactions_total,
						coalesce(i.pr_draft, false) as pr_draft, i.pr_base_branch, i.assignees,
						c.area, c.summary, c.review_complexity, c.review_notes,
						c.main_branch_applicable, c.main_branch_note,
						extract(days from now() - i.updated_at)::int as days_since_update
				from gh_item i
				left join classification c on c.item_number = i.number and c.model_used = :model
				where i.kind = 'pr' and i.state = 'open'
				order by i.updated_at asc
				""", Map.of("model", modelOrDefault(model)),
				(rs, n) -> new PrView(rs.getInt("number"), rs.getString("title"), rs.getString("url"),
				rs.getString("author"), rs.getString("author_assoc"),
				rs.getString("created_at"), rs.getString("updated_at"),
				rs.getInt("comments_count"), rs.getInt("reactions_total"),
				rs.getBoolean("pr_draft"), rs.getString("pr_base_branch"),
				parseList(rs.getString("assignees")),
				rs.getString("area"), rs.getString("summary"),
				rs.getString("review_complexity"), rs.getString("review_notes"),
				rs.getString("main_branch_applicable"), rs.getString("main_branch_note"),
				rs.getInt("days_since_update")));
	}

	/**
	 * Items both models classified but disagree on (type, area, or severity), plus coverage
	 * counts. Ordered by engagement so the most consequential disagreements surface first.
	 */
	public ComparisonResult modelComparison(String haikuModel, String sonnetModel, int limit) {
		var params = new MapSqlParameterSource()
			.addValue("haiku", haikuModel)
			.addValue("sonnet", sonnetModel)
			.addValue("limit", limit);
		String disagreeFilter = """
				h.type is distinct from s.type
				or h.area is distinct from s.area
				or h.severity is distinct from s.severity
				""";
		List<ComparisonItem> items = this.jdbc.query("""
				select i.number, i.kind, i.title, i.url, i.reactions_total, i.comments_count,
						h.type as h_type, h.area as h_area, h.severity as h_severity, h.summary as h_summary,
						s.type as s_type, s.area as s_area, s.severity as s_severity, s.summary as s_summary
				from gh_item i
				join classification h on h.item_number = i.number and h.model_used = :haiku
				join classification s on s.item_number = i.number and s.model_used = :sonnet
				where %s
				order by (i.reactions_total + i.comments_count) desc, i.number
				limit :limit
				""".formatted(disagreeFilter), params, (rs, n) -> {
			String hType = rs.getString("h_type");
			String sType = rs.getString("s_type");
			String hArea = rs.getString("h_area");
			String sArea = rs.getString("s_area");
			String hSeverity = rs.getString("h_severity");
			String sSeverity = rs.getString("s_severity");
			List<String> disagrees = new ArrayList<>();
			if (!Objects.equals(hType, sType)) {
				disagrees.add("type");
			}
			if (!Objects.equals(hArea, sArea)) {
				disagrees.add("area");
			}
			if (!Objects.equals(hSeverity, sSeverity)) {
				disagrees.add("severity");
			}
			return new ComparisonItem(rs.getInt("number"), rs.getString("kind"), rs.getString("title"),
					rs.getString("url"), rs.getInt("reactions_total"), rs.getInt("comments_count"), hType, hArea,
					hSeverity, rs.getString("h_summary"), sType, sArea, sSeverity, rs.getString("s_summary"),
					disagrees);
		});
		long compared = orZero(this.jdbc.queryForObject("""
				select count(*) from classification h
				join classification s on s.item_number = h.item_number and s.model_used = :sonnet
				where h.model_used = :haiku
				""", params, Long.class));
		long disagreements = orZero(this.jdbc.queryForObject("""
				select count(*) from classification h
				join classification s on s.item_number = h.item_number and s.model_used = :sonnet
				where h.model_used = :haiku and (%s)
				""".formatted(disagreeFilter), params, Long.class));
		long haikuOnly = orZero(this.jdbc.queryForObject("""
				select count(*) from classification h
				where h.model_used = :haiku and not exists (
					select 1 from classification s
					where s.item_number = h.item_number and s.model_used = :sonnet)
				""", params, Long.class));
		long sonnetOnly = orZero(this.jdbc.queryForObject("""
				select count(*) from classification s
				where s.model_used = :sonnet and not exists (
					select 1 from classification h
					where h.item_number = s.item_number and h.model_used = :haiku)
				""", params, Long.class));
		return new ComparisonResult(items, compared, disagreements, haikuOnly, sonnetOnly);
	}

	private static String modelOrDefault(String model) {
		return (model == null || model.isBlank()) ? ModelIds.DEFAULT_CLASSIFIER : model;
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
			String baseBranch, List<String> assignees, String area, String summary, String reviewComplexity,
			String reviewNotes, String mainBranchApplicable, String mainBranchNote, int daysSinceUpdate) {
	}

	/** One item both models classified, with the fields they disagree on. */
	public record ComparisonItem(int number, String kind, String title, String url, int reactions, int comments,
			String haikuType, String haikuArea, String haikuSeverity, String haikuSummary,
			String sonnetType, String sonnetArea, String sonnetSeverity, String sonnetSummary,
			List<String> disagrees) {
	}

	/** Disagreeing items (capped) plus whole-dataset coverage/disagreement counts. */
	public record ComparisonResult(List<ComparisonItem> items, long compared, long disagreements,
			long haikuOnly, long sonnetOnly) {
	}

}
