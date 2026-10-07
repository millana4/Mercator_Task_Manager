-- V12: полный набор карточки для подбора аналогов в нишах и код ТН ВЭД.
--
-- content_txt           — карточка от парсера целиком при полном разборе:
--                         все характеристики, описание, все переключатели вариантов.
-- content_txt_parsed_at — время последнего полного разбора.
-- tnved                 — код ТН ВЭД (цифры), когда Ozon его показывает.
-- Статус candidate      — кандидат ниши: карточка есть в базе, но в обход
--                         и аналитику не попадает.

ALTER TABLE card ADD COLUMN content_txt jsonb;
ALTER TABLE card ADD COLUMN content_txt_parsed_at timestamptz;
ALTER TABLE card ADD COLUMN tnved text;

ALTER TABLE card DROP CONSTRAINT card_status_check;
ALTER TABLE card ADD CONSTRAINT card_status_check
    CHECK (status IN ('active', 'stale', 'dropped', 'candidate'));