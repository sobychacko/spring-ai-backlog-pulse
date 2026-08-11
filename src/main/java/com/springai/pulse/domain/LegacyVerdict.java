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

/**
 * Whether an issue that mentions an end-of-life version concerns only that EOL branch, or
 * whether the underlying problem/request also applies to the current codebase.
 */
public enum LegacyVerdict {

	/** The issue is tied to an EOL branch and can likely be closed as out of support. */
	LEGACY_ONLY,

	/** The underlying problem or request plausibly still exists on main/2.x. */
	APPLIES_TO_MAIN,

	/** Cannot determine from the item text alone. */
	UNCLEAR

}
