# Spring AI Backlog Pulse

An AI-powered intelligence dashboard over the [Spring AI](https://github.com/spring-projects/spring-ai)
GitHub backlog — ~1,400 open issues and PRs, most of them untriaged, turned into a live,
navigable picture of what the community is struggling with and where a maintainer can add the
most value.

Built **with** Spring AI, **about** Spring AI: the app dogfoods the framework's core APIs
(ChatClient structured output, local ONNX embeddings, pgvector similarity search) against its
own backlog.

<!-- screenshots: theme map + backlog pulse are the money shots
![Theme map](docs/theme-map.png)
![Backlog pulse](docs/pulse.png)
-->

## Why

GitHub's native tools can't answer "what is the community struggling with?" for this repo:
~76% of issues sit in `status: waiting-for-triage`, fewer than 10% carry a type label, and
area/provider labels are single-digit. The signal is in the *content* of the issues — so this
app derives structure from content with an LLM, and keeps every number grounded in GitHub
facts.

## The grounding rule (non-negotiable)

**The AI interprets; it never counts.**

- Every number and every ranking — counts, facet distributions, engagement, recency,
  staleness, pulse and value scores — is computed **deterministically in SQL** over raw
  GitHub data. The LLM contributes zero numbers to any ranking.
- The LLM is bounded to labeling existing content: type/area/provider tags, a faithful
  one-line summary, cluster names, and similarity candidates. Every AI-derived field is
  visibly marked **AI-suggested** in the UI and links back to its GitHub source.
- The app is strictly **read-only toward GitHub**. It surfaces candidates (duplicate pairs,
  suggested labels); humans act on GitHub, and the app observes the result on the next sync.

## Views

| Tab | What it answers |
|---|---|
| Overview | Backlog size, age histogram, % triaged, type/severity breakdowns |
| By Facet | Distributions by type, area, provider, vector store, enhancement kind |
| Backlog Pulse | Areas ranked by composite pulse (volume, 30-day velocity, engagement) — "what's hot" |
| Value Queue | Top issues to pick up right now, scored from pure GitHub metrics |
| Theme Map | Emergent clusters from embeddings (force graph) + area × week heatmap |
| Duplicates | AI-suggested duplicate/related pairs (issue↔issue, PR↔issue, competing PRs), read-only — resolve on GitHub, pairs clear on next sync |
| PR Review | Easy-to-review queue, inactive-branch PRs (with applies-to-main verdicts), community PRs awaiting first response, stale PRs |
| Search | Keyword search **and semantic search** — describe a problem in plain words, match by meaning; every item's detail shows its nearest semantic neighbors |

## Architecture

```
spring-ai-backlog-pulse (Spring Boot, Java 17, Maven — one jar)
 ├─ ingest/    GitHub REST sync → gh_item (+ GH-native links: Closes #N, refs)
 ├─ classify/  ChatClient.entity(IssueClassification) → classification (Claude Haiku)
 ├─ embed/     local ONNX all-mpnet embeddings → pgvector; duplicate candidate scan
 ├─ cluster/   similarity graph → connected components → LLM names each cluster
 ├─ analytics/ pulse & value scores — pure SQL over gh_item
 ├─ search/    semantic search via VectorStore.similaritySearch
 ├─ web/       REST /api/** + React SPA (Vite + Tailwind + ECharts) bundled into the jar
 └─ schedule/  optional incremental sync (off by default; manual Admin → Sync)
```

Backing store: PostgreSQL + pgvector (Flyway-migrated). Frontend is compiled into the jar by
`frontend-maven-plugin` — the Grafana model, one artifact to deploy.

### Spring AI usage tour

- **Structured output** — `ChatClient.prompt()...responseEntity(IssueClassification.class)`:
  a Java record with enums becomes a JSON schema that constrains the model's classification.
- **Plain chat** — one call per theme cluster to *name* it (membership comes from the graph),
  and one per duplicate candidate to label the relationship type.
- **Embeddings** — `spring-ai-starter-model-transformers` runs all-mpnet-base-v2 locally via
  ONNX: no second API key, $0, 768-dim vectors into `PgVectorStore`.
- **Similarity search** — `VectorStore.similaritySearch(SearchRequest…)` powers semantic
  search and per-item "similar items". (Dedup and clustering deliberately use set-based SQL
  over the stored vectors instead — the right tool for all-pairs work.)

### Model choice: measured, not assumed

Bulk classification runs on **Claude Haiku** (~$6 one-time backfill, cents/month steady
state). We tested whether Sonnet (3× the price) would do better: classified a random sample
with both models, adjudicated every disagreement against the labeling rubric, and audited a
control sample where they agreed.

Result: nearly every *systematic* difference was a **rubric-following failure, not a
capability gap** — severity caps ignored, verdicts issued for fields that didn't apply, empty
PR descriptions read as "simple change" (a blind spot **both** models shared). All of it is
now enforced deterministically in code (`ClassifyService.sanitize()`), leaving a residual
model gap of a few judgment calls per hundred items. Haiku stayed.

## Running locally

Prereqs: Java 17+, Docker, Maven. Optional but recommended: a zero-scope GitHub fine-grained
PAT (raises API rate limits).

```bash
# 1. Postgres + pgvector
docker compose up -d

# 2. Anthropic API key — in config/application.yml (gitignored), NOT an env var:
#    spring:
#      ai:
#        anthropic:
#          api-key: sk-ant-...

# 3. GitHub token (optional locally, required on any cloud host)
export GITHUB_TOKEN=$(gh auth token)   # or a fine-grained PAT

# 4. Run (first boot downloads the ~420 MB ONNX embedding model, then cached)
mvn spring-boot:run
```

Then populate, from the UI (Admin menu) or curl:

```bash
curl -X POST localhost:8080/api/backfill        # ingest + classify (~$6 one-time, Haiku)
curl -X POST localhost:8080/api/embed           # local embeddings, $0
curl -X POST localhost:8080/api/scan-duplicates # candidate pairs, ~$0
curl -X POST localhost:8080/api/cluster         # theme clusters, ~5¢
```

Open http://localhost:8080.

**Ongoing:** Admin → **Sync** (delta ingest + classify changes) whenever you like, then
Embed → Scan → Cluster to refresh the semantic layer. Everything is incremental and
idempotent; the whole routine costs ~$2–3/month run daily.

## Deploying

The app is PaaS-ready (`server.port: ${PORT:8080}`). Required environment:

| Variable | Purpose |
|---|---|
| `PULSE_ADMIN_TOKEN` | **Required in any deployment.** All state-changing `/api` calls need this as `X-Admin-Token` (enter it once via Admin → Set admin token). Unset = unguarded local mode, with a loud startup warning. |
| `ANTHROPIC_API_KEY` | Classification (Claude Haiku) |
| `GITHUB_TOKEN` | GitHub API rate limits (zero-scope read-only PAT is enough) |
| `SPRING_DATASOURCE_URL/USERNAME/PASSWORD` | A pgvector-enabled PostgreSQL (JDBC URL) |

GET endpoints are public by design — the dashboard is read-only over public GitHub data.

## License

Apache License 2.0
