-- GitHub assignees per item (logins). Populated from the /issues listing on ingest.
alter table gh_item add column assignees jsonb not null default '[]'::jsonb;
