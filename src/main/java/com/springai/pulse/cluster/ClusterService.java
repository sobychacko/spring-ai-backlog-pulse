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

package com.springai.pulse.cluster;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.RowCallbackHandler;

import com.springai.pulse.cluster.ClusterRepository.NewCluster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Builds theme clusters from the pgvector similarity graph.
 *
 * <p>Algorithm:
 * <ol>
 *   <li>Run a lateral-join SQL query to find all pairs with similarity above a threshold — this
 *       gives the edges of the similarity graph.
 *   <li>Apply Union-Find (connected components) to the edges.
 *   <li>Drop singletons and very small clusters (size &lt; MIN_CLUSTER_SIZE).
 *   <li>For each surviving cluster, call Haiku once to produce a short descriptive label.
 *   <li>Write all clusters to {@code theme_cluster} + {@code item_cluster} (full rebuild).
 * </ol>
 *
 * <p>The LLM only produces cluster <em>names</em>; membership and size come entirely from
 * the embedding geometry.
 */
@Service
public class ClusterService {

	private static final Logger logger = LoggerFactory.getLogger(ClusterService.class);

	private static final double CLUSTER_THRESHOLD = 0.68;

	// Clusters larger than this are recursively re-clustered at progressively
	// higher thresholds until they break into coherent sub-themes. Dense regions
	// of the embedding space (e.g. tool-calling / structured-output / chat-memory)
	// otherwise chain into one giant cluster that no view can present usefully.
	private static final int MAX_CLUSTER_SIZE = 40;

	private static final double SPLIT_THRESHOLD_STEP = 0.04;

	private static final double SPLIT_THRESHOLD_CAP = 0.88;

	// DBSCAN: minimum neighbours above threshold for a point to be a "core point"
	// that can expand a cluster. Prevents chaining through weakly-connected items.
	private static final int DBSCAN_MIN_PTS = 3;

	// How many nearest neighbours to fetch per item from vector_store.
	// More = better DBSCAN recall at the cost of a slower lateral join.
	private static final int NEIGHBOR_LIMIT = 20;

	private static final int MIN_CLUSTER_SIZE = 3;

	private static final int TITLES_PER_CLUSTER = 10;

	private final JdbcTemplate jdbc;

	private final ClusterRepository clusters;

	private final ChatClient chat;

	public ClusterService(JdbcTemplate jdbc, ClusterRepository clusters, ChatModel chatModel) {
		this.jdbc = jdbc;
		this.clusters = clusters;
		this.chat = ChatClient.create(chatModel);
	}

	/**
	 * Rebuild the cluster tables from the current vector store contents.
	 * @return number of clusters written
	 */
	public synchronized int buildClusters() {
		// 1. Fetch all embedded item numbers
		List<Integer> allItems = fetchAllItemNumbers();
		if (allItems.isEmpty()) {
			logger.warn("Cluster: no items in vector store — run embed first");
			return 0;
		}
		logger.info("Clustering {} embedded items at threshold {}", allItems.size(), CLUSTER_THRESHOLD);

		// 2. Fetch similarity edges (k-nearest neighbours per item, undirected)
		List<Edge> edges = fetchEdges(CLUSTER_THRESHOLD, NEIGHBOR_LIMIT);
		logger.info("Cluster: {} edges in similarity graph", edges.size());

		// 3. DBSCAN — avoids the chaining problem of Union-Find by only letting
		//    core points (>= DBSCAN_MIN_PTS neighbours) expand clusters.
		//    Oversized clusters are then recursively split at higher thresholds.
		List<List<Integer>> refined = new ArrayList<>();
		for (List<Integer> cluster : dbscan(allItems, filterEdges(edges, CLUSTER_THRESHOLD, null))) {
			refined.addAll(splitOversized(cluster, edges, CLUSTER_THRESHOLD));
		}

		List<List<Integer>> significant = refined.stream()
			.filter(c -> c.size() >= MIN_CLUSTER_SIZE)
			.sorted((a, b) -> b.size() - a.size())
			.collect(Collectors.toList());

		logger.info("Cluster: {} clusters with size >= {}", significant.size(), MIN_CLUSTER_SIZE);

		// 6. Fetch titles for naming
		Map<Integer, String> titles = fetchTitles();

		// 7. Name each cluster with LLM
		List<NewCluster> namedClusters = new ArrayList<>();
		int ok = 0, failed = 0;
		for (List<Integer> members : significant) {
			String label = nameCluster(members, titles);
			namedClusters.add(new NewCluster(label, members));
			if (label.startsWith("Cluster #")) {
				failed++;
			}
			else {
				ok++;
			}
		}
		logger.info("Cluster naming: {} ok, {} fallback", ok, failed);

		// 8. Persist
		this.clusters.replaceAll(namedClusters);
		logger.info("Cluster: wrote {} clusters", namedClusters.size());
		return namedClusters.size();
	}

	/**
	 * Recursively break an oversized cluster into sub-themes by re-running DBSCAN
	 * over its members at a higher similarity threshold. Stops when the cluster is
	 * small enough, the threshold cap is reached, or the cluster refuses to split
	 * (kept intact rather than dissolved into noise).
	 */
	private List<List<Integer>> splitOversized(List<Integer> members, List<Edge> edges, double threshold) {
		double next = threshold + SPLIT_THRESHOLD_STEP;
		if (members.size() <= MAX_CLUSTER_SIZE || next > SPLIT_THRESHOLD_CAP) {
			return List.of(members);
		}
		Set<Integer> memberSet = new HashSet<>(members);
		List<List<Integer>> pieces = dbscan(members, filterEdges(edges, next, memberSet));
		if (pieces.isEmpty()) {
			logger.info("Cluster split: {} items dissolved at threshold {} — keeping intact", members.size(), next);
			return List.of(members);
		}
		if (pieces.size() == 1 && pieces.get(0).size() == members.size()) {
			// No progress at this threshold — try the next one up
			return splitOversized(members, edges, next);
		}
		logger.info("Cluster split: {} items -> {} pieces at threshold {}", members.size(), pieces.size(), next);
		List<List<Integer>> result = new ArrayList<>();
		for (List<Integer> piece : pieces) {
			result.addAll(splitOversized(piece, edges, next));
		}
		return result;
	}

	private static List<int[]> filterEdges(List<Edge> edges, double threshold, Set<Integer> within) {
		List<int[]> out = new ArrayList<>();
		for (Edge e : edges) {
			if (e.sim() > threshold && (within == null || (within.contains(e.from()) && within.contains(e.to())))) {
				out.add(new int[] { e.from(), e.to() });
			}
		}
		return out;
	}

	record Edge(int from, int to, double sim) {
	}

	private String nameCluster(List<Integer> members, Map<Integer, String> titles) {
		List<String> sample = members.stream()
			.limit(TITLES_PER_CLUSTER)
			.map(n -> titles.getOrDefault(n, "#" + n))
			.toList();
		String prompt = "Name this cluster of GitHub issues/PRs from the Spring AI project with a short "
				+ "(5–10 word) descriptive label capturing what they share in common.\n\n"
				+ "Items:\n" + sample.stream().map(t -> "- " + t).collect(Collectors.joining("\n"))
				+ "\n\nRespond with only the cluster name.";
		try {
			String name = this.chat.prompt().user(prompt).call().content();
			return name != null ? name.trim().replaceAll("\"", "") : fallbackLabel(members);
		}
		catch (Exception ex) {
			logger.warn("Cluster naming failed: {}", ex.getMessage());
			return fallbackLabel(members);
		}
	}

	private static String fallbackLabel(List<Integer> members) {
		return "Cluster #" + members.get(0);
	}

	private List<Integer> fetchAllItemNumbers() {
		try {
			return this.jdbc.query(
					"select (metadata->>'number')::int from vector_store where metadata->>'number' is not null",
					(rs, n) -> rs.getInt(1));
		}
		catch (Exception ex) {
			logger.warn("Could not read vector_store: {}", ex.getMessage());
			return List.of();
		}
	}

	/**
	 * DBSCAN over the pre-fetched k-NN adjacency list.
	 * <ul>
	 *   <li>Core point: has &ge; DBSCAN_MIN_PTS neighbours above threshold → can expand a cluster.</li>
	 *   <li>Border point: fewer neighbours but reachable from a core point → joins but does not expand.</li>
	 *   <li>Noise: unreachable → handled by the catch-all area buckets upstream.</li>
	 * </ul>
	 */
	private List<List<Integer>> dbscan(List<Integer> allItems, List<int[]> edges) {
		// Build undirected adjacency list
		Map<Integer, Set<Integer>> adj = new HashMap<>();
		for (int num : allItems) {
			adj.put(num, new HashSet<>());
		}
		for (int[] e : edges) {
			adj.computeIfAbsent(e[0], k -> new HashSet<>()).add(e[1]);
			adj.computeIfAbsent(e[1], k -> new HashSet<>()).add(e[0]);
		}

		// -1 = noise, 0 = unvisited, >0 = cluster id
		Map<Integer, Integer> label = new HashMap<>();
		int nextCluster = 0;

		for (int p : allItems) {
			if (label.containsKey(p)) {
				continue;
			}
			Set<Integer> pNeighbors = adj.getOrDefault(p, Set.of());
			if (pNeighbors.size() < DBSCAN_MIN_PTS) {
				label.put(p, -1); // noise — may be promoted later by a core neighbour
				continue;
			}
			// p is a core point — start a new cluster
			nextCluster++;
			label.put(p, nextCluster);
			Deque<Integer> seeds = new ArrayDeque<>(pNeighbors);
			while (!seeds.isEmpty()) {
				int q = seeds.poll();
				int qLabel = label.getOrDefault(q, Integer.MIN_VALUE);
				if (qLabel == -1) {
					label.put(q, nextCluster); // noise promoted to border point
				}
				if (qLabel != Integer.MIN_VALUE) {
					continue; // already processed
				}
				label.put(q, nextCluster);
				Set<Integer> qNeighbors = adj.getOrDefault(q, Set.of());
				if (qNeighbors.size() >= DBSCAN_MIN_PTS) {
					seeds.addAll(qNeighbors); // q is also a core point — expand
				}
			}
		}

		// Collect surviving clusters
		Map<Integer, List<Integer>> clusters = new HashMap<>();
		for (Map.Entry<Integer, Integer> e : label.entrySet()) {
			if (e.getValue() > 0) {
				clusters.computeIfAbsent(e.getValue(), k -> new ArrayList<>()).add(e.getKey());
			}
		}
		return new ArrayList<>(clusters.values());
	}

	private List<Edge> fetchEdges(double threshold, int neighborLimit) {
		try {
			return this.jdbc.query("""
					select
					    (a.metadata->>'number')::int as from_num,
					    (b.to_num)::int              as to_num,
					    b.sim                        as sim
					from vector_store a
					cross join lateral (
					    select
					        (metadata->>'number')           as to_num,
					        1 - (embedding <=> a.embedding) as sim
					    from vector_store
					    where id != a.id
					    order by embedding <=> a.embedding
					    limit ?
					) b
					where b.sim > ?
					  and (a.metadata->>'number')::int < (b.to_num)::int
					""", (rs, n) -> new Edge(rs.getInt("from_num"), rs.getInt("to_num"), rs.getDouble("sim")),
					neighborLimit, threshold);
		}
		catch (Exception ex) {
			logger.warn("Could not fetch similarity edges: {}", ex.getMessage());
			return List.of();
		}
	}

	private Map<Integer, String> fetchTitles() {
		Map<Integer, String> map = new HashMap<>();
		this.jdbc.query("select number, title from gh_item",
				(RowCallbackHandler) rs -> map.put(rs.getInt("number"), rs.getString("title")));
		return map;
	}

}
