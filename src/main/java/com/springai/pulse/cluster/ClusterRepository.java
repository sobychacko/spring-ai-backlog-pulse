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

package com.springai.pulse.cluster;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/** Persists discovered theme clusters and their item memberships. */
@Repository
public class ClusterRepository {

	private final JdbcTemplate jdbc;

	public ClusterRepository(NamedParameterJdbcTemplate jdbc) {
		this.jdbc = jdbc.getJdbcTemplate();
	}

	/** Drop and re-populate clusters (clustering is idempotent — full rebuild each run). */
	public void replaceAll(List<NewCluster> clusters) {
		this.jdbc.execute("truncate table item_cluster");
		this.jdbc.execute("delete from theme_cluster");

		for (NewCluster c : clusters) {
			var keyHolder = new GeneratedKeyHolder();
			this.jdbc.update(con -> {
				var ps = con.prepareStatement(
						"insert into theme_cluster (label, size) values (?, ?) returning id",
						new String[] { "id" });
				ps.setString(1, c.label());
				ps.setInt(2, c.members().size());
				return ps;
			}, keyHolder);
			long clusterId = ((Number) keyHolder.getKeys().get("id")).longValue();

			if (!c.members().isEmpty()) {
				var sql = new StringBuilder("insert into item_cluster (item_number, cluster_id) values ");
				for (int i = 0; i < c.members().size(); i++) {
					sql.append(i > 0 ? "," : "").append("(").append(c.members().get(i)).append(",").append(clusterId).append(")");
				}
				this.jdbc.execute(sql.toString());
			}
		}
	}

	/** Returns clusters with engagement data for the theme-map response. */
	public List<ClusterView> findAllWithEngagement() {
		return this.jdbc.query("""
				select
				    tc.id, tc.label, tc.size,
				    coalesce(sum(i.reactions_total + i.comments_count), 0)::int as total_engagement,
				    mode() within group (order by c.area) as dominant_area
				from theme_cluster tc
				join item_cluster ic on ic.cluster_id = tc.id
				join gh_item i on i.number = ic.item_number
				left join classification c on c.item_number = ic.item_number
				group by tc.id, tc.label, tc.size
				order by total_engagement desc
				""",
				(rs, n) -> new ClusterView(rs.getLong("id"), rs.getString("label"), rs.getInt("size"),
						rs.getInt("total_engagement"), rs.getString("dominant_area")));
	}

	/** Items belonging to a cluster, ordered by engagement descending. */
	public List<ClusterItemView> findItemsByClusterId(long clusterId) {
		return this.jdbc.query("""
				select i.number, i.kind, i.title, i.url,
				       i.reactions_total, i.comments_count,
				       c.type, c.area, c.severity, c.summary, c.good_first_issue
				from item_cluster ic
				join gh_item i on i.number = ic.item_number
				left join classification c on c.item_number = ic.item_number
				where ic.cluster_id = ?
				order by (i.reactions_total + i.comments_count) desc
				""",
				(rs, n) -> new ClusterItemView(
						rs.getInt("number"), rs.getString("kind"), rs.getString("title"), rs.getString("url"),
						rs.getInt("reactions_total"), rs.getInt("comments_count"),
						rs.getString("type"), rs.getString("area"), rs.getString("severity"),
						rs.getString("summary"), (Boolean) rs.getObject("good_first_issue")),
				clusterId);
	}

	public long count() {
		Long n = this.jdbc.queryForObject("select count(*) from theme_cluster", Long.class);
		return n != null ? n : 0;
	}

	public record NewCluster(String label, List<Integer> members) {
	}

	public record ClusterView(long id, String label, int size, int totalEngagement, String dominantArea) {
	}

	public record ClusterItemView(int number, String kind, String title, String url,
			int reactions, int comments, String type, String area, String severity,
			String summary, Boolean goodFirstIssue) {
	}

}
