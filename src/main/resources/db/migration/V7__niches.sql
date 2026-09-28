-- V7: товарные ниши.
-- niche              — задание ниши (приходит из сервиса аналитики);
--                      признаки те же, что у сета: маркетплейс, категория, гео
-- niche_stratum_hint — страты, которые предложила нейросеть
-- niche_candidate    — все кандидаты подбора с эмбеддингом и близостью (источник автодобора)
-- card.niche_id      — карточка в нише; у карточки заполнена страта, ниша или обе

-- ---------- 1. Ниша ----------
CREATE TABLE niche (
    id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    marketplace_id     smallint NOT NULL REFERENCES marketplace(id),
    category_id        smallint NOT NULL REFERENCES category(id),
    geo_id             smallint NOT NULL REFERENCES geo(id),
    external_id        uuid NOT NULL UNIQUE,   -- id ниши в сервисе аналитики
    description        text,                   -- описание от пользователя
    reference_url      text,                   -- ссылка на референс
    reference_sku      text,                   -- sku, извлечённый из ссылки
    reference_snapshot jsonb,                  -- данные карточки-референса от парсера
    search_query       text,                   -- поисковый запрос от нейросети
    target_count       integer NOT NULL,       -- сколько карточек нужно для анализа
    plan_count         integer NOT NULL,       -- сколько держать на парсинге (с запасом)
    status             text NOT NULL DEFAULT 'new',
    error              text,                   -- причина статуса failed
    reference_task_id  uuid,                   -- задача парсинга референса
    select_task_id     uuid,                   -- текущая задача подбора (первичный или автодобор)
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),
    deleted_at         timestamptz,
    CONSTRAINT niche_input_check
        CHECK (description IS NOT NULL OR reference_url IS NOT NULL),
    CONSTRAINT niche_counts_check
        CHECK (target_count > 0 AND plan_count >= target_count),
    CONSTRAINT niche_status_check
        CHECK (status IN ('new', 'reference_parsing', 'query_generation', 'selecting',
                          'ranking', 'active', 'failed', 'deleted'))
);

CREATE INDEX idx_niche_mp_cat_geo ON niche (marketplace_id, category_id, geo_id);
CREATE INDEX idx_niche_status ON niche (status);
CREATE INDEX idx_niche_reference_task ON niche (reference_task_id)
    WHERE reference_task_id IS NOT NULL;
CREATE INDEX idx_niche_select_task ON niche (select_task_id)
    WHERE select_task_id IS NOT NULL;

-- ---------- 2. Страты, предложенные нейросетью ----------
CREATE TABLE niche_stratum_hint (
    niche_id   uuid NOT NULL REFERENCES niche(id) ON DELETE CASCADE,
    stratum_id uuid NOT NULL REFERENCES stratum(id) ON DELETE CASCADE,
    PRIMARY KEY (niche_id, stratum_id)
);

-- ---------- 3. Кандидаты подбора ----------
CREATE TABLE niche_candidate (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    niche_id       uuid NOT NULL REFERENCES niche(id) ON DELETE CASCADE,
    sku            text NOT NULL,
    source         text NOT NULL,              -- search | stratum | refill_search
    card_id        uuid REFERENCES card(id) ON DELETE SET NULL,  -- если кандидат — существующая карточка
    snapshot       jsonb,                      -- данные карточки от парсера (кандидаты из выдачи)
    embed_text     text,                       -- текст, по которому посчитан эмбеддинг
    embedding      real[],
    similarity     double precision,           -- близость к референсу
    status         text NOT NULL DEFAULT 'candidate',  -- candidate | selected | unavailable
    verify_task_id uuid,                       -- задача проверочного парсинга при автодоборе
    created_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT niche_candidate_unique UNIQUE (niche_id, sku),
    CONSTRAINT niche_candidate_source_check
        CHECK (source IN ('search', 'stratum', 'refill_search')),
    CONSTRAINT niche_candidate_status_check
        CHECK (status IN ('candidate', 'selected', 'unavailable'))
);

CREATE INDEX idx_niche_candidate_verify_task ON niche_candidate (verify_task_id)
    WHERE verify_task_id IS NOT NULL;

-- ---------- 4. Карточка: страта, ниша или обе ----------
ALTER TABLE card ALTER COLUMN stratum_id DROP NOT NULL;
ALTER TABLE card ADD COLUMN niche_id uuid REFERENCES niche(id) ON DELETE CASCADE;
ALTER TABLE card ADD CONSTRAINT card_stratum_or_niche_check
    CHECK (stratum_id IS NOT NULL OR niche_id IS NOT NULL);

-- (stratum_id, sku) не проверяет карточки без страты — уникальность sku внутри ниши
CREATE UNIQUE INDEX card_niche_sku_unique ON card (niche_id, sku)
    WHERE niche_id IS NOT NULL;
CREATE INDEX idx_card_niche_id ON card (niche_id)
    WHERE niche_id IS NOT NULL;