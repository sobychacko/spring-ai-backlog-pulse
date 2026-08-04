-- Distinguish new-capability requests from refinements of existing features (ENHANCEMENT only).
alter table classification add column enhancement_kind varchar(24);

create index idx_classification_enh_kind on classification (enhancement_kind);
