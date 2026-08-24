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