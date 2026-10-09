-- V14: новое ранжирование кандидатов ниши (подбор аналогов по группам признаков).
--
-- score_details — составляющие итоговой схожести (текст, цена, размер, доля натуральных,
--                 температура), исходный косинус, размер и цена аналога; итоговая схожесть —
--                 по-прежнему в similarity.
-- reject_reason — почему кандидат не идёт в нишу: другой вид изделия, другой пол,
--                 та же модель, дубль по тексту; null — прошёл.
-- analog_sku    — sku, который пойдёт в нишу, если у модели кандидата подставлен нужный
--                 размер; null — идёт сама карточка кандидата.

ALTER TABLE niche_candidate ADD COLUMN score_details jsonb;
ALTER TABLE niche_candidate ADD COLUMN reject_reason text;
ALTER TABLE niche_candidate ADD COLUMN analog_sku text;