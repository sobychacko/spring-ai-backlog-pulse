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

package com.springai.pulse.web;

import java.util.List;
import java.util.Map;

import com.springai.pulse.chat.ChatBudget;
import com.springai.pulse.chat.ChatService;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * MVP 9 "Ask the backlog". POST /api/chat is metered (Haiku per turn) and admin-token guarded
 * like every non-GET endpoint — unless {@code pulse.chat.public=true}, the one documented
 * carve-out in {@link AdminTokenFilter}. The guard chain (budget / rate limits / caps) applies
 * in both modes. Read-only toward GitHub, always.
 */
@RestController
@RequestMapping("/api")
public class ChatController {

	private final ChatService service;

	private final ChatBudget budget;

	public ChatController(ChatService service, ChatBudget budget) {
		this.service = service;
		this.budget = budget;
	}

	@PostMapping("/chat")
	public ChatService.ChatResult chat(@RequestBody ChatRequest request, HttpServletRequest http) {
		return this.service.ask(clientIp(http), request.question(), request.history());
	}

	/**
	 * Today's spend against the ceiling — read by the chat panel so a questioner can see why
	 * answers stop at the cap. Deliberately says nothing about how the endpoint is reached:
	 * this response is public, and the deployment's auth posture is not something to publish.
	 */
	@GetMapping("/chat/status")
	public Map<String, Object> status() {
		return Map.of(
				"dailyBudgetUsd", this.budget.dailyBudgetUsd(),
				"spentTodayUsd", this.budget.spentTodayUsd(),
				"turnsToday", this.budget.turnsToday(),
				"budgetAvailable", this.budget.hasBudget());
	}

	/**
	 * First X-Forwarded-For hop when present (Railway fronts the app with a proxy), else the
	 * socket address. XFF is spoofable when hit directly, which only lets an abuser rotate
	 * per-IP windows — the daily budget ceiling is the real backstop.
	 */
	private static String clientIp(HttpServletRequest request) {
		String forwarded = request.getHeader("X-Forwarded-For");
		if (forwarded != null && !forwarded.isBlank()) {
			return forwarded.split(",")[0].trim();
		}
		return request.getRemoteAddr();
	}

	public record ChatRequest(String question, List<ChatService.Turn> history) {
	}

}
