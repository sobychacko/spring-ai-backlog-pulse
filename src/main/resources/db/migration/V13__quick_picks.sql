-- Quick picks: an AI assessment of whether a high-value open issue is a small, safe change a
-- maintainer can land on main in one sitting. Only the deterministic candidate pool (top open
-- issues by value score that pass the eligibility filters) gets rows. Keyed by content_hash
-- plus the comment count seen, so an item is re-assessed only when its text or thread changes.
-- decision/decided_at belong to the human ("took it" / "skip") and are never touched by the scan.
create table quick_pick (
    item_number    integer     primary key references gh_item (number) on delete cascade,
    effort         varchar(16) not null,               -- ABOUT_AN_HOUR | HALF_DAY | MULTI_DAY | CANNOT_TELL
    api_risk       varchar(16) not null,               -- NONE | ADDITIVE | BREAKING | CANNOT_TELL
    blockers       jsonb       not null default '[]'::jsonb,
    likely_scope   text,
    evidence       text,                               -- verbatim quote from the item text or comments
    first_step     text,
    confidence     varchar(8),                         -- LOW | MEDIUM | HIGH
    model_used     varchar(64) not null,
    content_hash   varchar(64) not null,
    comments_seen  integer     not null default 0,
    assessed_at    timestamptz not null default now(),
    decision       varchar(8),                         -- TAKEN | SKIPPED (human)
    decided_at     timestamptz
);

create index idx_quick_pick_effort on quick_pick (effort);
