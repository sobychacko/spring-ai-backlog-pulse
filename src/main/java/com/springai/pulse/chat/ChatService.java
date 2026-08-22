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
import java.util.List;
import java.util.concurrent.Semaphore;

import com.springai.pulse.analytics.AnalyticsRepository;
import com.springai.pulse.config.PulseProperties;
import com.springai.pulse.search.SemanticSearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.anthropic.models.messages.ToolChoice;
import com.anthropic.models.messages.ToolChoiceNone;

import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.context.annotation.Lazy;

/**
 * MVP 9 "Ask the backlog": a metered chat turn behind a guard chain. The LLM is a router, not
 * a knower — it translates questions into {@link ChatTools} calls; every number comes from SQL
 * and the matched items render as the same cards the tabs use, with LLM prose as commentary.
 * Guards, in order: per-IP hourly rate limit → global in-flight semaphore → daily budget
 * ceiling (a friendly in-band refusal, not an error). Per-turn caps: question length,
 * server-side history truncation, and a hard tool-loop iteration cap.
 *
 * <p>The tool loop runs manually (direct {@code ChatModel} calls + a
 * {@link ToolCallingManager}) rather than via ChatClient's internal loop — that is what makes
 * the iteration cap deterministic and gives per-call token usage for the budget. On the last
 * allowed iteration the model is called without tools, forcing a prose answer.
 */
@Service
public class ChatService {

	private static final Logger logger = LoggerFactory.getLogger(ChatService.class);

	private static final int MAX_QUESTION_CHARS = 500;

	/** History is client-supplied; keep only the last N exchanges and cap each message. */
	private static final int MAX_HISTORY_TURNS = 5;

	private static final int MAX_HISTORY_MSG_CHARS = 2000;

	private static final int MAX_TOOL_ITERATIONS = 3;

	/** Cards shown under a reply — enough to ground the prose without flooding the panel. */
	private static final int MAX_CARDS = 12;

	private static final String SYSTEM = """
			You are "Ask the backlog", the chat assistant of Spring AI Backlog Pulse — a dashboard
			over the open issues and pull requests of the spring-projects/spring-ai GitHub repo.

			You answer ONLY questions about this backlog: its issues, PRs, themes, areas, trends,
			and contributors. Politely refuse anything else (other topics, code generation, general
			Spring AI usage help) in one short sentence.

			You are a router, not a knower: ground every factual claim in tool results. Every
			number you state MUST come verbatim from a tool result — never estimate, extrapolate,
			or recall numbers from training data. If the tools cannot answer, say so plainly.

			Your tools are real and invoked through the API's tool-use mechanism. Never write a
			tool call as text in your reply, and never state results you did not receive from an
			actual tool response — if no tool result reached you, say that instead of answering.

			Tool habits:
			- "is X a theme / popular / who's asking" → themeReport; report its popularityLabel and
			  percentiles as-is — popularity is deterministic here, not your judgment.
			- "growing? trend? opened vs closed" → trend (mind its dataBoundary field).
			- a specific #number → itemContext; "find items about X" → semanticSearch.
			- "hottest areas / compare areas" → pulse.
			You have at most 3 tool rounds per turn; batch what you need.

			When you cite "unique people asking", state the caveat that this counts item authors.
			Keep answers to a few sentences; the UI renders matching items as cards below your
			text, so never enumerate items exhaustively in prose — refer to "the items below".
			""";

	private final ChatModel chatModel;

	private final ToolCallingManager toolCallingManager;

	private final ChatBudget budget;

	private final ChatRateLimiter rateLimiter;

	private final Semaphore inFlight;

	private final VectorStore vectorStore;

	private final ChatToolsRepository toolsRepo;

	private final AnalyticsRepository analytics;

	private final SemanticSearchService semanticSearch;

	public ChatService(ChatModel chatModel, ChatBudget budget, ChatRateLimiter rateLimiter, PulseProperties props,
			@Lazy VectorStore vectorStore, ChatToolsRepository toolsRepo, AnalyticsRepository analytics,
			SemanticSearchService semanticSearch) {
		this.chatModel = chatModel;
		this.toolCallingManager = ToolCallingManager.builder().build();
		this.budget = budget;
		this.rateLimiter = rateLimiter;
		this.inFlight = new Semaphore(Math.max(1, props.chat().maxConcurrent()));
		// resolved guard values at boot: whether configuration actually reached the process is
		// otherwise invisible until someone trips a limit. Read through the properties record,
		// while AdminTokenFilter reports the same block via @Value — if the two disagree, the
		// binding is at fault rather than the deployment.
		logger.info("chat guards: budget ${}/day, {} questions/hour per IP, {} concurrent",
				props.chat().dailyBudgetUsd(), props.chat().questionsPerHour(), props.chat().maxConcurrent());
		this.vectorStore = vectorStore;
		this.toolsRepo = toolsRepo;
		this.analytics = analytics;
		this.semanticSearch = semanticSearch;
	}

	public ChatResult ask(String clientIp, String question, List<Turn> history) {
		if (question == null || question.isBlank()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "question is required");
		}
		if (question.length() > MAX_QUESTION_CHARS) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"question too long (max " + MAX_QUESTION_CHARS + " chars)");
		}
		if (!this.rateLimiter.tryAcquire(clientIp)) {
			throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
					"hourly question limit reached — try again later");
		}
		if (!this.inFlight.tryAcquire()) {
			throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
					"too many questions in flight — try again in a moment");
		}
		try {
			if (!this.budget.hasBudget()) {
				// friendly in-band refusal: renders as a normal assistant message
				return new ChatResult("I've hit today's answer budget — it resets at midnight. "
						+ "The dashboard tabs above have all the same data, no budget required.", true, List.of(),
						List.of(), List.of(), true);
			}
			return answer(clientIp, question.trim(), truncateHistory(history));
		}
		finally {
			this.inFlight.release();
		}
	}

	private ChatResult answer(String clientIp, String question, List<Message> history) {
		long start = System.currentTimeMillis();
		ChatTools tools = new ChatTools(this.vectorStore, this.toolsRepo, this.analytics, this.semanticSearch);
		// in Spring AI 2.0 a direct ChatModel.call never executes tools (the internal loop
		// lives in ChatClient) — the model returns tool calls and this service runs them
		ToolCallback[] callbacks = ToolCallbacks.from(tools);

		List<Message> messages = new ArrayList<>();
		messages.add(new SystemMessage(SYSTEM));
		messages.addAll(history);
		messages.add(new UserMessage(question));

		long inTokens = 0;
		long outTokens = 0;
		Prompt prompt = new Prompt(messages, options(callbacks, true));
		ChatResponse response = this.chatModel.call(prompt);
		inTokens += promptTokens(response);
		outTokens += completionTokens(response);
		int iterations = 0;
		while (response.hasToolCalls() && iterations < MAX_TOOL_ITERATIONS) {
			iterations++;
			ToolExecutionResult executed = this.toolCallingManager.executeToolCalls(prompt, response);
			// on the last allowed round the tools stay *declared* but tool choice is forced to
			// none: the history now contains tool_use/tool_result blocks, and Anthropic rejects
			// a request carrying those with no tools defined
			prompt = new Prompt(executed.conversationHistory(), options(callbacks, iterations < MAX_TOOL_ITERATIONS));
			response = this.chatModel.call(prompt);
			inTokens += promptTokens(response);
			outTokens += completionTokens(response);
		}

		this.budget.record(inTokens, outTokens);
		double cost = (inTokens + 5 * outTokens) / 1_000_000.0;
		logger.info("chat turn: ip={} qChars={} tools={} rounds={} tokens in={} out={} cost=${} spentToday=${} took={}ms",
				clientIp, question.length(), tools.toolsCalled(), iterations, inTokens, outTokens,
				String.format("%.4f", cost), String.format("%.4f", this.budget.spentTodayUsd()),
				System.currentTimeMillis() - start);

		String reply = response.getResult() != null && response.getResult().getOutput() != null
				&& response.getResult().getOutput().getText() != null ? response.getResult().getOutput().getText()
						: "";
		List<ChatToolsRepository.Card> cards = tools.collectedCards();
		if (cards.size() > MAX_CARDS) {
			cards = cards.subList(0, MAX_CARDS);
		}
		boolean grounded = !tools.toolsCalled().isEmpty();
		return new ChatResult(guardReply(reply, grounded), false, tools.toolsCalled(), cards,
				tools.collectedCaveats(), grounded);
	}

	/**
	 * Chat options for one round: the model's CONFIGURED options (model id, max-tokens, api key
	 * from {@code application.yml}) with this turn's tools added.
	 *
	 * <p>Deriving from {@code chatModel.getOptions()} is load-bearing, not tidiness. Spring AI's
	 * {@code AnthropicChatModel} does not merge prompt options with its defaults — prompt
	 * options replace them outright, and {@code createRequest} then discards anything that is
	 * not an {@code AnthropicChatOptions}, substituting a blank one. Passing a generic
	 * {@code ToolCallingChatOptions} therefore silently drops BOTH the tool callbacks (the model
	 * then imitates tool calls in prose and fabricates answers) and the configured max-tokens.
	 *
	 * @param allowToolUse false forces {@code tool_choice: none} — tools stay declared so the
	 * transcript's tool_use/tool_result blocks remain valid, but the model must answer in prose
	 */
	private ChatOptions options(ToolCallback[] callbacks, boolean allowToolUse) {
		if (this.chatModel.getOptions() instanceof AnthropicChatOptions configured) {
			var builder = configured.mutate().toolCallbacks(callbacks);
			if (!allowToolUse) {
				builder.toolChoice(ToolChoice.ofNone(ToolChoiceNone.builder().build()));
			}
			return builder.build();
		}
		return ToolCallingChatOptions.builder().toolCallbacks(callbacks).build();
	}

	/**
	 * Deterministic guard on the model's prose (same spirit as the classify/legacy rubric
	 * guards). A model with no usable tools falls back to emitting tool-call markup as text and
	 * inventing the results — never show that: it reads as a real answer. An ungrounded reply is
	 * also flagged so the UI can mark it rather than let it pass as backlog data.
	 */
	static String guardReply(String reply, boolean grounded) {
		String text = reply != null ? reply.trim() : "";
		boolean fabricatedToolCall = text.contains("<function_calls>") || text.contains("<invoke name=")
				|| text.contains("<invoke");
		if (fabricatedToolCall) {
			return "I couldn't reach the backlog query tools for that question, so I have no grounded "
					+ "answer to give. Please try again — and if it keeps happening, the tool wiring needs a look.";
		}
		if (text.isEmpty()) {
			return grounded ? "I ran the backlog queries but couldn't summarize them. The matching items are below."
					: "I don't have an answer for that one.";
		}
		return text;
	}

	/** Server-side truncation of the client-supplied transcript: last N exchanges, capped. */
	private static List<Message> truncateHistory(List<Turn> history) {
		if (history == null || history.isEmpty()) {
			return List.of();
		}
		List<Turn> tail = history.size() > MAX_HISTORY_TURNS * 2
				? history.subList(history.size() - MAX_HISTORY_TURNS * 2, history.size()) : history;
		List<Message> messages = new ArrayList<>(tail.size());
		for (Turn turn : tail) {
			String content = turn.content() != null ? turn.content() : "";
			if (content.length() > MAX_HISTORY_MSG_CHARS) {
				content = content.substring(0, MAX_HISTORY_MSG_CHARS);
			}
			messages.add("assistant".equalsIgnoreCase(turn.role()) ? new AssistantMessage(content)
					: new UserMessage(content));
		}
		return messages;
	}

	private static long promptTokens(ChatResponse response) {
		return (response != null && response.getMetadata() != null && response.getMetadata().getUsage() != null
				&& response.getMetadata().getUsage().getPromptTokens() != null)
						? response.getMetadata().getUsage().getPromptTokens() : 0;
	}

	private static long completionTokens(ChatResponse response) {
		return (response != null && response.getMetadata() != null && response.getMetadata().getUsage() != null
				&& response.getMetadata().getUsage().getCompletionTokens() != null)
						? response.getMetadata().getUsage().getCompletionTokens() : 0;
	}

	/** One prior message in the conversation, as the client resends it. */
	public record Turn(String role, String content) {
	}

	/**
	 * The assistant's reply. {@code refused} marks budget refusals (no model call made);
	 * {@code cards} are the matched items in the UI's ItemView shape; {@code caveats} are
	 * honesty notes from the tools (author-counting, closure-tracking boundary);
	 * {@code grounded} is false when no tool ran, so the UI can mark the answer as not backed
	 * by backlog data instead of letting it read like every other reply.
	 */
	public record ChatResult(String reply, boolean refused, List<String> toolsUsed,
			List<ChatToolsRepository.Card> cards, List<String> caveats, boolean grounded) {
	}

}
