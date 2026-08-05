-- Rubric-guard backfill for PR fields (found via the adjudicated Haiku-vs-Sonnet PR
-- comparison; ClassifyService now enforces both rules in code for future classifications):
--
-- 1. LOW review complexity requires a clear description — a blank/near-blank PR body cannot
--    qualify (both models share this blind spot: "no information" read as "simple").
update classification c
set review_complexity = 'MEDIUM'
from gh_item i
where i.number = c.item_number
  and i.kind = 'pr'
  and c.review_complexity = 'LOW'
  and length(trim(coalesce(i.body, ''))) < 80;

-- 2. main_branch_applicable is only meaningful for PRs targeting old/inactive branches;
--    PRs already targeting main/2.x must be NOT_APPLICABLE.
update classification c
set main_branch_applicable = 'NOT_APPLICABLE', main_branch_note = ''
from gh_item i
where i.number = c.item_number
  and i.kind = 'pr'
  and (i.pr_base_branch = 'main' or i.pr_base_branch like '2.%')
  and c.main_branch_applicable is not null
  and c.main_branch_applicable <> 'NOT_APPLICABLE';
