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

/** Reasons an otherwise small issue cannot be picked up and finished in one sitting. */
public enum PickBlocker {

	/** Cannot reproduce or scope without more detail from the reporter. */
	NEEDS_REPORTER_INFO,

	/** A maintainer decision on approach or API shape is needed first. */
	NEEDS_DESIGN_DECISION,

	/** Depends on a change in an upstream library, provider API, or another project. */
	NEEDS_EXTERNAL_CHANGE,

	/** The thread indicates the problem is already fixed or no longer reproduces on main. */
	LIKELY_ALREADY_FIXED,

	/** Someone in the thread says they are working on it or has a PR in flight. */
	SOMEONE_WORKING_ON_IT,

	/** A question, discussion, or request with no concrete code change to make. */
	NOT_ACTIONABLE

}
