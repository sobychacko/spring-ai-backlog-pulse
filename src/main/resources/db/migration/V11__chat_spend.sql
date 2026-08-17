-- MVP 9: daily chat spend ledger. Kept in the database (not in-memory) so the daily ceiling
-- survives restarts and serverless sleep/wake cycles — otherwise every cold boot would hand
-- out a fresh budget on a metered, possibly public, endpoint.
create table chat_spend (
    day       date   primary key,
    micro_usd bigint not null default 0,   -- Haiku rates: 1 µ$ per input token, 5 µ$ per output token
    turns     integer not null default 0
);
