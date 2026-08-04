-- Switch embedding model from all-MiniLM-L6-v2 (384-dim) to all-mpnet-base-v2 (768-dim).
-- Dropping vector_store forces Spring AI to recreate it with the correct 768-dimension column.
-- All embeddings must be regenerated via Admin → Embed items after this migration.
drop table if exists vector_store;

-- Clear cluster tables so stale cluster memberships don't reference unembedded items.
truncate table item_cluster;
delete from theme_cluster;
