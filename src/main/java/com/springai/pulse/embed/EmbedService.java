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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.springai.pulse.persistence.GhItemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Embeds every gh_item's title + body into the pgvector store using the local ONNX
 * all-MiniLM-L6-v2 model. Idempotent: items already in the vector store are skipped.
 *
 * <p>Document IDs are deterministic UUIDs derived from the item number so that repeated
 * calls trigger an ON CONFLICT update rather than a duplicate insert.
 */
@Service
public class EmbedService {

	private static final Logger logger = LoggerFactory.getLogger(EmbedService.class);

	private static final int MAX_BODY_CHARS = 2000;

	private static final int BATCH_SIZE = 50;

	private final VectorStore vectorStore;

	private final GhItemRepository items;

	private final JdbcTemplate jdbc;

	public EmbedService(VectorStore vectorStore, GhItemRepository items, JdbcTemplate jdbc) {
		this.vectorStore = vectorStore;
		this.items = items;
		this.jdbc = jdbc;
	}

	/**
	 * Embed all items not yet in the vector store.
	 * @return number of items newly embedded
	 */
	public int embedAll() {
		Set<Integer> already = alreadyEmbeddedNumbers();
		logger.info("Embed: {} already in vector store", already.size());

		List<GhItemRepository.ItemEmbed> pending = this.items.findAllForEmbedding()
			.stream()
			.filter(i -> !already.contains(i.number()))
			.collect(Collectors.toList());

		if (pending.isEmpty()) {
			logger.info("Embed: nothing new to embed");
			return 0;
		}
		logger.info("Embedding {} item(s)…", pending.size());

		List<Document> docs = pending.stream().map(item -> {
			String body = item.body() != null ? item.body() : "";
			if (body.length() > MAX_BODY_CHARS) {
				body = body.substring(0, MAX_BODY_CHARS);
			}
			String text = item.title() + "\n\n" + body;
			UUID id = UUID.nameUUIDFromBytes(("gh_item:" + item.number()).getBytes(StandardCharsets.UTF_8));
			return Document.builder()
				.id(id.toString())
				.text(text)
				.metadata(Map.of("number", String.valueOf(item.number()), "kind", item.kind()))
				.build();
		}).collect(Collectors.toList());

		for (int i = 0; i < docs.size(); i += BATCH_SIZE) {
			List<Document> batch = docs.subList(i, Math.min(i + BATCH_SIZE, docs.size()));
			this.vectorStore.add(batch);
			if ((i + BATCH_SIZE) % 500 == 0 || (i + BATCH_SIZE) >= docs.size()) {
				logger.info("  …embedded {}/{}", Math.min(i + BATCH_SIZE, docs.size()), docs.size());
			}
		}
		return docs.size();
	}

	private Set<Integer> alreadyEmbeddedNumbers() {
		try {
			List<Integer> rows = this.jdbc.query(
					"select (metadata->>'number')::int from vector_store where metadata->>'number' is not null",
					(rs, n) -> rs.getInt(1));
			return new HashSet<>(rows);
		}
		catch (Exception ex) {
			// vector_store table may not exist yet on first startup
			logger.debug("Could not query vector_store (not yet created?): {}", ex.getMessage());
			return new HashSet<>();
		}
	}

	/** Returns the number of embedded items — for the admin status display. */
	public long embeddedCount() {
		try {
			Long n = this.jdbc.queryForObject("select count(*) from vector_store", Long.class);
			return n != null ? n : 0;
		}
		catch (Exception ex) {
			return 0;
		}
	}

	/** Deterministic UUID for a gh_item number — same formula used by embedAll(). */
	public static UUID docId(int number) {
		return UUID.nameUUIDFromBytes(("gh_item:" + number).getBytes(StandardCharsets.UTF_8));
	}

}
