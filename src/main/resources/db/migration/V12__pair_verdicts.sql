-- Duplicates round 2: an AI adjudication verdict per embedding candidate pair. The cosine
-- similarity that generates candidates measures topical closeness, not "asks for the same
-- thing", so a Haiku pass reads both items and judges DUPLICATE | RELATED | DISTINCT with a
-- one-line rationale. AI-suggested like everything else — the human decision columns
-- (confirmed/decided_at) remain the authority and are set only via the admin decide endpoint.
alter table item_link add column verdict           varchar(16);
alter table item_link add column verdict_rationale text;
alter table item_link add column verdict_model     varchar(64);
alter table item_link add column verdict_at        timestamptz;
