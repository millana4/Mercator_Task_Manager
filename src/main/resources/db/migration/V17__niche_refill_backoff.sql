-- Автодобор ниш: пауза после поисков без новых кандидатов и выбор только по поводу.
-- refill_empty_count - поисков автодобора подряд без новых кандидатов (задаёт паузу до следующего).
-- refill_choice_pending - есть что выбирать: новые кандидаты или освободились места.
ALTER TABLE niche ADD COLUMN refill_empty_count integer NOT NULL DEFAULT 0;
ALTER TABLE niche ADD COLUMN refill_choice_pending boolean NOT NULL DEFAULT false;

-- активные ниши один раз проходят выбор после наката
UPDATE niche SET refill_choice_pending = true WHERE status = 'active';