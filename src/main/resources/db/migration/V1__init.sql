-- pgvector enabled now so MVP 4 (embeddings/clustering) needs no infra change.
create extension if not exists vector;

-- Raw GitHub facts. Issues and PRs are both stored here; a PR is a GitHub issue
-- with a pull_request field, so `kind` distinguishes them.
create table gh_item (
    number          integer primary key,
    kind            varchar(8)  not null,               -- issue | pr
    title           text        not null,
    body            text,
    state           varchar(16) not null,
    author          varchar(255),
    author_assoc    varchar(32),
    created_at      timestamptz,
    updated_at      timestamptz,
    closed_at       timestamptz,
    comments_count  integer     not null default 0,
    reactions_total integer     not null default 0,
    labels          jsonb       not null default '[]'::jsonb,
    url             text,
    pr_draft        boolean,
    pr_merge_state  varchar(32),
    content_hash    varchar(64),
    last_synced_at  timestamptz not null default now()
);

create index idx_gh_item_kind  on gh_item (kind);
create index idx_gh_item_state on gh_item (state);

-- Links between items. GH-native (closes/references, deterministic) and, from MVP 4,
-- AI-suggested duplicate/related candidates. No FK to gh_item: a link may reference an
-- item outside our open-issue snapshot (e.g. a PR that closes an already-closed issue).
create table item_link (
    id          bigserial   primary key,
    from_number integer     not null,
    to_number   integer     not null,
    type        varchar(24) not null,                   -- closes | references | duplicate_candidate | related
    source      varchar(16) not null,                   -- gh_reference | embedding
    confidence  double precision,
    confirmed   boolean     not null default false,
    decided_by  varchar(255),
    decided_at  timestamptz,
    created_at  timestamptz not null default now(),
    unique (from_number, to_number, type)
);

create index idx_item_link_from on item_link (from_number);
create index idx_item_link_to   on item_link (to_number);

-- AI-suggested classification. Every field here is a model interpretation of the item
-- text (marked as suggested in the UI); keyed by content_hash so unchanged items are
-- never re-classified.
create table classification (
    item_number      integer     primary key references gh_item (number) on delete cascade,
    type             varchar(24),
    area             varchar(64),
    providers        jsonb       not null default '[]'::jsonb,
    vector_stores    jsonb       not null default '[]'::jsonb,
    severity         varchar(16),
    good_first_issue boolean,
    summary          text,
    model_used       varchar(64),
    content_hash     varchar(64),
    classified_at    timestamptz not null default now()
);

create index idx_classification_type on classification (type);
create index idx_classification_area on classification (area);

-- Incremental-sync cursor (used by the live scheduler in MVP 5).
create table sync_state (
    id                integer     primary key default 1,
    cursor_updated_at timestamptz,
    last_run_at       timestamptz,
    constraint sync_state_singleton check (id = 1)
);

insert into sync_state (id) values (1) on conflict do nothing;
