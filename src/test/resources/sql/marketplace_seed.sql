-- Справочник маркетплейсов должен быть заполнен ДО подъёма контекста:
-- RedisResultListener читает его в @PostConstruct и поднимает потоки
-- по числу найденных площадок. На чистой базе Testcontainers справочник
-- пуст, слушатель не стартует, и результаты от двойника парсера принимать
-- некому. Скрипт выполняется до теста и снимает эту зависимость.
INSERT INTO marketplace (title) VALUES ('ozon') ON CONFLICT (title) DO NOTHING;
INSERT INTO geo (title) VALUES ('Санкт-Петербург') ON CONFLICT (title) DO NOTHING;
INSERT INTO category (title) VALUES ('clothing') ON CONFLICT (title) DO NOTHING;