-- PR base branch (fetched from /pulls endpoint, not available on /issues listing).
alter table gh_item add column if not exists pr_base_branch varchar(255);

-- PR review classification fields (populated for PRs only; NOT_APPLICABLE for issues).
alter table classification add column if not exists review_complexity    varchar(16);
alter table classification add column if not exists review_notes         text;
alter table classification add column if not exists main_branch_applicable varchar(16);
alter table classification add column if not exists main_branch_note     text;
