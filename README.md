# Spring AI Backlog Pulse

A dashboard over the [Spring AI](https://github.com/spring-projects/spring-ai) GitHub backlog —
around 1,400 open issues and PRs, most of them untriaged. The app classifies them, clusters
them by theme, finds likely duplicates, and ranks where a maintainer's time is best spent.

It's built with Spring AI itself, so it doubles as a working example of the framework:
ChatClient structured output, local ONNX embeddings, pgvector, similarity search.

<!-- screenshots
![Theme map](docs/theme-map.png)
![Backlog pulse](docs/pulse.png)
-->

## Why

GitHub labels can't answer "what is the community struggling with" for this repo. Most issues
sit in `status: waiting-for-triage`, and only a small fraction carry a type or area label. The
useful signal is in the text of the issues themselves, so this app reads the text with an LLM
and derives the structure the labels never had.

## Ground rules

A few principles the whole app is built around:

- Every number and ranking (counts, engagement, pulse and value scores) comes from plain SQL
  over the raw GitHub data. The LLM never produces a number that feeds a ranking.
- The LLM only labels existing content: type/area/provider tags, a one-line summary, cluster
  names, duplicate candidates. Anything AI-derived is marked as suggested in the UI and links
  back to the GitHub item it came from.
- The app never writes to GitHub. It suggests; you act on GitHub; the next sync picks up the
  result.

## Views

| Tab | What it answers |
|---|---|
| Today's Picks | High-value open issues a maintainer could land on main in about an hour: unassigned, no open PR, no API break, nothing blocking. Effort and blockers are AI-suggested from the issue and its comment thread |
| Overview | Backlog size, age, % triaged, type/severity breakdowns |
| By Facet | Distributions by type, area, provider, vector store |
| Backlog Pulse | Areas ranked by volume, recent velocity, and engagement |
| Value Queue | Which issues are worth picking up right now |
| Theme Map | Clusters discovered from embeddings, plus an area-by-week heatmap |
| Duplicates | Likely duplicate/related pairs. Read-only: close the real duplicate on GitHub and the pair clears on the next sync |
| PR Review | Easy-to-review PRs, PRs on inactive branches, community PRs waiting on a first response, stale PRs |
| Search & Ask | Keyword and semantic search (describe the problem in plain words), plus a chat mode that answers questions about the backlog from live data |

## Architecture

```
spring-ai-backlog-pulse (Spring Boot, Java 17, Maven — single jar)
 ├─ ingest/    GitHub REST sync → gh_item (+ native links: Closes #N, refs)
 ├─ classify/  ChatClient.entity(IssueClassification) → classification
 ├─ embed/     local ONNX embeddings → pgvector; duplicate candidate scan
 ├─ cluster/   similarity graph → connected components → LLM names each cluster
 ├─ analytics/ pulse & value scores, plain SQL
 ├─ search/    semantic search via VectorStore.similaritySearch
 ├─ web/       REST /api/** + React SPA (Vite + Tailwind + ECharts), bundled into the jar
 └─ schedule/  optional incremental sync (off by default; use Admin → Sync)
```

Postgres + pgvector underneath, Flyway migrations. The frontend is compiled into the jar by
`frontend-maven-plugin`, so the whole thing deploys as one artifact.

Where Spring AI is used:

- Structured output: `ChatClient.prompt()...responseEntity(IssueClassification.class)` — a
  Java record with enums becomes the JSON schema that constrains classification.
- Plain chat: one call to name each theme cluster, one per duplicate candidate to label the
  relationship.
- Embeddings: `spring-ai-starter-model-transformers` runs all-mpnet-base-v2 locally through
  ONNX. No extra API key, no cost, 768-dim vectors into `PgVectorStore`.
- Similarity search: `VectorStore.similaritySearch(...)` powers semantic search and the
  per-item "similar items" list. Dedup and clustering query the stored vectors with SQL
  instead, since those are all-pairs jobs.

Classification runs on Claude Haiku. Model output goes through a deterministic sanitization
step before it's stored (see `ClassifyService`), and unchanged items are never re-classified —
classification is keyed by content hash.

## Running locally

You need Java 17+, Docker, and Maven. A GitHub token is optional locally but recommended
(a fine-grained PAT with read-only public repo access is plenty).

```bash
# 1. Postgres + pgvector
docker compose up -d

# 2. Anthropic API key goes in config/application.yml (gitignored), not an env var:
#    spring:
#      ai:
#        anthropic:
#          api-key: sk-ant-...

# 3. GitHub token
export GITHUB_TOKEN=$(gh auth token)

# 4. Run. First boot downloads the embedding model (~420 MB), then it's cached.
mvn spring-boot:run
```

Then populate, from the Admin menu in the UI or with curl:

```bash
curl -X POST localhost:8080/api/backfill        # ingest + classify (one-time, a few dollars)
curl -X POST localhost:8080/api/embed           # local embeddings, free
curl -X POST localhost:8080/api/scan-duplicates # candidate pairs
curl -X POST localhost:8080/api/cluster         # theme clusters, a few cents
curl -X POST localhost:8080/api/picks-assess    # today's picks, ~$2 first run (Opus)
```

Open http://localhost:8080.

Day to day: Admin → Sync pulls anything that changed on GitHub and classifies it, then
Embed → Scan → Cluster refreshes the semantic layer. Everything is incremental. Running the
whole routine daily costs a couple of dollars a month.

## Deploying

The app reads `PORT`, so it works on the usual platforms. Environment:

| Variable | Purpose |
|---|---|
| `PULSE_ADMIN_TOKEN` | Required on any deployment. All state-changing `/api` calls need it as an `X-Admin-Token` header (enter it once via Admin → Set admin token). If unset the app runs unguarded, which is only okay on your own machine. |
| `ANTHROPIC_API_KEY` | Classification and the Ask tab |
| `GITHUB_TOKEN` | GitHub API rate limits |
| `SPRING_DATASOURCE_URL/USERNAME/PASSWORD` | A pgvector-enabled Postgres (JDBC URL) |

GET endpoints are public: the dashboard is read-only over public GitHub data.

The Ask tab is metered per question, so it carries its own limits regardless of how it is
reached: a daily spend ceiling, a per-IP hourly cap, and a bound on questions answered at
once. All three are configurable under `pulse.chat`.

## License

Apache License 2.0
