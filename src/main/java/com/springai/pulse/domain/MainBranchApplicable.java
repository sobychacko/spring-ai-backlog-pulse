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
 * Whether a PR targeting an old/inactive branch (1.x, 0.x) should also be applied to main.
 * Set to NOT_APPLICABLE for issues or PRs already targeting main/2.x.
 */
public enum MainBranchApplicable {

	/** The fix/change almost certainly applies to main too. */
	YES,

	/** The change is specific to the old branch API and does not apply to main. */
	NO,

	/** Cannot determine from the description alone. */
	UNCLEAR,

	/** Not an old-branch PR. */
	NOT_APPLICABLE

}
