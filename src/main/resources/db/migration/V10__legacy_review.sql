-- MVP 8: AI-suggested EOL-branch verdicts for regex-gated issue candidates. Separate table
-- (not classification columns) because only version-mentioning issues get rows and the scan
-- runs on its own lifecycle. Keyed by content_hash so unchanged items are never re-scanned.
create table legacy_review (
    item_number  integer     primary key references gh_item (number) on delete cascade,
    verdict      varchar(16) not null,                   -- LEGACY_ONLY | APPLIES_TO_MAIN | UNCLEAR
    evidence     text,                                   -- verbatim quote from the item text
    model_used   varchar(64) not null,
    content_hash varchar(64) not null,
    checked_at   timestamptz not null default now()
);
