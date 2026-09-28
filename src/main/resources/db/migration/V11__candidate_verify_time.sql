-- V11: время отправки проверочного парсинга кандидата ниши (автодобор).
-- Без результата дольше inflight-timeout проверка считается потерянной.
ALTER TABLE niche_candidate ADD COLUMN verify_requested_at timestamptz;