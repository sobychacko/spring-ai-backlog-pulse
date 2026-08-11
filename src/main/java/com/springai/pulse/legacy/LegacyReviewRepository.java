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

package com.springai.pulse.legacy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.springai.pulse.domain.LegacyVerdict;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Persistence for the legacy (EOL-branch) review stream: the AI verdict rows in
 * {@code legacy_review} plus the read queries backing the Legacy tab. List queries join on
 * {@code gh_item.state = 'open'} — closing an item on GitHub clears it here on the next sync.
 */
@Repository
public class LegacyReviewRepository {

	private final NamedParameterJdbcTemplate jdbc;

	public LegacyReviewRepository(NamedParameterJdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public void upsert(int itemNumber, LegacyVerdict verdict, String evidence, String modelUsed, String contentHash) {
		var params = new MapSqlParameterSource().addValue("itemNumber", itemNumber)
			.addValue("verdict", verdict.name())
			.addValue("evidence", evidence)
			.addValue("modelUsed", modelUsed)
			.addValue("contentHash", contentHash);
		this.jdbc.update("""
				insert into legacy_review (item_number, verdict, evidence, model_used, content_hash, checked_at)
				values (:itemNumber, :verdict, :evidence, :modelUsed, :contentHash, now())
				on conflict (item_number) do update set
						verdict = excluded.verdict, evidence = excluded.evidence,
						model_used = excluded.model_used, content_hash = excluded.content_hash,
						checked_at = now()
				""", params);
	}

	/**
	 * Open issues whose title/body mentions an EOL version (per {@code versionRegex}) and that
	 * the scan has not assessed yet — or whose content changed since it did.
	 */
	public List<Candidate> findNeedingScan(String versionRegex) {
		return this.jdbc.query("""
				select i.number, i.title, i.body, i.content_hash
				from gh_item i
				left join legacy_review lr on lr.item_number = i.number
				where i.kind = 'issue' and i.state = 'open'
					and (i.title ~* :regex or i.body ~* :regex)
					and (lr.item_number is null or lr.content_hash is distinct from i.content_hash)
				order by i.number
				""", new MapSqlParameterSource("regex", versionRegex),
				(rs, n) -> new Candidate(rs.getInt("number"), rs.getString("title"), rs.getString("body"),
						rs.getString("content_hash")));
	}

	/** Open PRs whose base branch is in the EOL list — the deterministic tier. */
	public List<EolPr> findEolPrs(List<String> eolBranches, String branch, String model) {
		var params = new MapSqlParameterSource()
			.addValue("branches", eolBranches)
			.addValue("branch", branch)
			.addValue("model", model);
		return this.jdbc.query("""
				select i.number, i.title, i.url, i.author, i.pr_base_branch,
						coalesce(i.pr_draft, false) as pr_draft, i.comments_count, i.reactions_total,
						c.summary, c.main_branch_applicable, c.main_branch_note,
						extract(days from now() - i.updated_at)::int as days_since_update
				from gh_item i
				left join classification c on c.item_number = i.number and c.model_used = :model
				where i.kind = 'pr' and i.state = 'open'
					and i.pr_base_branch in (:branches)
					and (:branch::text is null or i.pr_base_branch = :branch)
				order by i.updated_at asc
				""", params,
				(rs, n) -> new EolPr(rs.getInt("number"), rs.getString("title"), rs.getString("url"),
						rs.getString("author"), rs.getString("pr_base_branch"), rs.getBoolean("pr_draft"),
						rs.getInt("comments_count"), rs.getInt("reactions_total"), rs.getString("summary"),
						rs.getString("main_branch_applicable"), rs.getString("main_branch_note"),
						rs.getInt("days_since_update")));
	}

	/** Scanned open issues, optionally filtered by verdict. LEGACY_ONLY first, then by engagement. */
	public List<LegacyIssue> findIssueCandidates(String verdict, String model) {
		var params = new MapSqlParameterSource().addValue("verdict", verdict).addValue("model", model);
		return this.jdbc.query("""
				select i.number, i.title, i.url, i.reactions_total, i.comments_count,
						c.area, c.summary, lr.verdict, lr.evidence,
						extract(days from now() - i.created_at)::int as age_days
				from legacy_review lr
				join gh_item i on i.number = lr.item_number
				left join classification c on c.item_number = i.number and c.model_used = :model
				where i.state = 'open'
					and (:verdict::text is null or lr.verdict = :verdict)
				order by case lr.verdict when 'LEGACY_ONLY' then 0 when 'UNCLEAR' then 1 else 2 end,
						(i.reactions_total + i.comments_count) desc, i.number
				""", params,
				(rs, n) -> new LegacyIssue(rs.getInt("number"), rs.getString("title"), rs.getString("url"),
						rs.getInt("reactions_total"), rs.getInt("comments_count"), rs.getString("area"),
						rs.getString("summary"), rs.getString("verdict"), rs.getString("evidence"),
						rs.getInt("age_days")));
	}

	/** Verdict → open-item count, for the filter chips. */
	public Map<String, Long> countsByVerdict() {
		Map<String, Long> counts = new LinkedHashMap<>();
		this.jdbc.getJdbcTemplate().query("""
				select lr.verdict, count(*) as cnt
				from legacy_review lr
				join gh_item i on i.number = lr.item_number
				where i.state = 'open'
				group by lr.verdict
				""", rs -> {
			counts.put(rs.getString("verdict"), rs.getLong("cnt"));
		});
		return counts;
	}

	/** Candidate issue text as fed to the scan. */
	public record Candidate(int number, String title, String body, String contentHash) {
	}

	/** Deterministic tier: an open PR targeting an EOL branch. */
	public record EolPr(int number, String title, String url, String author, String baseBranch, boolean draft,
			int comments, int reactions, String summary, String mainBranchApplicable, String mainBranchNote,
			int daysSinceUpdate) {
	}

	/** AI-suggested tier: a scanned open issue with its verdict and evidence quote. */
	public record LegacyIssue(int number, String title, String url, int reactions, int comments, String area,
			String summary, String verdict, String evidence, int ageDays) {
	}

}
