-- MVP 6: dual-model classification. One classification row per (item, model) so a second
-- model's pass (Sonnet) can sit alongside the Haiku baseline for comparison. Existing Haiku
-- rows are untouched beyond backfilling model_used where it was null.
update classification set model_used = 'claude-haiku-4-5' where model_used is null;
alter table classification alter column model_used set not null;
alter table classification drop constraint classification_pkey;
alter table classification add primary key (item_number, model_used);
