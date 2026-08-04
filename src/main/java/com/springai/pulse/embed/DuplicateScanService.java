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

package com.springai.pulse.embed;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.springai.pulse.persistence.ItemLinkRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Scans the pgvector store for semantically similar item pairs and inserts them into
 * {@code item_link} as embedding-derived candidates.
 *
 * <p>Relationship types:
 * <ul>
 *   <li>{@code duplicate_candidate} — two issues about the same bug/request
 *   <li>{@code competing_pr} — two PRs working on the same thing
 *   <li>{@code pr_fixes_issue} — a PR that addresses an issue (LLM-classified)
 *   <li>{@code related} — similar but not a direct fix relationship
 * </ul>
 *
 * <p>For PR↔Issue pairs the LLM decides between {@code pr_fixes_issue} and {@code related}
 * because the embedding alone cannot distinguish "this PR implements that issue" from "this PR
 * and that issue happen to be about the same topic independently." Haiku is used since the
 * classification requires only a short output.
 */
@Service
public class DuplicateScanService {

	private static final Logger logger = LoggerFactory.getLogger(DuplicateScanService.class);

	private static final String SYSTEM = """
			You are analysing a GitHub issue and pull request from the Spring AI project.
			Determine whether the PR directly fixes / implements the issue, or whether they are
			just independently about the same topic.
			Respond with EXACTLY one of: pr_fixes_issue OR related
			""";

	private final JdbcTemplate jdbc;

	private final ItemLinkRepository links;

	private final ChatClient chat;

	public DuplicateScanService(JdbcTemplate jdbc, ItemLinkRepository links, ChatModel chatModel) {
		this.jdbc = jdbc;
		this.links = links;
		this.chat = ChatClient.create(chatModel);
	}

	/**
	 * Find candidate pairs in the vector store with similarity above {@code threshold} and
	 * insert them into {@code item_link}.
	 * @param threshold cosine similarity threshold (0–1); 0.85 is a good default
	 * @return number of new candidates inserted
	 */
	public int scan(double threshold) {
		// 1. Fetch raw similar pairs from pgvector via a single lateral-join query.
		List<RawPair> rawPairs = fetchRawPairs(threshold);
		logger.info("Duplicate scan: found {} raw pairs above threshold {}", rawPairs.size(), threshold);
		if (rawPairs.isEmpty()) {
			return 0;
		}

		// 2. Skip pairs that already have a decided outcome.
		Set<String> decided = this.links.decidedPairs();
		List<RawPair> pending = rawPairs.stream()
			.filter(p -> !decided.contains(p.fromNum() + ":" + p.toNum())
					&& !decided.contains(p.toNum() + ":" + p.fromNum()))
			.toList();
		logger.info("Duplicate scan: {} pairs after skipping already-decided", pending.size());

		// 3. Split by kind-pair and classify PR↔Issue pairs with LLM.
		int inserted = 0;
		List<RawPair> prIssuePairs = new ArrayList<>();

		for (RawPair pair : pending) {
			String fromKind = pair.fromKind();
			String toKind = pair.toKind();
			boolean isIssueIssue = "issue".equals(fromKind) && "issue".equals(toKind);
			boolean isPrPr = "pr".equals(fromKind) && "pr".equals(toKind);

			if (isIssueIssue) {
				this.links.insertEmbeddingCandidate(pair.fromNum(), pair.toNum(), "duplicate_candidate",
						pair.similarity());
				inserted++;
			}
			else if (isPrPr) {
				this.links.insertEmbeddingCandidate(pair.fromNum(), pair.toNum(), "competing_pr",
						pair.similarity());
				inserted++;
			}
			else {
				// PR↔Issue pair — LLM will decide; gather for batch
				prIssuePairs.add(pair);
			}
		}

		// 4. Classify PR↔Issue pairs with LLM.
		if (!prIssuePairs.isEmpty()) {
			logger.info("Classifying {} PR↔Issue pairs with LLM…", prIssuePairs.size());
			// Fetch titles + summaries for context
			List<Integer> allNums = prIssuePairs.stream()
				.flatMap(p -> java.util.stream.Stream.of(p.fromNum(), p.toNum()))
				.distinct()
				.toList();
			List<ItemDetail> details = fetchDetails(allNums);
			var detailMap = new java.util.HashMap<Integer, ItemDetail>();
			for (ItemDetail d : details) {
				detailMap.put(d.number(), d);
			}

			int llmOk = 0, llmFailed = 0;
			for (RawPair pair : prIssuePairs) {
				// Canonicalise: PR first, Issue second for the prompt
				int prNum = "pr".equals(pair.fromKind()) ? pair.fromNum() : pair.toNum();
				int issueNum = "issue".equals(pair.fromKind()) ? pair.fromNum() : pair.toNum();

				ItemDetail pr = detailMap.get(prNum);
				ItemDetail issue = detailMap.get(issueNum);
				if (pr == null || issue == null) {
					continue;
				}

				try {
					String relationshipType = classifyPrIssue(pr, issue);
					// Store canonically: PR as from_number, Issue as to_number
					this.links.insertEmbeddingCandidate(prNum, issueNum, relationshipType, pair.similarity());
					llmOk++;
					inserted++;
				}
				catch (Exception ex) {
					llmFailed++;
					logger.warn("LLM classification failed for PR#{} / Issue#{}: {}", prNum, issueNum,
							ex.getMessage());
					// Fall back to "related" so the pair is still surfaced for human review
					this.links.insertEmbeddingCandidate(prNum, issueNum, "related", pair.similarity());
					inserted++;
				}
			}
			logger.info("LLM classification: {} ok, {} failed", llmOk, llmFailed);
		}

		logger.info("Duplicate scan complete: {} candidates inserted", inserted);
		return inserted;
	}

	private String classifyPrIssue(ItemDetail pr, ItemDetail issue) {
		String user = """
				PR #%d: %s
				%s

				Issue #%d: %s
				%s
				""".formatted(pr.number(), pr.title(), pr.summary() != null ? pr.summary() : "",
				issue.number(), issue.title(), issue.summary() != null ? issue.summary() : "");

		String response = this.chat.prompt().system(SYSTEM).user(user).call().content();
		if (response != null && response.toLowerCase().contains("pr_fixes_issue")) {
			return "pr_fixes_issue";
		}
		return "related";
	}

	private List<RawPair> fetchRawPairs(double threshold) {
		try {
			return this.jdbc.query("""
					select
					    (a.metadata->>'number')::int as from_num,
					    a.metadata->>'kind'          as from_kind,
					    (b.to_num)::int              as to_num,
					    b.to_kind,
					    b.sim                        as similarity
					from vector_store a
					cross join lateral (
					    select
					        (metadata->>'number')    as to_num,
					        metadata->>'kind'        as to_kind,
					        1 - (embedding <=> a.embedding) as sim
					    from vector_store
					    where id != a.id
					    order by embedding <=> a.embedding
					    limit 6
					) b
					where b.sim > ?
					  and (a.metadata->>'number')::int < (b.to_num)::int
					""", (rs, n) -> new RawPair(rs.getInt("from_num"), rs.getString("from_kind"),
					rs.getInt("to_num"), rs.getString("to_kind"), rs.getDouble("similarity")),
					threshold);
		}
		catch (Exception ex) {
			logger.warn("Could not query vector_store for duplicate candidates: {}", ex.getMessage());
			return List.of();
		}
	}

	private List<ItemDetail> fetchDetails(List<Integer> numbers) {
		if (numbers.isEmpty()) {
			return List.of();
		}
		String placeholders = numbers.stream().map(n -> "?").collect(java.util.stream.Collectors.joining(","));
		return this.jdbc.query(
				"select i.number, i.title, c.summary from gh_item i "
						+ "left join classification c on c.item_number = i.number "
						+ "where i.number in (" + placeholders + ")",
				(rs, n) -> new ItemDetail(rs.getInt("number"), rs.getString("title"), rs.getString("summary")),
				numbers.toArray());
	}

	private record RawPair(int fromNum, String fromKind, int toNum, String toKind, double similarity) {
	}

	private record ItemDetail(int number, String title, String summary) {
	}

}
