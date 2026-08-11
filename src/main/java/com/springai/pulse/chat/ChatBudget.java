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

import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicReference;

import com.springai.pulse.config.PulseProperties;

import org.springframework.stereotype.Component;

/**
 * Global daily spend ceiling for the chat endpoint — the backstop that bounds the blast radius
 * of any abuse: whatever gets past the per-IP rate limit, total damage caps at the configured
 * dollar ceiling per day ({@code pulse.chat.daily-budget-usd}). Applies to admin use too.
 *
 * <p>Spend is tracked in micro-dollars at Claude Haiku 4.5 rates ($1/MTok in, $5/MTok out —
 * the same rates {@code LegacyReviewService} uses), so one input token = 1 µ$ and one output
 * token = 5 µ$. Day + spend live in a single atomically-swapped pair, making the midnight
 * reset race-free without locks. The check is optimistic (a turn that crosses the line still
 * completes), so the ceiling can overshoot by at most one turn (~1–3¢).
 */
@Component
public class ChatBudget {

	private record DaySpend(LocalDate day, long microUsd, int turns) {
	}

	private final AtomicReference<DaySpend> spend = new AtomicReference<>(new DaySpend(LocalDate.now(), 0, 0));

	private final double dailyBudgetUsd;

	public ChatBudget(PulseProperties props) {
		this.dailyBudgetUsd = props.chat().dailyBudgetUsd();
	}

	/** Whether today's spend is still under the ceiling (checked before each turn). */
	public boolean hasBudget() {
		return spentTodayUsd() < this.dailyBudgetUsd;
	}

	/** Record one completed turn's token usage against today's budget. */
	public void record(long inTokens, long outTokens) {
		long microUsd = inTokens + 5 * outTokens;
		while (true) {
			DaySpend current = today();
			DaySpend next = new DaySpend(current.day(), current.microUsd() + microUsd, current.turns() + 1);
			if (this.spend.compareAndSet(current, next)) {
				return;
			}
		}
	}

	public double spentTodayUsd() {
		return today().microUsd() / 1_000_000.0;
	}

	public int turnsToday() {
		return today().turns();
	}

	public double dailyBudgetUsd() {
		return this.dailyBudgetUsd;
	}

	/** Today's counter, atomically resetting when the stored day is stale. */
	private DaySpend today() {
		while (true) {
			DaySpend current = this.spend.get();
			if (current.day().equals(LocalDate.now())) {
				return current;
			}
			DaySpend fresh = new DaySpend(LocalDate.now(), 0, 0);
			if (this.spend.compareAndSet(current, fresh)) {
				return fresh;
			}
		}
	}

}
