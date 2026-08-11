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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import com.springai.pulse.analytics.AnalyticsRepository;
import com.springai.pulse.chat.ChatToolsRepository.Card;
import com.springai.pulse.domain.ModelIds;
import com.springai.pulse.search.SemanticSearchService;

import org.springframework.ai.document.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

/**
 * The chat's tool surface — one instance per request. Each tool answers something only this
 * app can answer (semantic layer + SQL aggregation); all read-only, no GitHub write ability
 * exists here. Tools double as collectors: alongside the JSON the model sees, they stash
 * item cards and caveats the UI renders under the reply.
 *
 * <p>NOT a Spring bean: {@code ChatService} constructs one per turn so the collected cards
 * belong to exactly one request.
 */
public class ChatTools {

	/** Similarity floor for counting an item as part of a topic's matching set. */
	private static final double MEMBERSHIP_THRESHOLD = 0.45;

	/** Retrieval width for a topic's matching set. */
	private static final int MATCH_TOP_K = 200;

	private static final String AUTHORS_CAVEAT = "\"Unique people asking\" counts item authors; "
			+ "commenters and reactors are in the engagement totals but not identified.";

	private final VectorStore vectorStore;

	private final ChatToolsRepository repo;

	private final AnalyticsRepository analytics;

	private final SemanticSearchService semanticSearch;

	private final List<String> called = new ArrayList<>();

	private final Map<Integer, Card> cards = new LinkedHashMap<>();

	private final LinkedHashSet<String> caveats = new LinkedHashSet<>();

	public ChatTools(VectorStore vectorStore, ChatToolsRepository repo, AnalyticsRepository analytics,
			SemanticSearchService semanticSearch) {
		this.vectorStore = vectorStore;
		this.repo = repo;
		this.analytics = analytics;
		this.semanticSearch = semanticSearch;
	}

	@Tool(description = """
			The workhorse for theme questions ("is X a theme?", "how popular is X?", "who is asking
			for X?"). Semantically retrieves every open item matching the topic, then aggregates in
			SQL: item count, unique authors, engagement, date range, recent inflow, top items — plus
			the set's percentile standing against ALL discovered theme clusters, which is the
			deterministic basis for calling something popular. Report popularity from the percentile
			fields, never from your own impression.""")
	public ThemeReport themeReport(
			@ToolParam(description = "topic phrase, e.g. 'hybrid search' or 'streaming tool calls'") String topic) {
		this.called.add("themeReport");
		List<Integer> numbers = matchingSet(topic);
		var aggregate = this.repo.aggregate(numbers);
		var standing = this.repo.clusterStanding(aggregate.items(),
				aggregate.totalReactions() + aggregate.totalComments());
		List<Card> top = this.repo.topItems(numbers, 6);
		top.forEach(c -> this.cards.putIfAbsent(c.number(), c));
		this.caveats.add(AUTHORS_CAVEAT);
		return new ThemeReport(topic, aggregate, this.repo.topAuthors(numbers, 8), standing,
				popularityLabel(standing), top.stream().map(Card::number).toList(),
				"matching set = open items with embedding similarity ≥ " + MEMBERSHIP_THRESHOLD);
	}

	@Tool(description = """
			Weekly opened vs closed counts — answers "is X growing, or just closing slower?".
			Scope by EITHER area (an exact area tag like 'mcp', 'vector-store' — see pulse() for
			valid tags) OR topic (free text, semantic match), or neither for the whole backlog.
			Closed counts only cover closures observed since syncing began (dataBoundary field).""")
	public Trend trend(@ToolParam(required = false, description = "exact area tag") String area,
			@ToolParam(required = false, description = "free-text topic (semantic match)") String topic,
			@ToolParam(required = false, description = "window in weeks, default 12, max 52") Integer weeks) {
		this.called.add("trend");
		int window = Math.min(Math.max(weeks != null ? weeks : 12, 4), 52);
		List<Integer> numbers = (topic != null && !topic.isBlank()) ? matchingSet(topic) : null;
		String scope = numbers != null ? "topic: " + topic : (area != null && !area.isBlank())
				? "area: " + area : "whole backlog";
		List<ChatToolsRepository.TrendPoint> points = this.repo.trend(blankToNull(area), numbers, window);
		String boundary = "closures are only tracked since " + this.repo.closuresTrackedSince()
				+ "; earlier outflow is invisible, so opened-vs-closed comparisons before then are one-sided";
		this.caveats.add("Closure data starts " + this.repo.closuresTrackedSince()
				+ " (when this app began syncing) — earlier closes aren't counted.");
		return new Trend(scope, window, points, boundary);
	}

	@Tool(description = """
			Full dossier for one issue/PR by number: GitHub facts, AI-suggested classification,
			theme-cluster membership, legacy-review verdict, duplicate/related links, and the
			most similar other items — one item in context of the whole backlog.""")
	public ItemContext itemContext(@ToolParam(description = "the GitHub issue/PR number") int number) {
		this.called.add("itemContext");
		var dossier = this.repo.dossier(number);
		if (dossier == null) {
			return new ItemContext(null, List.of(), List.of());
		}
		List<SemanticSearchService.SemanticHit> hits = this.semanticSearch
			.search(dossier.title() + "\n" + dossier.bodyExcerpt(), null, null, null, null, 6);
		List<Neighbor> neighbors = hits.stream()
			.filter(h -> h.number() != number)
			.limit(5)
			.map(h -> new Neighbor(h.number(), h.title(), Math.round(h.similarity() * 100) / 100.0))
			.toList();
		this.repo.topItems(List.of(number), 1).forEach(c -> this.cards.putIfAbsent(c.number(), c));
		this.repo.topItems(neighbors.stream().map(Neighbor::number).toList(), 5)
			.forEach(c -> this.cards.putIfAbsent(c.number(), c));
		return new ItemContext(dossier, this.repo.links(number, 10), neighbors);
	}

	@Tool(description = """
			Thin semantic search: top open items matching a free-text query, by meaning. Use for
			"find items about X" when a full themeReport is overkill, or to locate an item number.""")
	public List<SearchHit> semanticSearch(@ToolParam(description = "free-text query") String query) {
		this.called.add("semanticSearch");
		List<SemanticSearchService.SemanticHit> hits = this.semanticSearch.search(query, null, null, null, null, 8);
		List<Integer> numbers = hits.stream().map(SemanticSearchService.SemanticHit::number).toList();
		this.repo.topItems(numbers, 8).forEach(c -> this.cards.putIfAbsent(c.number(), c));
		return hits.stream()
			.map(h -> new SearchHit(h.number(), h.kind(), h.title(), h.reactions() + h.comments(),
					Math.round(h.similarity() * 100) / 100.0))
			.toList();
	}

	@Tool(description = """
			The dashboard's pulse ranking: every area with volume, 30-day velocity, engagement and
			its 0-100 pulse score. Use for "what are the hottest areas" and to compare an area
			against the rest, and as the list of valid area tags for trend(area=...).""")
	public List<AnalyticsRepository.PulseEntry> pulse() {
		this.called.add("pulse");
		return this.analytics.pulseByArea(ModelIds.DEFAULT_CLASSIFIER);
	}

	/** Open-item numbers whose embedding similarity to the topic clears the membership floor. */
	private List<Integer> matchingSet(String topic) {
		List<Document> docs = this.vectorStore.similaritySearch(SearchRequest.builder()
			.query(topic)
			.topK(MATCH_TOP_K)
			.similarityThreshold(MEMBERSHIP_THRESHOLD)
			.build());
		if (docs == null) {
			return List.of();
		}
		LinkedHashSet<Integer> numbers = new LinkedHashSet<>();
		for (Document doc : docs) {
			Object number = doc.getMetadata().get("number");
			if (number != null) {
				numbers.add(Integer.parseInt(number.toString()));
			}
		}
		return List.copyOf(numbers);
	}

	private static String blankToNull(String value) {
		return (value == null || value.isBlank()) ? null : value;
	}

	List<String> toolsCalled() {
		return List.copyOf(this.called);
	}

	List<Card> collectedCards() {
		return List.copyOf(this.cards.values());
	}

	List<String> collectedCaveats() {
		return List.copyOf(this.caveats);
	}

	public record ThemeReport(String topic, ChatToolsRepository.ThemeAggregate aggregate,
			List<ChatToolsRepository.AuthorStat> topAuthors, ChatToolsRepository.ClusterStanding standing,
			String popularityLabel, List<Integer> topItemNumbers, String method) {
	}

	public record Trend(String scope, int weeks, List<ChatToolsRepository.TrendPoint> points, String dataBoundary) {
	}

	public record ItemContext(ChatToolsRepository.Dossier item, List<ChatToolsRepository.LinkedItem> links,
			List<Neighbor> similarItems) {
	}

	public record Neighbor(int number, String title, double similarity) {
	}

	public record SearchHit(int number, String kind, String title, int engagement, double similarity) {
	}

	/** Deterministic wording for the standing percentiles — the model repeats, never invents. */
	static String popularityLabel(ChatToolsRepository.ClusterStanding standing) {
		// no clusters built yet (fresh database, cluster step never run): the percentiles are
		// all zero, which would read as "niche" for every topic. Say the baseline is missing.
		if (standing.clusterCount() == 0) {
			return "popularity ranking unavailable — no theme clusters have been built for this "
					+ "backlog yet, so there is no distribution to compare against";
		}
		int best = Math.max(standing.sizePercentile(), standing.engagementPercentile());
		if (best >= 90) {
			return "top-decile theme (larger or more engaged than " + best + "% of discovered clusters)";
		}
		if (best >= 75) {
			return "clearly above typical (beats " + best + "% of discovered clusters)";
		}
		if (best >= 50) {
			return "above the median cluster";
		}
		return "below the median cluster — a niche topic in this backlog";
	}

}
