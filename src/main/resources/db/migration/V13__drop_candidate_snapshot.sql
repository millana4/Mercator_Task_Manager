-- V13: кандидаты ниш — карточки со статусом candidate (card.status), данные
-- кандидата живут в card.content_txt и снимке ClickHouse. Колонка
-- niche_candidate.snapshot больше не нужна.

ALTER TABLE niche_candidate DROP COLUMN snapshot;