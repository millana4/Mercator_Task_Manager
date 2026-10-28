-- V18: жизненный цикл сета и сезонная замена (детская одежда); задание хранится в ядре.
--
-- set.status: draft (черновик в админке, ни в чём не участвует) → collecting (идёт сбор,
--             обход и расчёт есть, на дашборде не показывается) → active (текущий сет
--             категории) → retired (выведен после переключения, история).
-- set.season_type / season_year: сезон сета («Autumn Winter 2027» = autumn_winter, 2027).
-- set.analysis_cards / min_subcategory_cards / rotation_factor: параметры задания
--             (200 / 30 / 1,35); новый сезонный сет копирует их из активного.
--             У сетов, которые админка не ведёт (одежда), пусто.
-- category_setting: какие категории меняют сет по сезону.
-- subcategory.share / tracked: доля подкатегории в категории и «Отслеживать подкатегорию».
-- stratum.query_share: доля запроса в подкатегории; analysis_quota: квота анализа
--             категории; select_requested_at: последняя задача подбора по страте.
-- stratum.count >= 0: страта с нулевым количеством хранится как часть задания,
--             но не подбирается и не идёт в выдачу и анализ.

-- ---------- 1. Сет ----------
ALTER TABLE set
    ADD COLUMN status                text NOT NULL DEFAULT 'active',
    ADD COLUMN season_type           text,
    ADD COLUMN season_year           smallint,
    ADD COLUMN analysis_cards        integer,
    ADD COLUMN min_subcategory_cards integer,
    ADD COLUMN rotation_factor       numeric(4,2),
    ADD COLUMN collecting_at         timestamptz,
    ADD COLUMN activated_at          timestamptz,
    ADD COLUMN retired_at            timestamptz,
    ADD CONSTRAINT set_status_check
        CHECK (status IN ('draft', 'collecting', 'active', 'retired')),
    ADD CONSTRAINT set_season_check
        CHECK ((season_type IS NULL AND season_year IS NULL)
            OR (season_type IN ('spring_summer', 'autumn_winter') AND season_year IS NOT NULL)),
    ADD CONSTRAINT set_params_check
        CHECK ((analysis_cards IS NULL OR analysis_cards > 0)
           AND (min_subcategory_cards IS NULL OR min_subcategory_cards > 0)
           AND (rotation_factor IS NULL OR rotation_factor >= 1));

UPDATE set SET activated_at = created_at;

-- ---------- 2. Категории с сезонной заменой ----------
CREATE TABLE category_setting (
    category_id     smallint PRIMARY KEY REFERENCES category(id) ON DELETE CASCADE,
    seasonal_switch boolean NOT NULL DEFAULT false
);

INSERT INTO category_setting (category_id, seasonal_switch)
SELECT id, true FROM category WHERE title = 'kids_clothing';

-- ---------- 3. Подкатегория и страта ----------
ALTER TABLE subcategory
    ADD COLUMN share   numeric,
    ADD COLUMN tracked boolean NOT NULL DEFAULT false,
    ADD CONSTRAINT subcategory_share_check CHECK (share IS NULL OR (share > 0 AND share <= 1));

ALTER TABLE stratum
    ADD COLUMN query_share         numeric,
    ADD COLUMN analysis_quota      integer,
    ADD COLUMN select_requested_at timestamptz,
    ADD CONSTRAINT stratum_query_share_check
        CHECK (query_share IS NULL OR (query_share > 0 AND query_share <= 1)),
    ADD CONSTRAINT stratum_analysis_quota_check
        CHECK (analysis_quota IS NULL OR analysis_quota >= 0);

ALTER TABLE stratum DROP CONSTRAINT IF EXISTS stratum_count_check;
ALTER TABLE stratum ADD CONSTRAINT stratum_count_check CHECK (count >= 0);

-- ---------- 4. Ускоряющие индексы не нужны ----------
DROP INDEX IF EXISTS idx_stratum_subcategory;
DROP INDEX IF EXISTS idx_subcategory_attrs;
DROP INDEX IF EXISTS idx_subcategory_set;

-- ---------- 5. Летний сет детской одежды: сезон, параметры, задание ----------
-- Данные — из represent_set_kids_clothes.csv (Spring Summer 2026).
-- Квота анализа = round_half_up(доля подкатегории × 200 × доля запроса).
-- На тестовой базе сета детской одежды нет — обновления ничего не меняют.
UPDATE set s
SET season_type = 'spring_summer', season_year = 2026,
    analysis_cards = 200, min_subcategory_cards = 30, rotation_factor = 1.35
FROM category c
WHERE c.id = s.category_id AND c.title = 'kids_clothing';

UPDATE subcategory sc
SET share = v.share, tracked = v.tracked
FROM (VALUES
    ('f', 0, 'Носки и колготки', 0.007890035632, false),
    ('f', 0, 'Нижнее белье', 0.007890035632, false),
    ('f', 0, 'Домашняя одежда', 0.07130290637, true),
    ('f', 1, 'Пляжная одежда', 0.008822927217, false),
    ('f', 3, 'Верхняя одежда', 0.04805479316, true),
    ('f', 1, 'Блузы и рубашки', 0.01563219801, true),
    ('f', 1, 'Футболки и лонгсливы', 0.0950121283, true),
    ('f', 1, 'Майки и топы', 0.01676684617, false),
    ('f', 2, 'Джемперы и кардиганы', 0.01300804546, false),
    ('f', 2, 'Толстовки и свитшоты', 0.03802904871, true),
    ('f', 2, 'Пиджаки и жакеты', 0.01603572364, false),
    ('f', 1, 'Брюки и джинсы', 0.04046584737, true),
    ('f', 1, 'Шорты', 0.01993093975, false),
    ('f', 1, 'Комбинезоны и полукомбинезоны', 0.01181567969, false),
    ('f', 1, 'Комплекты и костюмы', 0.03085366678, false),
    ('f', 1, 'Юбки', 0.01212944768, false),
    ('f', 1, 'Платья и сарафаны', 0.03904757155, true),
    ('m', 3, 'Верхняя одежда', 0.04178665832, true),
    ('m', 0, 'Нижнее белье', 0.005716844009, false),
    ('m', 0, 'Носки и колготки', 0.005716844009, false),
    ('m', 1, 'Брюки, джинсы и шорты', 0.0470896713, true),
    ('m', 0, 'Домашняя одежда', 0.0646258755, true),
    ('m', 2, 'Джемперы и кардиганы', 0.007814226551, false),
    ('m', 1, 'Рубашки', 0.01121912411, true),
    ('m', 2, 'Толстовки и свитшоты', 0.04633390763, true),
    ('m', 1, 'Футболки', 0.101897684, true),
    ('m', 1, 'Майки', 0.003151474762, false),
    ('m', 2, 'Пиджаки и жилеты', 0.01598166134, false),
    ('m', 2, 'Комплекты и костюмы', 0.01065444089, false),
    ('m', 2, 'Комбинезоны и полукомбинезоны', 0.004431485149, false),
    ('m', 1, 'Плавки и пляжная одежда', 0.004772142402, false),
    ('baby', 1, 'Боди и песочники', 0.01483007612, false),
    ('baby', 1, 'Ползунки', 0.004943358708, false),
    ('baby', 3, 'Верхняя одежда', 0.01359653463, false),
    ('baby', 0, 'Нижнее белье', 0.006797727971, false),
    ('baby', 1, 'Футболки и рубашки', 0.02071440772, false),
    ('baby', 0, 'Пинетки', 0.007741684089, false),
    ('baby', 1, 'Брюки и шорты', 0.01907117119, false),
    ('baby', 2, 'Комбинезоны и полукомбинезоны', 0.02445793379, false),
    ('baby', 1, 'Костюмы и комплекты', 0.01489023373, false),
    ('baby', 1, 'Платья и юбки', 0.007682913209, false)
) AS v(segment, layer, title, share, tracked),
     set s, category c
WHERE sc.set_id = s.id AND c.id = s.category_id AND c.title = 'kids_clothing'
  AND sc.title = v.title
  AND sc.attrs->>'segment' = v.segment
  AND (sc.attrs->>'layer')::int = v.layer;

UPDATE stratum st
SET query_share = v.query_share, analysis_quota = v.quota
FROM (VALUES
    ('f', 0, 'Носки и колготки', 'Носки', 0.5, 1),
    ('f', 0, 'Носки и колготки', 'Колготки', 0.5, 1),
    ('f', 0, 'Нижнее белье', 'Трусы', 0.8, 1),
    ('f', 0, 'Домашняя одежда', 'Пижама', 1, 14),
    ('f', 1, 'Пляжная одежда', 'Купальник', 1, 2),
    ('f', 3, 'Верхняя одежда', 'Куртка', 0.7, 7),
    ('f', 3, 'Верхняя одежда', 'Пуховик', 0.3, 3),
    ('f', 1, 'Блузы и рубашки', 'Блузка', 0.7, 2),
    ('f', 1, 'Блузы и рубашки', 'Рубашка', 0.3, 1),
    ('f', 1, 'Футболки и лонгсливы', 'Футболка', 0.8, 15),
    ('f', 1, 'Футболки и лонгсливы', 'Лонгслив', 0.2, 4),
    ('f', 1, 'Майки и топы', 'Майка', 1, 3),
    ('f', 2, 'Джемперы и кардиганы', 'Джемпер', 1, 3),
    ('f', 2, 'Толстовки и свитшоты', 'Свитшот', 0.6, 5),
    ('f', 2, 'Толстовки и свитшоты', 'Толстовка', 0.2, 2),
    ('f', 2, 'Толстовки и свитшоты', 'Худи', 0.2, 2),
    ('f', 2, 'Пиджаки и жакеты', 'Пиджак', 0.5, 2),
    ('f', 2, 'Пиджаки и жакеты', 'Жилет', 0.5, 2),
    ('f', 1, 'Брюки и джинсы', 'Брюки', 0.7, 6),
    ('f', 1, 'Брюки и джинсы', 'Джинсы', 0.3, 2),
    ('f', 1, 'Шорты', 'Шорты', 1, 4),
    ('f', 1, 'Комбинезоны и полукомбинезоны', 'Комбинезон', 1, 2),
    ('f', 1, 'Комплекты и костюмы', 'Костюм', 0.2, 1),
    ('f', 1, 'Комплекты и костюмы', 'Комплект одежды', 0.8, 5),
    ('f', 1, 'Юбки', 'Юбка', 1, 2),
    ('f', 1, 'Платья и сарафаны', 'Платье', 1, 8),
    ('m', 3, 'Верхняя одежда', 'Куртка', 0.7, 6),
    ('m', 3, 'Верхняя одежда', 'Пуховик', 0.3, 3),
    ('m', 0, 'Нижнее белье', 'Трусы', 1, 1),
    ('m', 0, 'Носки и колготки', 'Носки', 1, 1),
    ('m', 1, 'Брюки, джинсы и шорты', 'Брюки', 0.5, 5),
    ('m', 1, 'Брюки, джинсы и шорты', 'Джинсы', 0.2, 2),
    ('m', 1, 'Брюки, джинсы и шорты', 'Шорты', 0.3, 3),
    ('m', 0, 'Домашняя одежда', 'Пижама', 1, 13),
    ('m', 2, 'Джемперы и кардиганы', 'Джемпер', 1, 2),
    ('m', 1, 'Рубашки', 'Рубашка', 1, 2),
    ('m', 2, 'Толстовки и свитшоты', 'Свитшот', 0.6, 6),
    ('m', 2, 'Толстовки и свитшоты', 'Толстовка', 0.2, 2),
    ('m', 2, 'Толстовки и свитшоты', 'Худи', 0.2, 2),
    ('m', 1, 'Футболки', 'Футболка', 0.8, 16),
    ('m', 1, 'Футболки', 'Лонгслив', 0.2, 4),
    ('m', 1, 'Майки', 'Майка', 1, 1),
    ('m', 2, 'Пиджаки и жилеты', 'Пиджак', 0.5, 2),
    ('m', 2, 'Пиджаки и жилеты', 'Жилет', 0.5, 2),
    ('m', 2, 'Комплекты и костюмы', 'Комплект одежды', 1, 2),
    ('m', 2, 'Комбинезоны и полукомбинезоны', 'Комбинезон', 1, 1),
    ('m', 1, 'Плавки и пляжная одежда', 'Плавки', 1, 1),
    ('baby', 1, 'Боди и песочники', 'Боди', 1, 3),
    ('baby', 1, 'Ползунки', 'Ползунки', 1, 1),
    ('baby', 3, 'Верхняя одежда', 'Конверт', 1, 3),
    ('baby', 0, 'Нижнее белье', 'Носки', 1, 1),
    ('baby', 1, 'Футболки и рубашки', 'Футболка', 1, 4),
    ('baby', 0, 'Пинетки', 'Пинетки', 1, 2),
    ('baby', 1, 'Брюки и шорты', 'Шорты', 1, 4),
    ('baby', 2, 'Комбинезоны и полукомбинезоны', 'Комбинезон', 1, 5),
    ('baby', 1, 'Костюмы и комплекты', 'Комплект', 1, 3),
    ('baby', 1, 'Платья и юбки', 'Платье', 1, 2)
) AS v(segment, layer, subcategory, item, query_share, quota),
     subcategory sc, set s, category c
WHERE st.subcategory_id = sc.id AND sc.set_id = s.id
  AND c.id = s.category_id AND c.title = 'kids_clothing'
  AND sc.title = v.subcategory
  AND sc.attrs->>'segment' = v.segment
  AND (sc.attrs->>'layer')::int = v.layer
  AND st.item = v.item;

-- Страта задания с нулевым количеством (2 × 0,2 = 0,4 → 0): при импорте не создавалась.
INSERT INTO stratum (subcategory_id, item, query, count, is_seasonal, base_share,
                     query_share, analysis_quota)
SELECT sc.id, 'Бюстгальтер', 'Бюстгальтер для девочки', 0, false, NULL, 0.2, 0
FROM subcategory sc
JOIN set s ON s.id = sc.set_id
JOIN category c ON c.id = s.category_id AND c.title = 'kids_clothing'
WHERE sc.title = 'Нижнее белье'
  AND sc.attrs->>'segment' = 'f'
  AND (sc.attrs->>'layer')::int = 0
ON CONFLICT (subcategory_id, item) DO NOTHING;