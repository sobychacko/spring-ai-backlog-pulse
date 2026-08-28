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

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import tools.jackson.databind.JsonNode;

import com.springai.pulse.config.GitHubProperties;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
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
		return fetchAll("/repos/" + this.props.repo() + "/issues?state=open&per_page=" + pageSize);
	}

	/**
	 * Fetch open PRs from the {@code /pulls} endpoint, which includes {@code base.ref} — the
	 * target branch. The {@code /issues} listing does not include this field.
	 */
	public List<JsonNode> fetchOpenPullRequests(int pageSize) {
		return fetchAll("/repos/" + this.props.repo() + "/pulls?state=open&per_page=" + pageSize);
	}

	/** Fetch a single pull request (for targeted base-branch refresh during incremental sync). */
	public JsonNode fetchPullRequest(int number) {
		return this.rest.get()
			.uri("/repos/" + this.props.repo() + "/pulls/" + number)
			.retrieve()
			.body(JsonNode.class);
	}

	/**
	 * Fetch all items (open and closed) updated at or after {@code since}, sorted ascending by
	 * update time so the caller can advance the cursor to the last item seen.
	 */
	public List<JsonNode> fetchItemsSince(String since, int pageSize) {
		String sinceParam = (since != null && !since.isBlank()) ? "&since=" + since : "";
		return fetchAll("/repos/" + this.props.repo() + "/issues?state=all&sort=updated&direction=asc" + sinceParam
				+ "&per_page=" + pageSize);
	}

	/**
	 * Follow {@code Link: <...>; rel="next"} headers instead of incrementing a {@code page}
	 * parameter — GitHub rejects page-based pagination on large result sets (422) and hands out
	 * cursor URLs in the Link header instead.
	 */
	private List<JsonNode> fetchAll(String firstUri) {
		List<JsonNode> all = new ArrayList<>();
		// repo is trusted config and contains a slash, so URIs are built by concatenation
		// (a templated path variable would be URL-encoded and break the path).
		URI next = URI.create(this.props.apiBase() + firstUri);
		String apiHost = next.getHost();
		while (next != null) {
			// the Authorization header rides along on every request — never follow a Link
			// that points off the configured API host
			if (!apiHost.equalsIgnoreCase(next.getHost())) {
				throw new IllegalStateException("Refusing to follow pagination link to foreign host: " + next);
			}
			ResponseEntity<JsonNode> resp = this.rest.get().uri(next).retrieve().toEntity(JsonNode.class);
			JsonNode arr = resp.getBody();
			if (arr == null || !arr.isArray() || arr.isEmpty()) {
				break;
			}
			arr.forEach(all::add);
			next = nextLink(resp.getHeaders());
		}
		return all;
	}

	private static URI nextLink(HttpHeaders headers) {
		List<String> linkHeaders = headers.getOrEmpty(HttpHeaders.LINK);
		for (String header : linkHeaders) {
			for (String part : header.split(",")) {
				String[] segments = part.split(";");
				if (segments.length >= 2 && segments[1].trim().equals("rel=\"next\"")) {
					String url = segments[0].trim();
					if (url.startsWith("<") && url.endsWith(">")) {
						return URI.create(url.substring(1, url.length() - 1));
					}
				}
			}
		}
		return null;
	}

}
