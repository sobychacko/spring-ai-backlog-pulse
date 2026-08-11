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

import java.util.ArrayDeque;
import java.util.concurrent.ConcurrentHashMap;

import com.springai.pulse.config.PulseProperties;

import org.springframework.stereotype.Component;

/**
 * Per-IP sliding-window limiter for chat turns ({@code pulse.chat.questions-per-hour}).
 * In-memory and deliberately crude — no persistence, no distributed state: the worst failure
 * mode (a restart forgetting windows, or a spoofed X-Forwarded-For rotating IPs) degrades to
 * the {@link ChatBudget} daily ceiling, which is the real backstop.
 */
@Component
public class ChatRateLimiter {

	private static final long WINDOW_MILLIS = 60 * 60 * 1000;

	/** Memory guard: a flood of distinct IPs wipes all windows rather than growing the map. */
	private static final int MAX_TRACKED_IPS = 10_000;

	private final ConcurrentHashMap<String, ArrayDeque<Long>> byIp = new ConcurrentHashMap<>();

	private final int questionsPerHour;

	public ChatRateLimiter(PulseProperties props) {
		this.questionsPerHour = props.chat().questionsPerHour();
	}

	/** Try to consume one question slot for this IP; false = over the hourly limit. */
	public boolean tryAcquire(String ip) {
		if (this.byIp.size() > MAX_TRACKED_IPS) {
			this.byIp.clear();
		}
		ArrayDeque<Long> stamps = this.byIp.computeIfAbsent(ip, k -> new ArrayDeque<>());
		long now = System.currentTimeMillis();
		synchronized (stamps) {
			while (!stamps.isEmpty() && now - stamps.peekFirst() > WINDOW_MILLIS) {
				stamps.pollFirst();
			}
			if (stamps.size() >= this.questionsPerHour) {
				return false;
			}
			stamps.addLast(now);
			return true;
		}
	}

}
