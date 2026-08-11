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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.springai.pulse.config.PulseProperties;
import com.springai.pulse.domain.LegacyAssessment;
import com.springai.pulse.domain.LegacyVerdict;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * MVP 8: assesses open issues that mention an end-of-life version. Candidates are gated by a
 * deterministic regex over title/body (built from the configured EOL branch list); only those
 * go to the LLM, which answers with a {@link LegacyVerdict} plus a verbatim evidence quote.
 * A deterministic guard demotes any definite verdict to UNCLEAR when the quote does not
 * literally occur in the item text. Idempotent via {@code content_hash} — an unchanged item is
 * never re-sent to the model.
 */
@Service
public class LegacyReviewService {

	private static final Logger logger = LoggerFactory.getLogger(LegacyReviewService.class);

	private static final String SYSTEM = """
			You review GitHub issues for the Spring AI project. The following release lines are OUT
			of OSS support (end-of-life): %s. Active development happens on main / 2.x.

			The issue you are given mentions a version from an EOL line. Decide, STRICTLY from the
			provided title and body, whether the issue only concerns the EOL line:
			- LEGACY_ONLY: the concern is tied to the EOL line and moot for current development —
			  e.g. it asks for a fix/backport/upgrade within the EOL line, targets an API that was
			  removed or replaced in 2.x, or explicitly says the problem is gone in newer versions.
			- APPLIES_TO_MAIN: the underlying bug or request plausibly still exists on main/2.x.
			  Merely stating "I am using 1.0.0" does NOT make an issue legacy-only — most reports
			  just name the version the author happened to run.
			- UNCLEAR: cannot tell from the text.

			`evidence`: quote VERBATIM the single sentence or fragment from the item text — ideally
			containing the version mention — that best supports your verdict. Copy it exactly,
			character for character; never paraphrase, translate, or invent a quote.
			""";

	private final ChatClient chat;

	private final LegacyReviewRepository repo;

	private final PulseProperties props;

	private final String model;

	public LegacyReviewService(ChatModel chatModel, LegacyReviewRepository repo, PulseProperties props,
			@Value("${spring.ai.anthropic.chat.options.model:claude-haiku-4-5}") String model) {
		this.chat = ChatClient.create(chatModel);
		this.repo = repo;
		this.props = props;
		this.model = model;
	}

	/** EOL branch names from config, e.g. {@code [1.0.x, 1.1.x]}. */
	public List<String> eolBranches() {
		return this.props.support().eolBranches();
	}

	/**
	 * Postgres regex matching an EOL version mention, e.g. {@code \m(1\.0|1\.1)\.[0-9x]+}.
	 * Requires a patch segment so bare "1.0" (too noisy) does not qualify.
	 */
	public String versionMentionRegex() {
		String alternatives = eolBranches().stream()
			.map(b -> b.replaceAll("\\.x$", ""))
			.map(prefix -> prefix.replace(".", "\\."))
			.collect(Collectors.joining("|"));
		return "\\m(" + alternatives + ")\\.[0-9x]+";
	}

	/** Same version pattern for Java-side checks (evidence guard, UI chips). */
	private Pattern versionPattern() {
		return Pattern.compile("\\b" + versionMentionRegex().replace("\\m", "") + "\\b");
	}

	/** Number of candidates a scan would send to the model right now (for the cost prompt). */
	public int pendingCount() {
		return this.repo.findNeedingScan(versionMentionRegex()).size();
	}

	/**
	 * Assess every pending candidate, capped at {@code limit} (0 = no cap; the cap lets us smoke-
	 * test a handful of items before committing to the full spend).
	 */
	public ScanResult scan(int limit) {
		List<LegacyReviewRepository.Candidate> all = this.repo.findNeedingScan(versionMentionRegex());
		List<LegacyReviewRepository.Candidate> pending = (limit > 0 && all.size() > limit) ? all.subList(0, limit)
				: all;
		if (pending.isEmpty()) {
			logger.info("Legacy scan: nothing to do (all candidates current)");
			return new ScanResult(0, 0, 0, 0, 0);
		}
		int concurrency = Math.max(1, this.props.classify().concurrency());
		logger.info("Legacy scan: assessing {} issue(s) with {} @ concurrency {}", pending.size(), this.model,
				concurrency);
		ExecutorService pool = Executors.newFixedThreadPool(concurrency);
		AtomicInteger failed = new AtomicInteger();
		AtomicInteger legacyOnly = new AtomicInteger();
		AtomicInteger appliesToMain = new AtomicInteger();
		AtomicInteger unclear = new AtomicInteger();
		AtomicLong inTokens = new AtomicLong();
		AtomicLong outTokens = new AtomicLong();
		Pattern versionPattern = versionPattern();
		String system = SYSTEM.formatted(String.join(", ", eolBranches()));
		try {
			List<Future<?>> futures = new ArrayList<>(pending.size());
			for (LegacyReviewRepository.Candidate candidate : pending) {
				futures.add(pool.submit(() -> assessOne(candidate, system, versionPattern, failed, legacyOnly,
						appliesToMain, unclear, inTokens, outTokens)));
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
		int ok = legacyOnly.get() + appliesToMain.get() + unclear.get();
		// Haiku rates: $1/MTok in, $5/MTok out
		double cost = (inTokens.get() * 1.0 + outTokens.get() * 5.0) / 1_000_000.0;
		logger.info("Legacy scan complete: {} ok, {} failed of {}; {} LEGACY_ONLY / {} APPLIES_TO_MAIN / {} UNCLEAR;"
				+ " tokens in={} out={}; est cost ${}", ok, failed.get(), pending.size(), legacyOnly.get(),
				appliesToMain.get(), unclear.get(), inTokens.get(), outTokens.get(), String.format("%.4f", cost));
		return new ScanResult(ok, failed.get(), legacyOnly.get(), appliesToMain.get(), unclear.get());
	}

	private void assessOne(LegacyReviewRepository.Candidate candidate, String system, Pattern versionPattern,
			AtomicInteger failed, AtomicInteger legacyOnly, AtomicInteger appliesToMain, AtomicInteger unclear,
			AtomicLong inTokens, AtomicLong outTokens) {
		try {
			String body = candidate.body() != null ? candidate.body() : "";
			int max = this.props.classify().maxBodyChars();
			if (body.length() > max) {
				body = body.substring(0, max);
			}
			String user = "Title: " + candidate.title() + "\n\nBody:\n" + body;
			var response = this.chat.prompt().system(system).user(user).call().responseEntity(LegacyAssessment.class);
			LegacyAssessment result = response.getEntity();
			accumulateUsage(response.getResponse(), inTokens, outTokens);
			if (result == null) {
				failed.incrementAndGet();
				return;
			}
			LegacyAssessment guarded = guard(result, candidate.title(), candidate.body(), versionPattern);
			this.repo.upsert(candidate.number(), guarded.verdict(), guarded.evidence(), this.model,
					candidate.contentHash());
			switch (guarded.verdict()) {
				case LEGACY_ONLY -> legacyOnly.incrementAndGet();
				case APPLIES_TO_MAIN -> appliesToMain.incrementAndGet();
				case UNCLEAR -> unclear.incrementAndGet();
			}
		}
		catch (Exception ex) {
			failed.incrementAndGet();
			logger.warn("Legacy assessment failed for #{}: {}", candidate.number(), ex.getMessage());
		}
	}

	/**
	 * Deterministic guard on the model's answer (same spirit as the classify rubric guards): a
	 * definite verdict is only trusted when its evidence quote literally occurs in the item text
	 * (whitespace-normalized), and a LEGACY_ONLY verdict additionally requires the quote to
	 * contain an EOL version mention — the fact the verdict hinges on. Anything else → UNCLEAR.
	 */
	static LegacyAssessment guard(LegacyAssessment assessment, String title, String body, Pattern versionPattern) {
		LegacyVerdict verdict = assessment.verdict() != null ? assessment.verdict() : LegacyVerdict.UNCLEAR;
		String evidence = assessment.evidence() != null ? assessment.evidence().trim() : "";
		if (verdict != LegacyVerdict.UNCLEAR) {
			String text = normalize(title + "\n" + (body != null ? body : ""));
			boolean occurs = !evidence.isEmpty() && text.contains(normalize(evidence));
			boolean mentionsVersion = versionPattern.matcher(evidence).find();
			if (!occurs || (verdict == LegacyVerdict.LEGACY_ONLY && !mentionsVersion)) {
				verdict = LegacyVerdict.UNCLEAR;
			}
		}
		return new LegacyAssessment(verdict, evidence);
	}

	private static String normalize(String s) {
		return s.replaceAll("\\s+", " ").trim();
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

	/** Outcome of one scan run (counts reflect post-guard verdicts). */
	public record ScanResult(int scanned, int failed, int legacyOnly, int appliesToMain, int unclear) {
	}

}
