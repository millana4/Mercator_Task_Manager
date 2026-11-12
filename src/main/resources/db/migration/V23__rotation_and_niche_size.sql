-- ---------- V23: запас на ротацию сезонных сетов детской одежды ----------
-- Минимум на подкатегорию 30 -> 40, коэффициент ротации 1,35 -> 1,60.
-- Для расчёта подкатегории нужно не меньше 40 годных карточек (свежая цена и ряд без пропусков),
-- а годных около 70-80% состава: на парсинге около 64 карточек на отслеживаемую подкатегорию.
-- Количество страты пересчитывается по формулам AssignmentCalculator:
--   итог подкатегории = ROUND(max(доля x карточек на анализ, минимум на подкатегорию, если она
--   отслеживается) x коэффициент ротации), количество страты = ROUND(итог x доля запроса).
-- ROUND для положительных чисел - половина вверх, как в калькуляторе. Квоты анализа не меняются:
-- они не зависят от минимума и коэффициента. Выведенный (retired) сет не трогаем.
UPDATE set s
SET min_subcategory_cards = 40, rotation_factor = 1.60
FROM category c
WHERE c.id = s.category_id AND c.title = 'kids_clothing' AND s.status <> 'retired';

UPDATE stratum st
SET count = ROUND(f.total * COALESCE(st.query_share, 1))::integer
FROM (
    SELECT sc.id AS subcategory_id,
           ROUND(CASE WHEN sc.tracked
                      THEN GREATEST(sc.share * s.analysis_cards, s.min_subcategory_cards)
                      ELSE sc.share * s.analysis_cards END
                 * s.rotation_factor) AS total
    FROM subcategory sc
    JOIN set s ON s.id = sc.set_id
    JOIN category c ON c.id = s.category_id
    WHERE c.title = 'kids_clothing' AND s.status <> 'retired' AND sc.share IS NOT NULL
) f
WHERE st.subcategory_id = f.subcategory_id;

-- Ниши: плановый размер 30/34 -> 40/50 (в расчёт идут все годные карточки, нужно не меньше 40;
-- на парсинге 50 с запасом на выбывание). Значения для новых ниш - NICHE_TARGET_COUNT и
-- NICHE_PLAN_COUNT в настройках ядра. Удалённые ниши не трогаем.
UPDATE niche
SET target_count = 40, plan_count = 50
WHERE deleted_at IS NULL;