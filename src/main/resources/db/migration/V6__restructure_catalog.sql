-- =====================================================================
-- V6: справочники, подкатегории, страты.
-- Переносит существующий сет одежды в новую структуру без потери данных:
-- id страт сохраняются, поэтому card.stratum_id не меняется.
-- =====================================================================

-- 1. Справочники ------------------------------------------------------
CREATE TABLE marketplace (
    id    smallserial PRIMARY KEY,
    title text NOT NULL UNIQUE
);

CREATE TABLE geo (
    id    smallserial PRIMARY KEY,
    title text NOT NULL UNIQUE
);

CREATE TABLE category (
    id    smallserial PRIMARY KEY,
    title text NOT NULL UNIQUE
);

INSERT INTO marketplace (title) SELECT DISTINCT marketplace FROM set;
INSERT INTO geo (title)         SELECT DISTINCT geo         FROM set;
INSERT INTO category (title)    SELECT DISTINCT category    FROM set;

-- 2. Ссылки в set -----------------------------------------------------
ALTER TABLE set ADD COLUMN marketplace_id smallint,
                ADD COLUMN category_id    smallint,
                ADD COLUMN geo_id         smallint;

UPDATE set s SET
    marketplace_id = (SELECT id FROM marketplace m WHERE m.title = s.marketplace),
    category_id    = (SELECT id FROM category    c WHERE c.title = s.category),
    geo_id         = (SELECT id FROM geo         g WHERE g.title = s.geo);

ALTER TABLE set
    ALTER COLUMN marketplace_id SET NOT NULL,
    ALTER COLUMN category_id    SET NOT NULL,
    ALTER COLUMN geo_id         SET NOT NULL,
    ADD CONSTRAINT fk_set_marketplace FOREIGN KEY (marketplace_id) REFERENCES marketplace(id),
    ADD CONSTRAINT fk_set_category    FOREIGN KEY (category_id)    REFERENCES category(id),
    ADD CONSTRAINT fk_set_geo         FOREIGN KEY (geo_id)         REFERENCES geo(id);

-- 3. Новые таблицы ----------------------------------------------------
CREATE TABLE subcategory (
    id     uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    set_id uuid NOT NULL REFERENCES set(id) ON DELETE CASCADE,
    title  text NOT NULL,
    attrs  jsonb NOT NULL DEFAULT '{}'::jsonb,
    UNIQUE (set_id, title, attrs)
);

CREATE INDEX idx_subcategory_attrs ON subcategory USING gin (attrs);
CREATE INDEX idx_subcategory_set   ON subcategory (set_id);

CREATE TABLE stratum (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    subcategory_id uuid NOT NULL REFERENCES subcategory(id) ON DELETE CASCADE,
    item           text NOT NULL,
    query          text NOT NULL,
    count          integer NOT NULL CHECK (count > 0),
    is_seasonal    boolean NOT NULL DEFAULT false,
    base_share     numeric(4,3) CHECK (base_share IS NULL OR (base_share >= 0 AND base_share <= 1)),
    CONSTRAINT stratum_seasonal_share CHECK (NOT is_seasonal OR base_share IS NOT NULL),
    UNIQUE (subcategory_id, item)
);

CREATE INDEX idx_stratum_subcategory ON stratum (subcategory_id);

CREATE TABLE category_attribute (
    id             smallserial PRIMARY KEY,
    category_id    smallint NOT NULL REFERENCES category(id) ON DELETE CASCADE,
    key            text NOT NULL,
    title          text NOT NULL,
    value_type     text NOT NULL CHECK (value_type IN ('text','int','bool')),
    allowed_values text[],
    is_required    boolean NOT NULL DEFAULT true,
    UNIQUE (category_id, key)
);

-- 4. Перенос действующих страт ---------------------------------------
-- Каждая старая страта становится подкатегорией-обёрткой с одной стратой
-- внутри. Атрибуты gender/layer уходят в attrs подкатегории.
INSERT INTO subcategory (id, set_id, title, attrs)
SELECT sc.id,
       sc.set_id,
       sc.item,
       jsonb_build_object('segment', sc.gender, 'layer', sc.layer)
FROM set_clothing sc;

-- id страты = id старой записи set_clothing, поэтому card.stratum_id валиден
INSERT INTO stratum (id, subcategory_id, item, query,
                     count, is_seasonal, base_share)
SELECT sc.id,
       sc.id,
       sc.item,
       sc.query,
       sc.count,
       sc.is_seasonal,
       sc.base_share
FROM set_clothing sc;

-- 5. Переключение внешнего ключа card --------------------------------
ALTER TABLE card DROP CONSTRAINT IF EXISTS card_stratum_id_fkey;
ALTER TABLE card ADD CONSTRAINT card_stratum_id_fkey
    FOREIGN KEY (stratum_id) REFERENCES stratum(id) ON DELETE CASCADE;

-- 6. Атрибуты категории одежды ---------------------------------------
INSERT INTO category_attribute (category_id, key, title, value_type, allowed_values, is_required)
SELECT c.id, 'segment', 'Надкатегория', 'text', ARRAY['m','f'], true
FROM category c WHERE c.title = 'clothing';

INSERT INTO category_attribute (category_id, key, title, value_type, allowed_values, is_required)
SELECT c.id, 'layer', 'Слой', 'int', NULL, true
FROM category c WHERE c.title = 'clothing';

-- 7. Удаление старого -------------------------------------------------
DROP TABLE set_clothing;

ALTER TABLE set DROP COLUMN marketplace,
                DROP COLUMN category,
                DROP COLUMN geo;