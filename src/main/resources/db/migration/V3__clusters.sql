-- MVP 4: theme cluster tables. The vector_store table is created by PgVectorStore
-- initializeSchema=true at startup (Spring AI auto-config, not managed by Flyway).

create table if not exists theme_cluster (
    id         serial      primary key,
    label      text        not null,
    size       integer     not null default 0,
    created_at timestamptz not null default now()
);

-- Each gh_item belongs to at most one cluster; score is the avg intra-cluster similarity.
create table if not exists item_cluster (
    item_number integer     not null,
    cluster_id  integer     not null references theme_cluster (id) on delete cascade,
    primary key (item_number, cluster_id)
);

create index if not exists idx_item_cluster_cluster on item_cluster (cluster_id);
