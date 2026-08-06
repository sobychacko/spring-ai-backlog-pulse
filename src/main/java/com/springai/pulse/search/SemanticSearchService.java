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

package com.springai.pulse.search;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import com.springai.pulse.domain.ModelIds;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Free-text search by meaning: the query string is embedded (local ONNX) and matched against
 * the stored item vectors via {@link VectorStore#similaritySearch} — the query-string-shaped
 * use of the vector store (dedup/clustering intentionally use set-based SQL over stored
 * vectors instead). Results are joined back to {@code gh_item}/{@code classification} for
 * display, so as everywhere, facts come from GitHub data and labels are AI-suggested.
 *
 * <p>The {@code kind} filter pushes down into the vector search via document metadata;
 * type/area/severity are applied post-retrieval by joining hits to {@code classification},
 * so we over-fetch and trim after filtering.
 */
@Service
public class SemanticSearchService {

	/** Fetch well beyond the display cap so post-retrieval facet filters don't starve results. */
	private static final int OVERFETCH_TOP_K = 100;

	/** Cosine-similarity floor — below this, all-mpnet matches are noise. */
	private static final double MIN_SIMILARITY = 0.25;

	/**
	 * Public, unauthenticated endpoint that burns CPU per query (ONNX embedding) — cap the
	 * input so oversized queries can't grind the tokenizer, and rate-limit globally so abuse
	 * caps out at a bounded compute cost. The model truncates around 384 tokens anyway, so
	 * nothing legitimate is lost.
	 */
	private static final int MAX_QUERY_CHARS = 2000;

	private static final int MAX_SEARCHES_PER_MINUTE = 120;

	private final java.util.concurrent.atomic.AtomicLong windowStart = new java.util.concurrent.atomic.AtomicLong();

	private final java.util.concurrent.atomic.AtomicInteger windowCount = new java.util.concurrent.atomic.AtomicInteger();

	private static final TypeReference<List<String>> LIST_OF_STRING = new TypeReference<>() {
	};

	private final VectorStore vectorStore;

	private final NamedParameterJdbcTemplate jdbc;

	private final ObjectMapper objectMapper;

	public SemanticSearchService(VectorStore vectorStore, NamedParameterJdbcTemplate jdbc,
			ObjectMapper objectMapper) {
		this.vectorStore = vectorStore;
		this.jdbc = jdbc;
		this.objectMapper = objectMapper;
	}

	public List<SemanticHit> search(String query, String kind, String type, String area, String severity, int limit) {
		if (!tryAcquire()) {
			throw new org.springframework.web.server.ResponseStatusException(
					org.springframework.http.HttpStatus.TOO_MANY_REQUESTS, "semantic search rate limit");
		}
		if (query.length() > MAX_QUERY_CHARS) {
			query = query.substring(0, MAX_QUERY_CHARS);
		}
		SearchRequest.Builder request = SearchRequest.builder()
			.query(query)
			.topK(OVERFETCH_TOP_K)
			.similarityThreshold(MIN_SIMILARITY);
		// whitelisted values only — the filter expression is a string we compose
		if ("issue".equals(kind) || "pr".equals(kind)) {
			request.filterExpression("kind == '" + kind + "'");
		}
		List<Document> docs = this.vectorStore.similaritySearch(request.build());
		if (docs == null || docs.isEmpty()) {
			return List.of();
		}
		Map<Integer, Double> similarityByNumber = new LinkedHashMap<>();
		for (Document doc : docs) {
			Object number = doc.getMetadata().get("number");
			if (number != null) {
				similarityByNumber.putIfAbsent(Integer.parseInt(number.toString()),
						doc.getScore() != null ? doc.getScore() : 0.0);
			}
		}
		if (similarityByNumber.isEmpty()) {
			return List.of();
		}
		var params = new MapSqlParameterSource()
			.addValue("numbers", similarityByNumber.keySet())
			.addValue("model", ModelIds.DEFAULT_CLASSIFIER)
			.addValue("type", blankToNull(type))
			.addValue("area", blankToNull(area))
			.addValue("severity", blankToNull(severity));
		List<SemanticHit> hits = this.jdbc.query("""
				select i.number, i.kind, i.title, i.url, i.reactions_total, i.comments_count,
						c.type, c.area, c.severity, c.summary, c.providers, c.good_first_issue
				from gh_item i
				left join classification c on c.item_number = i.number and c.model_used = :model
				where i.number in (:numbers)
					and i.state = 'open'
					and (:type::text is null or c.type = :type)
					and (:area::text is null or c.area = :area)
					and (:severity::text is null or c.severity = :severity)
				""", params,
				(rs, n) -> new SemanticHit(rs.getInt("number"), rs.getString("kind"), rs.getString("title"),
						rs.getString("url"), rs.getInt("reactions_total"), rs.getInt("comments_count"),
						rs.getString("type"), rs.getString("area"), rs.getString("severity"),
						rs.getString("summary"), parseList(rs.getString("providers")),
						(Boolean) rs.getObject("good_first_issue"),
						similarityByNumber.getOrDefault(rs.getInt("number"), 0.0)));
		return hits.stream()
			.sorted(Comparator.comparingDouble(SemanticHit::similarity).reversed())
			.limit(limit)
			.toList();
	}

	/** Global fixed-window limiter — crude on purpose: it protects the compute bill, and the
	 * worst failure mode (window exhausted by abuse) degrades search, not the dashboard. */
	private boolean tryAcquire() {
		long now = System.currentTimeMillis();
		long start = this.windowStart.get();
		if (now - start > 60_000 && this.windowStart.compareAndSet(start, now)) {
			this.windowCount.set(0);
		}
		return this.windowCount.incrementAndGet() <= MAX_SEARCHES_PER_MINUTE;
	}

	private static String blankToNull(String value) {
		return (value == null || value.isBlank()) ? null : value;
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

	/** One semantic match — {@code ItemView} shape plus the similarity score (0..1). */
	public record SemanticHit(int number, String kind, String title, String url, int reactions, int comments,
			String type, String area, String severity, String summary, List<String> providers,
			Boolean goodFirstIssue, double similarity) {
	}

}
