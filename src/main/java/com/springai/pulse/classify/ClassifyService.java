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

package com.springai.pulse.classify;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.springai.pulse.config.PulseProperties;
import com.springai.pulse.domain.IssueClassification;
import com.springai.pulse.domain.ItemType;
import com.springai.pulse.domain.MainBranchApplicable;
import com.springai.pulse.domain.ReviewComplexity;
import com.springai.pulse.domain.Severity;
import com.springai.pulse.persistence.ClassificationRepository;
import com.springai.pulse.persistence.GhItemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Classifies items with the LLM into a structured {@link IssueClassification}. The model only
 * interprets the item's existing text; it produces no number used in any ranking. Idempotent via
 * {@code content_hash} — an unchanged item is never re-sent to the model.
 */
@Service
public class ClassifyService {

	private static final Logger logger = LoggerFactory.getLogger(ClassifyService.class);

	private static final String SYSTEM = """
			You triage GitHub issues and pull requests for the Spring AI project. Classify each item
			STRICTLY from the provided title and body. Do not invent facts. If something is not stated,
			leave providers/vectorStores empty and pick the closest area.

			`type` must be exactly one of: BUG, ENHANCEMENT, QUESTION, DOCUMENTATION, TASK.
			IMPROVEMENT and NEW_FEATURE are values of `enhancementKind` only — never of `type`;
			an improvement to an existing feature is type=ENHANCEMENT with enhancementKind=IMPROVEMENT.

			`area` must be exactly one of (lower-kebab-case):
			core, chat-client, chat-model, embedding, vector-store, rag, tool-calling, mcp,
			observability, chat-memory, structured-output, agents, advisors, prompt, image, audio,
			moderation, auto-config, docs, testing, build, kotlin, other.

			`providers` (only those explicitly mentioned): openai, anthropic, azure-openai, bedrock,
			vertex, google-genai, ollama, mistral, groq, deepseek, huggingface, minimax, moonshot,
			zhipu, oci-genai.

			`vectorStores` (only those explicitly mentioned): pgvector, redis, milvus, qdrant, chroma,
			weaviate, elasticsearch, opensearch, pinecone, mongodb, neo4j, cassandra, azure, oracle,
			typesense, couchbase, gemfire.

			The summary must be a faithful one-sentence restatement using only the item's own content.

			For PULL REQUESTS only:
			- `reviewComplexity`: LOW (narrow change, clear description, tests mentioned, easy to verify),
			  MEDIUM (moderate scope or partially described), HIGH (broad refactor, architectural change,
			  or unclear scope). Use NOT_APPLICABLE for issues.
			- `reviewNotes`: one sentence explaining the complexity verdict. Empty string for issues.

			For pull requests targeting OLD/INACTIVE branches (1.x, 0.x) only:
			- Active branches are: main, 2.x (e.g. 2.0.x, 2.1.x). Old/inactive: 1.0.x, 1.1.x, 0.x.
			- `mainBranchApplicable`: YES (this bug/fix almost certainly exists in main too), NO (change
			  is specific to the old 1.x API and does not apply), UNCLEAR (cannot tell from description).
			  Use NOT_APPLICABLE for issues or PRs targeting main/2.x.
			- `mainBranchNote`: one sentence explaining the verdict. Empty string otherwise.
			""";

	private final ChatClient chat;

	private final GhItemRepository items;

	private final ClassificationRepository classifications;

	private final PulseProperties props;

	private final String model;

	public ClassifyService(ChatModel chatModel, GhItemRepository items, ClassificationRepository classifications,
			PulseProperties props,
			@Value("${spring.ai.anthropic.chat.options.model:claude-haiku-4-5}") String model) {
		this.chat = ChatClient.create(chatModel);
		this.items = items;
		this.classifications = classifications;
		this.props = props;
		this.model = model;
	}

	/**
	 * Classify every item whose content changed or was never classified.
	 * @return the number successfully classified
	 */
	public int classifyPending() {
		return classifyPending(0);
	}

	/**
	 * Classify pending items, capped at {@code limit} (0 = no cap). The cap lets us run a cheap
	 * smoke test before committing to the full backfill spend.
	 * @return the number successfully classified
	 */
	public int classifyPending(int limit) {
		return classifyPendingForModel(this.model, limit, false);
	}

	/**
	 * Classify pending items with the second-opinion Sonnet model, capped at {@code limit}
	 * (0 = no cap). Items are taken in random order so a capped run is an unbiased sample for
	 * the model-comparison view rather than the oldest-N items.
	 * @return the number successfully classified
	 */
	public int classifyPendingSonnet(int limit) {
		return classifyPendingForModel(this.props.classify().sonnetModel(), limit, true);
	}

	private int classifyPendingForModel(String modelId, int limit, boolean randomOrder) {
		List<GhItemRepository.ItemText> all = new ArrayList<>(this.items.findNeedingClassification(modelId));
		if (randomOrder) {
			Collections.shuffle(all);
		}
		final List<GhItemRepository.ItemText> pending = (limit > 0 && all.size() > limit) ? all.subList(0, limit) : all;
		if (pending.isEmpty()) {
			logger.info("Classification ({}): nothing to do (all items current)", modelId);
			return 0;
		}
		int concurrency = Math.max(1, this.props.classify().concurrency());
		logger.info("Classifying {} item(s) with {} @ concurrency {}", pending.size(), modelId, concurrency);
		ExecutorService pool = Executors.newFixedThreadPool(concurrency);
		AtomicInteger ok = new AtomicInteger();
		AtomicInteger failed = new AtomicInteger();
		AtomicLong inTokens = new AtomicLong();
		AtomicLong outTokens = new AtomicLong();
		try {
			List<Future<?>> futures = new ArrayList<>(pending.size());
			for (GhItemRepository.ItemText item : pending) {
				futures.add(pool
					.submit(() -> classifyOne(item, modelId, ok, failed, inTokens, outTokens, pending.size())));
			}
			for (Future<?> f : futures) {
				try {
					f.get();
				}
				catch (Exception ex) {
					// per-item failures are already counted in classifyOne
				}
			}
		}
		finally {
			pool.shutdown();
		}
		double cost = costUsd(modelId, inTokens.get(), outTokens.get());
		logger.info("Classification complete: {} ok, {} failed of {}; tokens in={} out={}; est cost ${} ({})",
				ok.get(), failed.get(), pending.size(), inTokens.get(), outTokens.get(),
				String.format("%.4f", cost), modelId);
		return ok.get();
	}

	private void classifyOne(GhItemRepository.ItemText item, String modelId, AtomicInteger ok, AtomicInteger failed,
			AtomicLong inTokens, AtomicLong outTokens, int total) {
		try {
			String body = item.body() != null ? item.body() : "";
			int max = this.props.classify().maxBodyChars();
			if (body.length() > max) {
				body = body.substring(0, max);
			}
			boolean isPr = "pr".equalsIgnoreCase(item.kind());
			StringBuilder user = new StringBuilder();
			user.append("Kind: ").append(isPr ? "PR" : "Issue").append("\n");
			if (isPr && item.prBaseBranch() != null) {
				user.append("Base branch: ").append(item.prBaseBranch()).append("\n");
			}
			user.append("Title: ").append(item.title()).append("\n\nBody:\n").append(body);
			var prompt = this.chat.prompt().system(SYSTEM).user(user.toString());
			if (!modelId.equals(this.model)) {
				// Spring AI 2.0 ChatClient takes the options *builder* per-call
				prompt = prompt.options(AnthropicChatOptions.builder().model(modelId));
			}
			var response = prompt.call().responseEntity(IssueClassification.class);
			IssueClassification result = response.getEntity();
			accumulateUsage(response.getResponse(), inTokens, outTokens);
			if (result == null) {
				failed.incrementAndGet();
				return;
			}
			this.classifications.upsert(item.number(), sanitize(result, item), modelId, item.contentHash());
			int done = ok.incrementAndGet() + failed.get();
			if (done % 50 == 0) {
				logger.info("  ...classified {}/{}", done, total);
			}
		}
		catch (Exception ex) {
			failed.incrementAndGet();
			logger.warn("Classify failed for #{}: {}", item.number(), ex.getMessage());
		}
	}

	/** The area taxonomy from the prompt. {@code area} is a free string in the schema, so the
	 * model can emit anything — including, via prompt injection from issue bodies, markup that
	 * downstream HTML sinks must never see. Everything outside the taxonomy becomes 'other'. */
	private static final java.util.Set<String> AREAS = java.util.Set.of("core", "chat-client", "chat-model",
			"embedding", "vector-store", "rag", "tool-calling", "mcp", "observability", "chat-memory",
			"structured-output", "agents", "advisors", "prompt", "image", "audio", "moderation", "auto-config",
			"docs", "testing", "build", "kotlin", "other", "unknown");

	/** Branches where a PR's main-branch-applicability question is moot (it IS main/2.x). */
	private static final java.util.regex.Pattern ACTIVE_BRANCH = java.util.regex.Pattern.compile("main|2\\..*");

	/** LOW review complexity requires a clear description; a body shorter than this has none. */
	private static final int MIN_BODY_FOR_LOW_COMPLEXITY = 80;

	/**
	 * Deterministic post-processing of model output — every rule here is a rubric clause the
	 * models were told but do not reliably honor (each found via adjudicated Haiku-vs-Sonnet
	 * comparison, and the body-length one is a blind spot BOTH models share):
	 * <ul>
	 * <li>Severity: enhancements and documentation gaps are never higher than LOW.</li>
	 * <li>Area: normalized to the taxonomy whitelist, anything else → 'other'.</li>
	 * <li>PR review complexity: never LOW when the body is blank/near-blank — LOW requires a
	 * clear description, and "no information" is not "simple".</li>
	 * <li>Main-branch applicability: forced NOT_APPLICABLE for PRs already targeting
	 * main/2.x.</li>
	 * </ul>
	 */
	private static IssueClassification sanitize(IssueClassification c, GhItemRepository.ItemText item) {
		String area = c.area() == null ? "unknown" : c.area().trim().toLowerCase();
		if (!AREAS.contains(area)) {
			area = "other";
		}
		Severity severity = c.severity();
		boolean capped = c.type() == ItemType.ENHANCEMENT || c.type() == ItemType.DOCUMENTATION;
		if (capped && severity != null && severity != Severity.LOW) {
			severity = Severity.LOW;
		}
		ReviewComplexity rc = c.reviewComplexity();
		MainBranchApplicable mba = c.mainBranchApplicable();
		String mbaNote = c.mainBranchNote();
		if ("pr".equalsIgnoreCase(item.kind())) {
			String body = item.body() == null ? "" : item.body().trim();
			if (rc == ReviewComplexity.LOW && body.length() < MIN_BODY_FOR_LOW_COMPLEXITY) {
				rc = ReviewComplexity.MEDIUM;
			}
			String base = item.prBaseBranch();
			if (base != null && ACTIVE_BRANCH.matcher(base).matches()
					&& mba != null && mba != MainBranchApplicable.NOT_APPLICABLE) {
				mba = MainBranchApplicable.NOT_APPLICABLE;
				mbaNote = "";
			}
		}
		else {
			if (rc != null && rc != ReviewComplexity.NOT_APPLICABLE) {
				rc = ReviewComplexity.NOT_APPLICABLE;
			}
			if (mba != null && mba != MainBranchApplicable.NOT_APPLICABLE) {
				mba = MainBranchApplicable.NOT_APPLICABLE;
				mbaNote = "";
			}
		}
		return new IssueClassification(c.type(), c.enhancementKind(), area, c.providers(), c.vectorStores(),
				severity, c.goodFirstIssue(), c.summary(), rc, c.reviewNotes(), mba, mbaNote);
	}

	private static void accumulateUsage(org.springframework.ai.chat.model.ChatResponse response, AtomicLong inTokens,
			AtomicLong outTokens) {
		if (response == null || response.getMetadata() == null || response.getMetadata().getUsage() == null) {
			return;
		}
		var usage = response.getMetadata().getUsage();
		Integer prompt = usage.getPromptTokens();
		Integer completion = usage.getCompletionTokens();
		if (prompt != null) {
			inTokens.addAndGet(prompt);
		}
		if (completion != null) {
			outTokens.addAndGet(completion);
		}
	}

	/** Rough USD cost from token counts, by model. Rates are per million tokens (input, output). */
	private static double costUsd(String model, long inTokens, long outTokens) {
		double inRate = 1.0;
		double outRate = 5.0; // default: Haiku 4.5
		String m = model != null ? model.toLowerCase() : "";
		if (m.contains("sonnet")) {
			inRate = 3.0;
			outRate = 15.0;
		}
		else if (m.contains("opus")) {
			inRate = 5.0;
			outRate = 25.0;
		}
		return (inTokens * inRate + outTokens * outRate) / 1_000_000.0;
	}

}
