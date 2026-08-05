-- Rubric enforcement backfill: enhancements and documentation gaps are never higher than LOW
-- severity (the classification prompt says so, but the model did not reliably honor it — the
-- dominant error pattern found in the Haiku-vs-Sonnet adjudication). ClassifyService now clamps
-- this in code for all future classifications; this fixes rows classified before the clamp.
update classification
set severity = 'LOW'
where type in ('ENHANCEMENT', 'DOCUMENTATION')
  and severity is not null
  and severity is distinct from 'LOW';
