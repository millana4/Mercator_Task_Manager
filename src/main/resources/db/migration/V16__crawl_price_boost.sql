-- Учащённый обход при резком скачке цены.
-- last_price       — цена (price) последнего замера с ценой: с ней сравнивается новая.
-- boost_base_price — цена до скачка: возврат к ней снимает учащение.
-- boost_started_at — начало учащения; NULL — учащения нет.
-- Индексы не добавляются: выборки идут по небольшой таблице карточек.
ALTER TABLE card
    ADD COLUMN last_price       double precision,
    ADD COLUMN boost_base_price double precision,
    ADD COLUMN boost_started_at timestamptz;