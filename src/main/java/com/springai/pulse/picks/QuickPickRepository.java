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

package com.springai.pulse.picks;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.springai.pulse.analytics.AnalyticsRepository;
import com.springai.pulse.domain.PickAssessment;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Persistence for quick picks: the AI assessment rows in {@code quick_pick}, the deterministic
 * candidate pool that feeds the scan, and the read queries behind the "Today's picks" strip.
 *
 * <p>Eligibility is one SQL fragment applied both when choosing candidates and when listing
 * picks, so an issue that gets assigned, closed, or claimed by a PR after its assessment drops
 * out of the list on the next sync without any re-scan.
 */
@Repository
public class QuickPickRepository {

	/**
	 * What makes an open issue a candidate at all, before any AI judgment: an unassigned, human-
	 * filed issue whose classification says it is a bug, doc fix, task, or an improvement to an
	 * existing feature (never a new feature — those are not one-hour jobs), not legacy-only, and
	 * with no open PR already attached (a GitHub "closes" link or a confirmed AI pr-fixes-issue
	 * link; unconfirmed AI candidates do not disqualify).
	 */
	private static final String ELIGIBLE = """
			i.kind = 'issue' and i.state = 'open'
			and (i.author is null or i.author not like '%[bot]%')
			and jsonb_array_length(i.assignees) = 0
			and (c.type in ('BUG', 'DOCUMENTATION', 'TASK')
			     or (c.type = 'ENHANCEMENT' and c.enhancement_kind = 'IMPROVEMENT'))
			and not exists (select 1 from legacy_review lr
			                where lr.item_number = i.number and lr.verdict = 'LEGACY_ONLY')
			and not exists (select 1 from item_link il
			                join gh_item p on p.number =
			                    case when il.from_number = i.number then il.to_number else il.from_number end
			                where (il.from_number = i.number or il.to_number = i.number)
			                  and p.kind = 'pr' and p.state = 'open'
			                  and ((il.source = 'gh_reference' and il.type = 'closes')
			                       or (il.type = 'pr_fixes_issue' and il.confirmed)))
			""";

	private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
	};

	private final NamedParameterJdbcTemplate jdbc;

	private final ObjectMapper json = new ObjectMapper();

	public QuickPickRepository(NamedParameterJdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * The top {@code poolSize} eligible issues by value score that have no assessment yet, or
	 * whose text or comment thread changed since the last one. Decided items (taken or skipped)
	 * are never re-assessed — the human's call stands.
	 */
	public List<Candidate> findNeedingAssessment(String model, int poolSize) {
		var params = new MapSqlParameterSource().addValue("model", model).addValue("poolSize", poolSize);
		return this.jdbc.query("with " + AnalyticsRepository.VALUE_SCORE_CTES + """
				select i.number, i.title, i.body, i.labels::text as labels, i.content_hash, i.comments_count,
				       v.value_score
				from value_scores v
				join gh_item i on i.number = v.number
				left join classification c on c.item_number = i.number and c.model_used = :model
				left join quick_pick qp on qp.item_number = i.number
				where
				""" + ELIGIBLE + """
				  and (qp.item_number is null
				       or (qp.decision is null
				           and (qp.content_hash is distinct from i.content_hash
				                or qp.comments_seen <> i.comments_count)))
				order by v.value_score desc, i.number
				limit :poolSize
				""", params,
				(rs, n) -> new Candidate(rs.getInt("number"), rs.getString("title"), rs.getString("body"),
						parseList(rs.getString("labels")), rs.getString("content_hash"),
						rs.getInt("comments_count"), rs.getInt("value_score")));
	}

	public void upsert(int itemNumber, PickAssessment a, String modelUsed, String contentHash, int commentsSeen) {
		var params = new MapSqlParameterSource().addValue("itemNumber", itemNumber)
			.addValue("effort", a.effort().name())
			.addValue("apiRisk", a.apiRisk().name())
			.addValue("blockers", toJson(a.blockers().stream().map(Enum::name).toList()))
			.addValue("likelyScope", a.likelyScope())
			.addValue("evidence", a.evidence())
			.addValue("firstStep", a.firstStep())
			.addValue("confidence", a.confidence() != null ? a.confidence().name() : null)
			.addValue("modelUsed", modelUsed)
			.addValue("contentHash", contentHash)
			.addValue("commentsSeen", commentsSeen);
		this.jdbc.update("""
				insert into quick_pick (item_number, effort, api_risk, blockers, likely_scope, evidence, first_step,
				                        confidence, model_used, content_hash, comments_seen, assessed_at)
				values (:itemNumber, :effort, :apiRisk, :blockers::jsonb, :likelyScope, :evidence, :firstStep,
				        :confidence, :modelUsed, :contentHash, :commentsSeen, now())
				on conflict (item_number) do update set
						effort = excluded.effort, api_risk = excluded.api_risk, blockers = excluded.blockers,
						likely_scope = excluded.likely_scope, evidence = excluded.evidence,
						first_step = excluded.first_step, confidence = excluded.confidence,
						model_used = excluded.model_used, content_hash = excluded.content_hash,
						comments_seen = excluded.comments_seen, assessed_at = now()
				""", params);
	}

	/**
	 * The list itself: undecided, still-eligible issues whose assessment says the effort is one of
	 * {@code efforts}, the API impact is not breaking, and nothing blocks them — ordered purely by
	 * the GitHub-derived value score. Blocked or breaking items are available via
	 * {@link #findAssessed} for the full view.
	 */
	public List<PickView> findPicks(String model, List<String> efforts, int limit) {
		var params = new MapSqlParameterSource().addValue("model", model)
			.addValue("efforts", efforts)
			.addValue("limit", limit);
		return this.jdbc.query("with " + AnalyticsRepository.VALUE_SCORE_CTES + PICK_SELECT + """
				where
				""" + ELIGIBLE + """
				  and qp.decision is null
				  and qp.effort in (:efforts)
				  and qp.api_risk in ('NONE', 'ADDITIVE')
				  and qp.blockers = '[]'::jsonb
				order by v.value_score desc, i.number
				limit :limit
				""", params, this::mapPick);
	}

	/** Every undecided assessed issue that is still eligible, best value first. */
	public List<PickView> findAssessed(String model, int limit) {
		var params = new MapSqlParameterSource().addValue("model", model).addValue("limit", limit);
		return this.jdbc.query("with " + AnalyticsRepository.VALUE_SCORE_CTES + PICK_SELECT + """
				where
				""" + ELIGIBLE + """
				  and qp.decision is null
				order by v.value_score desc, i.number
				limit :limit
				""", params, this::mapPick);
	}

	/** Issues the maintainer took or skipped, most recent decision first. */
	public List<PickView> findDecided(String model, int limit) {
		var params = new MapSqlParameterSource().addValue("model", model).addValue("limit", limit);
		return this.jdbc.query("with " + AnalyticsRepository.VALUE_SCORE_CTES + PICK_SELECT + """
				where qp.decision is not null
				order by qp.decided_at desc, i.number
				limit :limit
				""", params, this::mapPick);
	}

	/** effort → count over undecided, still-eligible assessed issues (for the summary chips). */
	public Map<String, Long> countsByEffort(String model) {
		Map<String, Long> counts = new LinkedHashMap<>();
		this.jdbc.query("""
				select qp.effort, count(*) as cnt
				from quick_pick qp
				join gh_item i on i.number = qp.item_number
				left join classification c on c.item_number = i.number and c.model_used = :model
				where qp.decision is null and
				""" + ELIGIBLE + """
				group by qp.effort
				""", new MapSqlParameterSource("model", model), rs -> {
			counts.put(rs.getString("effort"), rs.getLong("cnt"));
		});
		return counts;
	}

	/**
	 * Record the human's call. {@code decision} is TAKEN or SKIPPED; null clears an earlier
	 * decision (the "undo" path) and puts the item back in the list.
	 * @return false when no assessment exists for the item
	 */
	public boolean decide(int itemNumber, String decision) {
		var params = new MapSqlParameterSource().addValue("itemNumber", itemNumber).addValue("decision", decision);
		return this.jdbc.update("""
				update quick_pick
				set decision = :decision,
				    decided_at = case when :decision::text is null then null else now() end
				where item_number = :itemNumber
				""", params) > 0;
	}

	public Instant lastAssessedAt() {
		OffsetDateTime at = this.jdbc.queryForObject("select max(assessed_at) from quick_pick",
				new MapSqlParameterSource(), OffsetDateTime.class);
		return at != null ? at.toInstant() : null;
	}

	private static final String PICK_SELECT = """
			select i.number, i.title, i.url, i.reactions_total, i.comments_count, i.labels::text as labels,
			       c.type, c.area, c.severity, c.summary,
			       v.age_days, v.value_score,
			       qp.effort, qp.api_risk, qp.blockers::text as blockers, qp.likely_scope, qp.evidence,
			       qp.first_step, qp.confidence, qp.model_used, qp.assessed_at, qp.decision, qp.decided_at
			from quick_pick qp
			join gh_item i on i.number = qp.item_number
			left join value_scores v on v.number = i.number
			left join classification c on c.item_number = i.number and c.model_used = :model
			""";

	private PickView mapPick(ResultSet rs, int n) throws SQLException {
		OffsetDateTime assessed = rs.getObject("assessed_at", OffsetDateTime.class);
		OffsetDateTime decided = rs.getObject("decided_at", OffsetDateTime.class);
		return new PickView(rs.getInt("number"), rs.getString("title"), rs.getString("url"),
				rs.getInt("reactions_total"), rs.getInt("comments_count"), parseList(rs.getString("labels")),
				rs.getString("type"), rs.getString("area"), rs.getString("severity"), rs.getString("summary"),
				rs.getInt("age_days"), rs.getInt("value_score"), rs.getString("effort"), rs.getString("api_risk"),
				parseList(rs.getString("blockers")), rs.getString("likely_scope"), rs.getString("evidence"),
				rs.getString("first_step"), rs.getString("confidence"), rs.getString("model_used"),
				assessed != null ? assessed.toInstant() : null, rs.getString("decision"),
				decided != null ? decided.toInstant() : null);
	}

	private List<String> parseList(String jsonArray) {
		if (jsonArray == null || jsonArray.isBlank()) {
			return List.of();
		}
		try {
			return this.json.readValue(jsonArray, STRING_LIST);
		}
		catch (Exception ex) {
			return List.of();
		}
	}

	private String toJson(List<String> values) {
		try {
			return this.json.writeValueAsString(values);
		}
		catch (Exception ex) {
			return "[]";
		}
	}

	/** Candidate issue as fed to the scan, with the labels GitHub shows on it. */
	public record Candidate(int number, String title, String body, List<String> labels, String contentHash,
			int commentsCount, int valueScore) {
	}

	/** One assessed issue as shown in the UI: GitHub facts, the value score, and the AI verdict. */
	public record PickView(int number, String title, String url, int reactions, int comments, List<String> labels,
			String type, String area, String severity, String summary, int ageDays, int valueScore, String effort,
			String apiRisk, List<String> blockers, String likelyScope, String evidence, String firstStep,
			String confidence, String modelUsed, Instant assessedAt, String decision, Instant decidedAt) {
	}

}
