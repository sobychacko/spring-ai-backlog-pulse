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

package com.springai.pulse.domain;

/** Whether landing the change on main would alter Spring AI's public API. */
public enum ApiRisk {

	/** Internal fix, docs, tests, or behaviour change behind an existing contract. */
	NONE,

	/** Adds a new public method, option, or property without changing existing ones. */
	ADDITIVE,

	/** Changes or removes an existing public signature or behaviour users depend on. */
	BREAKING,

	/** The text does not say enough to tell. */
	CANNOT_TELL

}
