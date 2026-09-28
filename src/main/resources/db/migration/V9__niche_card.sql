-- V9: карточка может входить в несколько ниш.
-- niche_card     — состав ниш (многие ко многим).
-- card.niche_id  — ниша, для которой карточка создана; только у карточек без страты.

CREATE TABLE niche_card (
    niche_id  uuid NOT NULL REFERENCES niche(id) ON DELETE CASCADE,
    card_id   uuid NOT NULL REFERENCES card(id) ON DELETE CASCADE,
    added_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (niche_id, card_id)
);

-- Перенос текущего членства из card.niche_id
INSERT INTO niche_card (niche_id, card_id)
SELECT niche_id, id FROM card WHERE niche_id IS NOT NULL;

-- У карточек страт ниша-создатель не хранится
UPDATE card SET niche_id = NULL
WHERE niche_id IS NOT NULL AND stratum_id IS NOT NULL;

-- Вместе с card_stratum_or_niche_check: заполнено ровно одно — страта или ниша-создатель
ALTER TABLE card ADD CONSTRAINT card_niche_owner_check
    CHECK (niche_id IS NULL OR stratum_id IS NULL);

COMMENT ON COLUMN card.niche_id IS
    'Ниша, для которой карточка создана (только у карточек без страты). Состав ниш — niche_card';