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

package com.springai.pulse.ingest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;

import com.springai.pulse.config.PulseProperties;
import com.springai.pulse.domain.GhItem;
import com.springai.pulse.domain.ItemKind;
import com.springai.pulse.persistence.GhItemRepository;
import com.springai.pulse.persistence.ItemLinkRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Service;

/**
 * Pulls open issues and PRs from GitHub into {@code gh_item} and parses GH-native links
 * ({@code Closes #N}, cross-references) into {@code item_link}. This layer records only facts —
 * no AI is involved.
 */
@Service
public class IngestService {

	private static final Logger logger = LoggerFactory.getLogger(IngestService.class);

	// close/closes/closed, fix/fixes/fixed, resolve/resolves/resolved  +  #<number>
	private static final Pattern CLOSING = Pattern
		.compile("(?i)\\b(?:clos(?:e|es|ed)|fix(?:|es|ed)|resolv(?:e|es|ed))\\s+#(\\d+)");

	// a bare issue/PR reference like "#123", not preceded by a word char or another '#'
	private static final Pattern REFERENCE = Pattern.compile("(?<![\\w#])#(\\d+)");

	private final GitHubClient client;

	private final GhItemRepository items;

	private final ItemLinkRepository links;

	private final PulseProperties props;

	public IngestService(GitHubClient client, GhItemRepository items, ItemLinkRepository links,
			PulseProperties props) {
		this.client = client;
		this.items = items;
		this.links = links;
		this.props = props;
	}

	/**
	 * Full backfill of every open issue and PR. Idempotent — safe to re-run.
	 * @return the number of items ingested
	 */
	public int ingestAll() {
		List<JsonNode> nodes = this.client.fetchOpenItems(this.props.ingest().pageSize());
		int issues = 0;
		int prs = 0;
		for (JsonNode node : nodes) {
			GhItem item = map(node);
			this.items.upsert(item);
			if (item.kind() == ItemKind.PR) {
				prs++;
				if (item.body() != null) {
					parseLinks(item.number(), item.body());
				}
			}
			else {
				issues++;
			}
		}
		logger.info("Ingest complete: {} items ({} issues, {} PRs), {} GH-native links", nodes.size(), issues, prs,
				this.links.count());
		// the /issues listing has no base.ref — fetch branches from /pulls (~5 requests)
		ingestPrBaseBranches();
		return nodes.size();
	}

	/**
	 * Incremental ingest of items updated at or after {@code cursor} (ISO-8601 string). Uses
	 * {@code state=all} so newly-closed items are picked up too. Returns the count and the latest
	 * {@code updated_at} seen — the caller advances the sync cursor to that value.
	 */
	public IngestResult ingestSince(String cursor) {
		List<JsonNode> nodes = this.client.fetchItemsSince(cursor, this.props.ingest().pageSize());
		if (nodes.isEmpty()) {
			logger.info("Incremental ingest since {}: nothing new", cursor);
			return new IngestResult(0, cursor);
		}
		String latestUpdatedAt = cursor;
		boolean sawPr = false;
		for (JsonNode node : nodes) {
			GhItem item = map(node);
			this.items.upsert(item);
			if (item.kind() == ItemKind.PR) {
				sawPr = true;
				if (item.body() != null) {
					parseLinks(item.number(), item.body());
				}
			}
			String updatedAt = text(node, "updated_at");
			if (updatedAt != null && (latestUpdatedAt == null || updatedAt.compareTo(latestUpdatedAt) > 0)) {
				latestUpdatedAt = updatedAt;
			}
		}
		if (sawPr) {
			ingestPrBaseBranches();
		}
		logger.info("Incremental ingest since {}: {} item(s), latest updated_at={}", cursor, nodes.size(), latestUpdatedAt);
		return new IngestResult(nodes.size(), latestUpdatedAt);
	}

	/** Result of an incremental ingest — item count and new cursor value (latest updated_at seen). */
	public record IngestResult(int count, String latestUpdatedAt) {
	}

	private GhItem map(JsonNode n) {
		int number = n.get("number").asInt();
		boolean isPr = n.hasNonNull("pull_request") || n.hasNonNull("base");
		String title = text(n, "title");
		String body = text(n, "body");
		JsonNode user = n.get("user");
		String author = (user != null && !user.isNull()) ? text(user, "login") : null;
		List<String> labels = new ArrayList<>();
		JsonNode labelsNode = n.get("labels");
		if (labelsNode != null && labelsNode.isArray()) {
			labelsNode.forEach(l -> {
				String name = text(l, "name");
				if (name != null) {
					labels.add(name);
				}
			});
		}
		// base.ref is present when called from the /pulls endpoint
		String prBaseBranch = null;
		if (isPr) {
			JsonNode base = n.get("base");
			if (base != null && !base.isNull()) {
				prBaseBranch = text(base, "ref");
			}
		}
		List<String> assignees = new ArrayList<>();
		JsonNode assigneesNode = n.get("assignees");
		if (assigneesNode != null && assigneesNode.isArray()) {
			assigneesNode.forEach(a -> {
				String login = text(a, "login");
				if (login != null) {
					assignees.add(login);
				}
			});
		}
		return new GhItem(number, isPr ? ItemKind.PR : ItemKind.ISSUE, title, body, text(n, "state"), author,
				text(n, "author_association"), parseTime(text(n, "created_at")), parseTime(text(n, "updated_at")),
				parseTime(text(n, "closed_at")), n.path("comments").asInt(0),
				n.path("reactions").path("total_count").asInt(0), labels, text(n, "html_url"),
				isPr ? n.path("draft").asBoolean(false) : null, null, contentHash(title, body), prBaseBranch,
				assignees);
	}

	/**
	 * Fetch the base branch ({@code base.ref}) for every open PR from the {@code /pulls} endpoint
	 * and update {@code pr_base_branch} in {@code gh_item}. Safe to call repeatedly — uses
	 * upsert logic that preserves an existing value when the new one is null.
	 * @return number of PRs updated
	 */
	public int ingestPrBaseBranches() {
		List<JsonNode> prs = this.client.fetchOpenPullRequests(this.props.ingest().pageSize());
		int updated = 0;
		for (JsonNode pr : prs) {
			int number = pr.get("number").asInt();
			JsonNode base = pr.get("base");
			if (base != null && !base.isNull()) {
				String ref = text(base, "ref");
				if (ref != null) {
					this.items.updatePrBaseBranch(number, ref);
					updated++;
				}
			}
		}
		logger.info("PR base branches updated: {}/{} PRs", updated, prs.size());
		return updated;
	}

	private void parseLinks(int from, String body) {
		Set<Integer> closed = new HashSet<>();
		Matcher c = CLOSING.matcher(body);
		while (c.find()) {
			int to = Integer.parseInt(c.group(1));
			if (to != from) {
				this.links.insertGhReference(from, to, "closes");
				closed.add(to);
			}
		}
		Matcher r = REFERENCE.matcher(body);
		while (r.find()) {
			int to = Integer.parseInt(r.group(1));
			if (to != from && !closed.contains(to)) {
				this.links.insertGhReference(from, to, "references");
			}
		}
	}

	private static String contentHash(String title, String body) {
		return sha256((title != null ? title : "") + "\n\n" + (body != null ? body : ""));
	}

	private static String sha256(String value) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
			StringBuilder sb = new StringBuilder(64);
			for (byte b : digest) {
				sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
			}
			return sb.toString();
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 unavailable", ex);
		}
	}

	private static OffsetDateTime parseTime(String value) {
		return (value == null || value.isBlank()) ? null : OffsetDateTime.parse(value);
	}

	private static String text(JsonNode node, String field) {
		JsonNode v = node.get(field);
		return (v == null || v.isNull()) ? null : v.asText();
	}

}
