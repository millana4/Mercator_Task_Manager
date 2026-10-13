-- Ниша создаётся только с референсом: ссылка обязательна, описание — по желанию.
-- Удалённые ниши остаются как есть: среди них могут быть созданные по одному описанию.
ALTER TABLE niche DROP CONSTRAINT niche_input_check;
ALTER TABLE niche ADD CONSTRAINT niche_input_check
    CHECK (reference_url IS NOT NULL OR status = 'deleted');