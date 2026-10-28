-- V19: карточка — товар маркетплейса (один sku — одна строка, один card_id),
-- состав страт — таблица stratum_card (многие ко многим, как niche_card у ниш).
--
-- Один товар может входить в страты разных сетов (летний и зимний, разные категории)
-- и в ниши. История замеров в ClickHouse — под одним card_id за всё время обхода.
--
-- card.marketplace_id — маркетплейс товара; уникальность (marketplace_id, sku).
-- card.status — доступность товара (active | stale | dropped | candidate), а не членство.
-- card.niche_id — ниша, для которой карточка создана (как раньше, без ограничений).
-- stratum_card.removed_at — членство закрыто: карточка выбыла из страты (добор ставит
--     замену). Вернувшийся товар (например, ради ниши) в страту не возвращается.
--
-- Дубли sku (одна строка на сет — до V19) сливаются: остаётся активная строка, при
-- равенстве — с более свежим замером; членство в стратах и нишах, кандидаты ниш
-- переходят на неё. Замеры удалённой строки остаются в ClickHouse под её card_id —
-- аналитика привязывает замеры к карточке по sku.

-- ---------- 1. Маркетплейс карточки ----------
ALTER TABLE card ADD COLUMN marketplace_id smallint REFERENCES marketplace(id);

UPDATE card c SET marketplace_id = s.marketplace_id
FROM stratum st
JOIN subcategory sc ON sc.id = st.subcategory_id
JOIN set s ON s.id = sc.set_id
WHERE st.id = c.stratum_id;

UPDATE card c SET marketplace_id = n.marketplace_id
FROM niche n
WHERE n.id = c.niche_id AND c.marketplace_id IS NULL;

ALTER TABLE card ALTER COLUMN marketplace_id SET NOT NULL;

-- ---------- 2. Состав страт ----------
CREATE TABLE stratum_card (
    stratum_id uuid NOT NULL REFERENCES stratum(id) ON DELETE CASCADE,
    card_id    uuid NOT NULL REFERENCES card(id) ON DELETE CASCADE,
    added_at   timestamptz NOT NULL DEFAULT now(),
    removed_at timestamptz,
    PRIMARY KEY (stratum_id, card_id)
);

INSERT INTO stratum_card (stratum_id, card_id, added_at, removed_at)
SELECT stratum_id, id, created_at,
       CASE WHEN status = 'dropped' THEN COALESCE(dropped_at, now()) END
FROM card
WHERE stratum_id IS NOT NULL;

-- Ограничения «страта или ниша» и уникальность sku внутри ниши-создателя больше не про
-- карточку: снимаются до слияния дублей (остающаяся строка может получить niche_id
-- удаляемой, пока та ещё существует).
ALTER TABLE card DROP CONSTRAINT card_stratum_or_niche_check;
ALTER TABLE card DROP CONSTRAINT card_niche_owner_check;
DROP INDEX IF EXISTS card_niche_sku_unique;

-- ---------- 3. Слияние дублей sku ----------
CREATE TEMP TABLE card_merge AS
SELECT id AS old_id, keep_id
FROM (SELECT id,
             first_value(id) OVER (PARTITION BY marketplace_id, sku
                                   ORDER BY (status = 'active') DESC,
                                            last_measured_at DESC NULLS LAST,
                                            created_at, id) AS keep_id
      FROM card) t
WHERE id <> keep_id;

INSERT INTO stratum_card (stratum_id, card_id, added_at, removed_at)
SELECT sm.stratum_id, m.keep_id, sm.added_at, sm.removed_at
FROM stratum_card sm
JOIN card_merge m ON m.old_id = sm.card_id
ON CONFLICT (stratum_id, card_id) DO NOTHING;

INSERT INTO niche_card (niche_id, card_id, added_at)
SELECT nc.niche_id, m.keep_id, nc.added_at
FROM niche_card nc
JOIN card_merge m ON m.old_id = nc.card_id
ON CONFLICT (niche_id, card_id) DO NOTHING;

UPDATE niche_candidate cand SET card_id = m.keep_id
FROM card_merge m
WHERE cand.card_id = m.old_id;

UPDATE card k SET niche_id = o.niche_id
FROM card_merge m
JOIN card o ON o.id = m.old_id
WHERE k.id = m.keep_id AND k.niche_id IS NULL AND o.niche_id IS NOT NULL;

-- членство удаляемых строк в стратах и нишах уходит каскадом
DELETE FROM card c USING card_merge m WHERE c.id = m.old_id;
DROP TABLE card_merge;

-- ---------- 4. Ограничения карточки ----------
ALTER TABLE card DROP CONSTRAINT card_stratum_sku_unique;
ALTER TABLE card DROP COLUMN stratum_id;
ALTER TABLE card ADD CONSTRAINT card_marketplace_sku_unique UNIQUE (marketplace_id, sku);

COMMENT ON COLUMN card.niche_id IS
    'Ниша, для которой карточка создана. Состав ниш - niche_card, состав страт - stratum_card';