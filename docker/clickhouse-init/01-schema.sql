CREATE TABLE IF NOT EXISTS measurement
(
    card_id         UUID,
    sku             String,
    parsed_at       DateTime,
    geo             String,

    card_price      Nullable(Float64),
    price           Nullable(Float64),
    original_price  Nullable(Float64),
    quantity        Nullable(UInt32),
    rating          Nullable(Float32),
    reviews_count   Nullable(UInt32),
    parser_id       String DEFAULT ''
)
)
ENGINE = MergeTree
ORDER BY (card_id, parsed_at);

CREATE TABLE IF NOT EXISTS card_snapshot
(
    card_id            UUID,
    sku                String,
    parsed_at          DateTime,

    name               String,
    description        String,
    brand              String,
    category           String,
    category_path      String,

    characteristics    Map(String, String),
    variants_aspect    String,
    variants           String,

    seller_id          String,
    seller_name        String,
    seller_legal_name  String,
    seller_ogrn        String,

    embedding          Array(Float32)
)
ENGINE = ReplacingMergeTree(parsed_at)
ORDER BY card_id;


-- Снимки поисковой выдачи по стратам: одна строка - одна плитка выдачи.
-- run_at - плановое время прогона (общее для всех страт прогона), parsed_at - когда снял парсер.
CREATE TABLE IF NOT EXISTS search_snapshot
(
    run_at          DateTime,
    parsed_at       DateTime,
    marketplace     String,
    geo             String,
    category        String,
    set_id          UUID,
    stratum_id      UUID,
    query           String,

    position        UInt16,
    sku             String,
    name            String,
    url             String,
    brand           String,
    brand_verified  UInt8,
    price           Nullable(Float64),
    original_price  Nullable(Float64),
    discount_pct    Nullable(UInt8),
    quantity        Nullable(UInt32),
    rating          Nullable(Float32),
    reviews         Nullable(UInt32),
    badges          Array(String),
    labels          Array(String),

    parser_id       String,
    task_id         UUID
)
ENGINE = MergeTree
ORDER BY (stratum_id, run_at, position);