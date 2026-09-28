-- V8: журнал обращений к нейросети при генерации поискового запроса ниши.
-- Одна строка — одно обращение. Нужен для разбора неудачных ответов
-- и подсчёта попыток (неудачные обращения → после лимита ниша failed).

CREATE TABLE niche_llm_log (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    niche_id      uuid NOT NULL REFERENCES niche(id) ON DELETE CASCADE,
    model         text,                    -- модель, ответившая на запрос
    system_prompt text NOT NULL,
    user_prompt   text NOT NULL,
    response      text,                    -- сырой ответ модели
    tokens        integer,                 -- total_tokens (вход + выход)
    success       boolean NOT NULL,        -- ответ разобран и прошёл проверку
    error         text,                    -- причина неудачи
    created_at    timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_niche_llm_log_niche ON niche_llm_log (niche_id, created_at);