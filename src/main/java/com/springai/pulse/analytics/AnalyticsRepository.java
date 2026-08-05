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

package com.springai.pulse.analytics;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import com.springai.pulse.domain.ModelIds;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Analytics queries. Every score here is computed deterministically from GitHub data
 * (SQL over {@code gh_item} and {@code classification}); the LLM contributes zero
 * numbers to any ranking.
 *
 * <p>Pulse score per area: {@code 0.35×volume + 0.35×velocity(30d) + 0.30×avg_engagement},
 * each component normalized to the per-dataset maximum so the result is always 0–100.
 *
 * <p>Value score per item: {@code 0.50×engagement + 0.30×log(age) + 0.10×severity +
 * 0.10×good_first_issue}, normalized similarly.
 *
 * @since 2.0.0
 */
@Repository
public class AnalyticsRepository {

	private final NamedParameterJdbcTemplate jdbc;

	private final ObjectMapper objectMapper;

	private static final TypeReference<List<String>> LIST_OF_STRING = new TypeReference<>() {
	};

	public AnalyticsRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
		this.jdbc = jdbc;
		this.objectMapper = objectMapper;
	}

	public List<PulseEntry> pulseByArea(String model) {
		return this.jdbc.query("""
				with area_stats as (
				  select
				    c.area,
				    count(*)                                                                   as volume,
				    count(*) filter (where i.created_at >= now() - interval '30 days')        as velocity,
				    coalesce(avg(i.reactions_total + i.comments_count), 0)                    as avg_engagement,
				    coalesce(sum(i.reactions_total + i.comments_count), 0)                    as total_engagement
				  from classification c
				  join gh_item i on i.number = c.item_number
				  where i.state = 'open' and c.area is not null and c.model_used = :model
				  group by c.area
				),
				maxima as (
				  select
				    greatest(max(volume), 1)::numeric       as max_vol,
				    greatest(max(velocity), 1)::numeric     as max_vel,
				    greatest(max(avg_engagement), 1)::numeric as max_eng
				  from area_stats
				)
				select
				  a.area,
				  a.volume,
				  a.velocity,
				  round(a.avg_engagement::numeric, 1)  as avg_engagement,
				  a.total_engagement,
				  round((
				    0.35 * a.volume::numeric    / m.max_vol +
				    0.35 * a.velocity::numeric  / m.max_vel +
				    0.30 * a.avg_engagement     / m.max_eng
				  ) * 100)::integer as pulse_score
				from area_stats a, maxima m
				order by pulse_score desc
				""", modelParams(model),
				(rs, n) -> new PulseEntry(rs.getString("area"), rs.getLong("volume"), rs.getLong("velocity"),
				rs.getDouble("avg_engagement"), rs.getLong("total_engagement"), rs.getInt("pulse_score")));
	}

	public List<ValueItem> valueQueue(int limit, String model) {
		return this.jdbc.query("""
				with item_scores as (
				  select
				    i.number,
				    i.kind,
				    i.title,
				    i.url,
				    i.reactions_total,
				    i.comments_count,
				    i.created_at,
				    c.type,
				    c.area,
				    c.severity,
				    c.good_first_issue,
				    c.summary,
				    c.providers,
				    (i.reactions_total + i.comments_count)::numeric           as engagement,
				    extract(epoch from (now() - i.created_at)) / 86400        as age_days,
				    (select count(*) from item_link il
				     join gh_item other on other.number =
				         case when il.from_number = i.number then il.to_number else il.from_number end
				     where il.source = 'embedding' and il.decided_at is null
				       and other.state = 'open'
				       and (il.from_number = i.number or il.to_number = i.number))::int as dup_count
				  from gh_item i
				  left join classification c on c.item_number = i.number and c.model_used = :model
				  where i.state = 'open'
				    and i.kind = 'issue'
				    and (i.author is null or i.author not like '%[bot]%')
				    and not (i.reactions_total = 0 and i.comments_count > 20)
				),
				maxima as (
				  select
				    greatest(max(engagement), 1)::numeric as max_eng,
				    greatest(max(age_days), 1)::numeric   as max_age
				  from item_scores
				)
				select
				  s.number, s.kind, s.title, s.url,
				  s.reactions_total, s.comments_count,
				  s.type, s.area, s.providers, s.severity, s.good_first_issue, s.summary,
				  s.dup_count,
				  round(s.age_days)::integer as age_days,
				  round((
				    0.50 * s.engagement / m.max_eng +
				    0.30 * ln(s.age_days + 1) / ln(m.max_age + 1) +
				    0.10 * case when s.good_first_issue then 1.0 else 0.0 end +
				    0.10 * case
				              when s.severity in ('HIGH', 'CRITICAL') then 1.0
				              when s.severity = 'MEDIUM'              then 0.5
				              else 0.0
				            end
				  ) * 100)::integer as value_score
				from item_scores s, maxima m
				order by value_score desc
				limit :limit
				""", modelParams(model).addValue("limit", Math.min(Math.max(limit, 1), 100)),
				(rs, n) -> new ValueItem(rs.getInt("number"), rs.getString("kind"), rs.getString("title"),
				rs.getString("url"), rs.getInt("reactions_total"), rs.getInt("comments_count"), rs.getString("type"),
				rs.getString("area"), parseList(rs.getString("providers")), rs.getString("severity"),
				(Boolean) rs.getObject("good_first_issue"), rs.getString("summary"), rs.getInt("age_days"),
				rs.getInt("value_score"), rs.getInt("dup_count")));
	}

	/**
	 * Area × week issue-creation heatmap for the last 52 weeks. Returns a structure
	 * ECharts heatmap can consume directly: parallel {@code areas} and {@code weeks} index
	 * arrays plus {@code data} as {@code [weekIdx, areaIdx, count]} triples.
	 */
	public HeatmapData heatmap(String model) {
		record Row(String area, String week, int count) {
		}
		List<Row> rows = this.jdbc.query("""
				select
				    c.area,
				    to_char(date_trunc('week', i.created_at), 'YYYY-MM-DD') as week,
				    count(*)::int as cnt
				from gh_item i
				join classification c on c.item_number = i.number and c.model_used = :model
				where i.created_at >= now() - interval '52 weeks'
				  and c.area is not null
				group by c.area, date_trunc('week', i.created_at)
				order by week, c.area
				""", modelParams(model), (rs, n) -> new Row(rs.getString("area"), rs.getString("week"), rs.getInt("cnt")));

		// Collect ordered unique sets
		Set<String> weekSet = new LinkedHashSet<>();
		Set<String> areaSet = new LinkedHashSet<>();
		// sort areas by total count desc for a better visual
		Map<String, Integer> areaTotals = new LinkedHashMap<>();
		for (Row r : rows) {
			weekSet.add(r.week());
			areaTotals.merge(r.area(), r.count(), Integer::sum);
		}
		areaTotals.entrySet()
			.stream()
			.sorted((a, b) -> b.getValue() - a.getValue())
			.forEach(e -> areaSet.add(e.getKey()));

		List<String> weeks = new ArrayList<>(weekSet);
		List<String> areas = new ArrayList<>(areaSet);
		Map<String, Integer> weekIdx = new LinkedHashMap<>();
		for (int i = 0; i < weeks.size(); i++) {
			weekIdx.put(weeks.get(i), i);
		}
		Map<String, Integer> areaIdx = new LinkedHashMap<>();
		for (int i = 0; i < areas.size(); i++) {
			areaIdx.put(areas.get(i), i);
		}

		List<int[]> data = rows.stream()
			.map(r -> new int[] { weekIdx.get(r.week()), areaIdx.get(r.area()), r.count() })
			.toList();

		return new HeatmapData(areas, weeks, data);
	}

	private static MapSqlParameterSource modelParams(String model) {
		return new MapSqlParameterSource("model",
				(model == null || model.isBlank()) ? ModelIds.DEFAULT_CLASSIFIER : model);
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

	public record PulseEntry(String area, long volume, long velocity, double avgEngagement, long totalEngagement,
			int pulseScore) {
	}

	public record ValueItem(int number, String kind, String title, String url, int reactions, int comments, String type,
			String area, List<String> providers, String severity, Boolean goodFirstIssue, String summary, int ageDays,
			int valueScore, int duplicateCount) {
	}

	public record HeatmapData(List<String> areas, List<String> weeks, List<int[]> data) {
	}

}
