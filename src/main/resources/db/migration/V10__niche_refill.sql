-- V10: автодобор ниш; удаление ускоряющих индексов card.

-- Ускоряющие индексы при текущих объёмах не нужны.
-- Первичный ключ и ограничения уникальности остаются.
DROP INDEX IF EXISTS idx_card_queue;
DROP INDEX IF EXISTS idx_card_status;
DROP INDEX IF EXISTS idx_card_stratum_id;

-- Время последнего поиска кандидатов автодобора ниши
-- (поиск не чаще taskmanager.niche.refill-interval-hours).
ALTER TABLE niche ADD COLUMN refill_requested_at timestamptz;