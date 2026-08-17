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
import java.util.Map;

import com.springai.pulse.config.PulseProperties;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Global daily spend ceiling for the chat endpoint — the backstop that bounds the blast radius
 * of any abuse: whatever gets past the per-IP rate limit, total damage caps at the configured
 * dollar ceiling per day ({@code pulse.chat.daily-budget-usd}). Applies to admin use too.
 *
 * <p>Spend is tracked in micro-dollars at Claude Haiku 4.5 rates ($1/MTok in, $5/MTok out —
 * the same rates {@code LegacyReviewService} uses), so one input token = 1 µ$ and one output
 * token = 5 µ$. The ledger lives in the {@code chat_spend} table, one row per day, updated
 * with an atomic upsert — so it survives restarts and serverless sleep/wake cycles, and is
 * shared if the service ever runs more than one replica. The check is optimistic (a turn that
 * crosses the line still completes), so the ceiling can overshoot by at most one turn (~1–3¢).
 */
@Component
public class ChatBudget {

	private record DaySpend(long microUsd, int turns) {
	}

	private final NamedParameterJdbcTemplate jdbc;

	private final double dailyBudgetUsd;

	public ChatBudget(NamedParameterJdbcTemplate jdbc, PulseProperties props) {
		this.jdbc = jdbc;
		this.dailyBudgetUsd = props.chat().dailyBudgetUsd();
	}

	/** Whether today's spend is still under the ceiling (checked before each turn). */
	public boolean hasBudget() {
		return spentTodayUsd() < this.dailyBudgetUsd;
	}

	/** Record one completed turn's token usage against today's budget. */
	public void record(long inTokens, long outTokens) {
		long microUsd = inTokens + 5 * outTokens;
		this.jdbc.update("""
				insert into chat_spend (day, micro_usd, turns) values (:day, :micro, 1)
				on conflict (day) do update
				   set micro_usd = chat_spend.micro_usd + excluded.micro_usd,
				       turns     = chat_spend.turns + 1
				""", Map.of("day", LocalDate.now(), "micro", microUsd));
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

	/** Today's ledger row, or zero if nothing has been spent yet today. */
	private DaySpend today() {
		return this.jdbc
			.query("select micro_usd, turns from chat_spend where day = :day", Map.of("day", LocalDate.now()),
					(rs, i) -> new DaySpend(rs.getLong("micro_usd"), rs.getInt("turns")))
			.stream()
			.findFirst()
			.orElse(new DaySpend(0, 0));
	}

}
