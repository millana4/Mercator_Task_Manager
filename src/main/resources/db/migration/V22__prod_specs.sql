-- Признаки карточки от модели (шаг 1 рекомендаций по закупкам): одна строка на карточку.
-- input_hash — отпечаток входных данных (фото, название, описание, характеристики),
-- prompt_version — версия промпта: карточка не менялась — повторно не размечается.
-- Индексы не создаём (только PK и уникальность).
CREATE TABLE prod_specs_card (
    card_id        uuid PRIMARY KEY REFERENCES card (id) ON DELETE CASCADE,
    input_hash     text NOT NULL,
    prompt_version text NOT NULL,
    model          text NOT NULL,
    attrs          jsonb NOT NULL,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now()
);

-- Журнал обращений к нейросети: одна строка — одно обращение (успешное или нет).
-- Тексты промптов и ответов не хранятся, только метаданные и токены; цена считается
-- по токенам и тарифу. ref_type / ref_id — на что ссылается обращение (niche, sku, stratum).
CREATE TABLE llm_request_log (
    id                uuid PRIMARY KEY,
    purpose           text NOT NULL,
    model             text NOT NULL,
    prompt_tokens     integer,
    completion_tokens integer,
    total_tokens      integer,
    success           boolean NOT NULL,
    error             text,
    duration_ms       integer,
    ref_type          text,
    ref_id            text,
    created_at        timestamptz NOT NULL DEFAULT now()
);