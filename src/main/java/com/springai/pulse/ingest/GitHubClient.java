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

package com.springai.pulse.ingest;

import java.util.ArrayList;
import java.util.List;

import tools.jackson.databind.JsonNode;

import com.springai.pulse.config.GitHubProperties;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

/**
 * Thin GitHub REST client. The {@code /issues} listing returns both issues and pull requests
 * (a PR carries a {@code pull_request} field), which is exactly what we want — PRs are
 * first-class.
 */
@Component
public class GitHubClient {

	private final RestClient rest;

	private final GitHubProperties props;

	public GitHubClient(GitHubProperties props) {
		this.props = props;
		RestClient.Builder b = RestClient.builder()
			.baseUrl(props.apiBase())
			.defaultHeader("Accept", "application/vnd.github+json")
			.defaultHeader("X-GitHub-Api-Version", "2022-11-28");
		if (StringUtils.hasText(props.token())) {
			b = b.defaultHeader("Authorization", "Bearer " + props.token());
		}
		this.rest = b.build();
	}

	/**
	 * Fetch every open issue and PR, following pagination. At ~1.4k items / 100 per page this is
	 * ~14 requests — comfortably within GitHub's authenticated rate limit.
	 */
	public List<JsonNode> fetchOpenItems(int pageSize) {
		return fetchPage("state=open", null, pageSize);
	}

	/**
	 * Fetch open PRs from the {@code /pulls} endpoint, which includes {@code base.ref} — the
	 * target branch. The {@code /issues} listing does not include this field.
	 */
	public List<JsonNode> fetchOpenPullRequests(int pageSize) {
		List<JsonNode> all = new ArrayList<>();
		int page = 1;
		while (true) {
			JsonNode arr = this.rest.get()
				.uri("/repos/" + this.props.repo() + "/pulls?state=open&per_page={ps}&page={p}", pageSize, page)
				.retrieve()
				.body(JsonNode.class);
			if (arr == null || !arr.isArray() || arr.isEmpty()) {
				break;
			}
			arr.forEach(all::add);
			if (arr.size() < pageSize) {
				break;
			}
			page++;
		}
		return all;
	}

	/**
	 * Fetch all items (open and closed) updated at or after {@code since}, sorted ascending by
	 * update time so the caller can advance the cursor to the last item seen.
	 */
	public List<JsonNode> fetchItemsSince(String since, int pageSize) {
		return fetchPage("state=all&sort=updated&direction=asc", since, pageSize);
	}

	private List<JsonNode> fetchPage(String baseParams, String since, int pageSize) {
		List<JsonNode> all = new ArrayList<>();
		String sinceParam = (since != null && !since.isBlank()) ? "&since=" + since : "";
		int page = 1;
		while (true) {
			// repo is trusted config and contains a slash, so inline it rather than templating
			// (a path variable would be URL-encoded and break the path).
			JsonNode arr = this.rest.get()
				.uri("/repos/" + this.props.repo() + "/issues?" + baseParams + sinceParam + "&per_page={ps}&page={p}",
						pageSize, page)
				.retrieve()
				.body(JsonNode.class);
			if (arr == null || !arr.isArray() || arr.isEmpty()) {
				break;
			}
			arr.forEach(all::add);
			if (arr.size() < pageSize) {
				break;
			}
			page++;
		}
		return all;
	}

}
