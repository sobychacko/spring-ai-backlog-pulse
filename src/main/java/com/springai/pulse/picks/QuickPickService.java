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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import tools.jackson.databind.JsonNode;
import com.springai.pulse.config.PulseProperties;
import com.springai.pulse.domain.ApiRisk;
import com.springai.pulse.domain.PickAssessment;
import com.springai.pulse.domain.PickBlocker;
import com.springai.pulse.domain.PickConfidence;
import com.springai.pulse.domain.PickEffort;
import com.springai.pulse.ingest.GitHubClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Service;

/**
 * "Today's picks": which high-value open issues could a maintainer land on main in about an
 * hour? The candidate pool is deterministic (see {@link QuickPickRepository}); this service sends
 * each candidate's title, body, and comment thread to the configured model and stores a
 * {@link PickAssessment}. A deterministic guard lowers the confidence of any answer whose
 * evidence quote does not literally occur in the text it was given.
 *
 * <p>Idempotent per (content hash, comment count): an unchanged issue with an unchanged thread
 * is never re-sent, and an issue the maintainer has taken or skipped is never re-sent at all.
 */
@Service
public class QuickPickService {

	private static final Logger logger = LoggerFactory.getLogger(QuickPickService.class);

	private static final String SYSTEM = """
			You advise a core maintainer of Spring AI (Java, Spring Boot) who is choosing small, safe
			issues to fix in a single sitting on the main branch (the 2.x line; %s are out of OSS
			support). You are given one open issue: its title, labels, body, and comment thread in
			order, with maintainers marked.

			Estimate, strictly from that text, what it would take this maintainer — someone who knows
			the codebase well — to land a fix on main with a test:
			- effort: ABOUT_AN_HOUR only when the change is narrow and the text makes the fix clear:
			  a one-class bug with a reproduction, a documentation correction, a missing null check,
			  a small additive option. HALF_DAY when it needs investigation first or touches several
			  places. MULTI_DAY for anything needing design, a refactor, or a new integration.
			  CANNOT_TELL when the text is too thin to say.
			- apiRisk: NONE, ADDITIVE, BREAKING, or CANNOT_TELL, as it affects Spring AI's public API.
			- blockers: everything that would stop the maintainer finishing today. Read the comments
			  carefully: a maintainer reply, a "working on it", a "fixed in main", or an unanswered
			  request for details all matter. Leave the list empty only if nothing stands in the way.
			- likelyScope: where the change lands, using only names that appear in the text.
			- evidence: copy VERBATIM the single sentence or fragment that best supports the effort
			  estimate. Exact characters; never paraphrase, translate, or invent a quote.
			- firstStep: the concrete first move, two sentences at most.
			- confidence: how well the text supports all of the above.

			Be conservative. When torn between two effort levels choose the larger one: a false
			"about an hour" wastes the maintainer's morning, a missed one costs nothing.
			""";

	private static final Set<String> MAINTAINER_ASSOCIATIONS = Set.of("OWNER", "MEMBER", "COLLABORATOR");

	private final ChatClient chat;

	private final QuickPickRepository repo;

	private final GitHubClient github;

	private final PulseProperties props;

	private final PulseProperties.Picks picks;

	public QuickPickService(ChatModel chatModel, QuickPickRepository repo, GitHubClient github,
			PulseProperties props) {
		this.chat = ChatClient.create(chatModel);
		this.repo = repo;
		this.github = github;
		this.props = props;
		// pulse.picks binds to null when the block is absent from config — fall back to the
		// record's documented defaults rather than NPE at first use
		this.picks = props.picks() != null ? props.picks() : new PulseProperties.Picks("claude-opus-5", 50, 25, 1500);
	}

	public String model() {
		return this.picks.model();
	}

	/** Number of candidates an assessment run would send to the model right now. */
	public int pendingCount() {
		return this.repo.findNeedingAssessment(defaultClassifier(), this.picks.poolSize()).size();
	}

	/**
	 * Assess every pending candidate, capped at {@code limit} (0 = the whole pool). The cap lets
	 * the operator smoke-test a few verdicts before committing to the full spend.
	 */
	public AssessResult assess(int limit) {
		List<QuickPickRepository.Candidate> all = this.repo.findNeedingAssessment(defaultClassifier(),
				this.picks.poolSize());
		List<QuickPickRepository.Candidate> pending = (limit > 0 && all.size() > limit) ? all.subList(0, limit)
				: all;
		if (pending.isEmpty()) {
			logger.info("Quick picks: nothing to assess (pool is current)");
			return new AssessResult(0, 0, 0, 0, 0, 0);
		}
		// the strong model is slower per call and the pool is small; keep the fan-out modest
		int concurrency = Math.max(1, Math.min(4, this.props.classify().concurrency()));
		logger.info("Quick picks: assessing {} issue(s) with {} @ concurrency {}", pending.size(),
				this.picks.model(), concurrency);
		ExecutorService pool = Executors.newFixedThreadPool(concurrency);
		AtomicInteger failed = new AtomicInteger();
		AtomicInteger hour = new AtomicInteger();
		AtomicInteger halfDay = new AtomicInteger();
		AtomicInteger multiDay = new AtomicInteger();
		AtomicInteger cannotTell = new AtomicInteger();
		AtomicLong inTokens = new AtomicLong();
		AtomicLong outTokens = new AtomicLong();
		String system = SYSTEM.formatted(String.join(", ", this.props.support().eolBranches()));
		try {
			List<Future<?>> futures = new ArrayList<>(pending.size());
			for (QuickPickRepository.Candidate candidate : pending) {
				futures.add(pool.submit(() -> assessOne(candidate, system, failed, hour, halfDay, multiDay,
						cannotTell, inTokens, outTokens)));
			}
			for (Future<?> f : futures) {
				try {
					f.get();
				}
				catch (Exception ex) {
					// per-item failures are already counted in assessOne
				}
			}
		}
		finally {
			pool.shutdown();
		}
		int ok = hour.get() + halfDay.get() + multiDay.get() + cannotTell.get();
		double cost = estimateCost(this.picks.model(), inTokens.get(), outTokens.get());
		logger.info("Quick picks complete: {} ok, {} failed of {}; {} ABOUT_AN_HOUR / {} HALF_DAY / {} MULTI_DAY /"
				+ " {} CANNOT_TELL; tokens in={} out={}; est cost ${}", ok, failed.get(), pending.size(),
				hour.get(), halfDay.get(), multiDay.get(), cannotTell.get(), inTokens.get(), outTokens.get(),
				String.format("%.4f", cost));
		return new AssessResult(ok, failed.get(), hour.get(), halfDay.get(), multiDay.get(), cannotTell.get());
	}

	private void assessOne(QuickPickRepository.Candidate candidate, String system, AtomicInteger failed,
			AtomicInteger hour, AtomicInteger halfDay, AtomicInteger multiDay, AtomicInteger cannotTell,
			AtomicLong inTokens, AtomicLong outTokens) {
		try {
			String thread = commentThread(candidate.number(), candidate.commentsCount());
			String text = issueText(candidate, thread);
			var converter = new BeanOutputConverter<>(PickAssessment.class);
			ChatResponse response = this.chat.prompt()
				.system(system)
				.user(text + "\n\n" + converter.getFormat())
				// thinking tokens count against max-tokens on Opus, so leave room for both
				.options(AnthropicChatOptions.builder().model(this.picks.model()).maxTokens(4096))
				.call()
				.chatResponse();
			accumulateUsage(response, inTokens, outTokens);
			// strong models still occasionally fence the JSON; cut to the outermost object
			// rather than failing the item (a failed item has no row and would be retried
			// — and re-fail — on every run)
			PickAssessment raw = converter.convert(extractJsonObject(answerText(response)));
			if (raw == null) {
				failed.incrementAndGet();
				return;
			}
			PickAssessment guarded = guard(raw, text);
			this.repo.upsert(candidate.number(), guarded, this.picks.model(), candidate.contentHash(),
					candidate.commentsCount());
			switch (guarded.effort()) {
				case ABOUT_AN_HOUR -> hour.incrementAndGet();
				case HALF_DAY -> halfDay.incrementAndGet();
				case MULTI_DAY -> multiDay.incrementAndGet();
				case CANNOT_TELL -> cannotTell.incrementAndGet();
			}
		}
		catch (Exception ex) {
			failed.incrementAndGet();
			logger.warn("Quick-pick assessment failed for #{}: {}", candidate.number(), ex.getMessage());
		}
	}

	/** The issue as the model sees it: title, labels, body (capped), then the thread. */
	private String issueText(QuickPickRepository.Candidate candidate, String thread) {
		String body = candidate.body() != null ? candidate.body() : "";
		int max = this.props.classify().maxBodyChars();
		if (body.length() > max) {
			body = body.substring(0, max) + "\n[body truncated]";
		}
		StringBuilder sb = new StringBuilder();
		sb.append("Issue #").append(candidate.number()).append("\nTitle: ").append(candidate.title()).append('\n');
		if (!candidate.labels().isEmpty()) {
			sb.append("Labels: ").append(String.join(", ", candidate.labels())).append('\n');
		}
		sb.append("\nBody:\n").append(body).append("\n\n").append(thread);
		return sb.toString();
	}

	/**
	 * The comment thread, oldest first, bots dropped, each comment capped. A fetch failure is
	 * logged and yields an explicit "unavailable" note rather than failing the assessment: the
	 * body alone still supports a (lower-confidence) verdict.
	 */
	private String commentThread(int number, int commentsCount) {
		if (commentsCount <= 0) {
			return "Comments: none.";
		}
		List<JsonNode> comments;
		try {
			comments = this.github.fetchComments(number, this.picks.maxComments());
		}
		catch (Exception ex) {
			logger.warn("Could not fetch comments for #{}: {}", number, ex.getMessage());
			return "Comments: " + commentsCount + " on GitHub, but they could not be fetched.";
		}
		StringBuilder sb = new StringBuilder();
		int shown = 0;
		for (JsonNode c : comments) {
			String author = c.path("user").path("login").asText("");
			if (author.endsWith("[bot]")) {
				continue;
			}
			String assoc = c.path("author_association").asText("");
			String body = c.path("body").asText("");
			if (body.length() > this.picks.maxCommentChars()) {
				body = body.substring(0, this.picks.maxCommentChars()) + " [truncated]";
			}
			shown++;
			sb.append('[').append(shown).append("] ").append(author);
			if (MAINTAINER_ASSOCIATIONS.contains(assoc)) {
				sb.append(" (MAINTAINER)");
			}
			sb.append(", ").append(c.path("created_at").asText("")).append(":\n").append(body).append("\n\n");
		}
		String header = "Comments (" + shown + " shown of " + commentsCount + ", oldest first):\n";
		return shown == 0 ? "Comments: " + commentsCount + " on GitHub, none from humans." : header + sb;
	}

	/**
	 * Deterministic guard on the model's answer: null-safe defaults, and a definite effort
	 * estimate keeps its stated confidence only when its evidence quote literally occurs in the
	 * text the model was given (whitespace-normalized). Otherwise confidence drops to LOW — the
	 * verdict is kept, but the UI shows it as weakly supported.
	 */
	static PickAssessment guard(PickAssessment a, String sourceText) {
		PickEffort effort = a.effort() != null ? a.effort() : PickEffort.CANNOT_TELL;
		ApiRisk apiRisk = a.apiRisk() != null ? a.apiRisk() : ApiRisk.CANNOT_TELL;
		List<PickBlocker> blockers = a.blockers() != null ? a.blockers().stream().filter(Objects::nonNull).toList()
				: List.of();
		String evidence = a.evidence() != null ? a.evidence().trim() : "";
		PickConfidence confidence = a.confidence() != null ? a.confidence() : PickConfidence.LOW;
		if (effort != PickEffort.CANNOT_TELL) {
			boolean occurs = !evidence.isEmpty() && normalize(sourceText).contains(normalize(evidence));
			if (!occurs) {
				confidence = PickConfidence.LOW;
			}
		}
		return new PickAssessment(effort, apiRisk, blockers, a.likelyScope(), evidence, a.firstStep(), confidence);
	}

	private static String normalize(String s) {
		return s.replaceAll("\\s+", " ").trim();
	}

	/**
	 * All text the model produced, across generations. With extended thinking on, the reply
	 * arrives as a thinking block followed by the answer; reading only the first generation
	 * would yield an empty string and a spurious "no JSON" failure.
	 */
	private static String answerText(ChatResponse response) {
		StringBuilder sb = new StringBuilder();
		for (var generation : response.getResults()) {
			String text = generation.getOutput() != null ? generation.getOutput().getText() : null;
			if (text != null) {
				sb.append(text);
			}
		}
		return sb.toString();
	}

	private static String extractJsonObject(String text) {
		int start = text.indexOf('{');
		int end = text.lastIndexOf('}');
		if (start < 0 || end <= start) {
			String head = text.length() > 200 ? text.substring(0, 200) + "…" : text;
			throw new IllegalStateException("no JSON object in model output (" + text.length() + " chars): " + head);
		}
		return text.substring(start, end + 1);
	}

	/** Published per-million-token rates, keyed on the model family; unknown models log at Opus rates. */
	static double estimateCost(String model, long inTokens, long outTokens) {
		double in = 5.0;
		double out = 25.0;
		if (model.startsWith("claude-haiku")) {
			in = 1.0;
			out = 5.0;
		}
		else if (model.startsWith("claude-sonnet-4")) {
			in = 3.0;
			out = 15.0;
		}
		else if (model.startsWith("claude-sonnet")) {
			in = 2.0;
			out = 10.0;
		}
		return (inTokens * in + outTokens * out) / 1_000_000.0;
	}

	private static void accumulateUsage(ChatResponse response, AtomicLong inTokens, AtomicLong outTokens) {
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

	private static String defaultClassifier() {
		return com.springai.pulse.domain.ModelIds.DEFAULT_CLASSIFIER;
	}

	/** Outcome of one assessment run (counts reflect post-guard verdicts). */
	public record AssessResult(int assessed, int failed, int aboutAnHour, int halfDay, int multiDay,
			int cannotTell) {
	}

}
