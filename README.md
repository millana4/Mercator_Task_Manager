# Mercator Task Manager

Ядро сбора данных о товарах маркетплейсов. Принимает наборы страт (сеты) в виде CSV,
наполняет их реальными карточками через парсеры и непрерывно обходит собранные карточки,
накапливая временной ряд цен, наличия и характеристик для эконометрического анализа.
Дополнительно ведёт товарные ниши (подбор аналогов к карточке-референсу) и снимает
поисковую выдачу по стратам.

Оркеструет внешние парсеры: раздаёт задачи подбора, обхода и снимков выдачи через Redis,
принимает результаты и раскладывает их по двум базам — Postgres (структура и статусы) и
ClickHouse (временные ряды и снимки). Сейчас поддерживает Ozon, регион Санкт-Петербург.

Связанные сервисы: парсеры (`mercator_parser`, читают задачи из Redis ядра), сервис
эмбеддингов (`mercator_embedder`, контейнер `emb-app`), нейросеть Yandex AI Studio
(поисковый запрос ниши), аналитика (`mercator_analytics`, читает базы ядра и создаёт ниши
через API).

## Стек технологий

- Java 21, Spring Boot 4.1, Gradle (Kotlin DSL)
- Spring Data JPA + Hibernate — Postgres; Flyway — миграции схемы
- Spring JDBC — ClickHouse
- Spring Data Redis (Lettuce) — транспорт задач и результатов
- Jackson 3 — сериализация, snake_case-контракт с парсером
- RestClient — HTTP-клиенты парсера (синхронный путь), эмбеддера и нейросети
- Testcontainers, Awaitility, Mockito — тесты
- Docker Compose — инфраструктура (Postgres, ClickHouse, Redis) и приложение

## Структура приложения

```
src/main/java/com/mercator/taskmanager/
├── TaskmanagerApplication.java
├── contract/Ozon/              # контракты обмена с парсером
│   ├── OzonCard, OzonPrice, OzonSeller, OzonLocation, OzonVariant, OzonAspect(Value)
│   ├── StratumRequest, SelectionResponse, ExcludedCard, Collection   # подбор
│   └── ParseByIdRequest, ParseByUrlRequest, CardResponse            # обход
├── entity/                     # JPA-сущности Postgres
│   ├── MarketplaceEntity, CategoryEntity, GeoEntity, CategoryAttributeEntity
│   ├── SetEntity, SubcategoryEntity, StratumEntity, CardEntity
│   └── NicheEntity, NicheStatus, NicheCandidateEntity, NicheLlmLogEntity
├── repository/                 # репозитории Postgres (Spring Data)
├── clickhouse/                 # ClickHouse
│   ├── Measurement, MeasurementRepository          # замеры (пульс)
│   ├── CardSnapshot, CardSnapshotRepository         # снимки карточек
│   └── SearchSnapshotRow, SearchSnapshotRepository  # снимки поисковой выдачи
├── set_csv/                    # разбор CSV со стратами
│   ├── StratumCsvParser, StratumRow                 # плоский сет (одежда)
│   └── KidsStratumCsvParser, KidsStratumRow, HierarchicalSetImportService  # иерархический (детская)
├── client/OzonParserClient     # HTTP-клиент парсера (синхронный путь, отладка)
├── embedding/                  # EmbedderClient, EmbedderException — сервис эмбеддингов
├── llm/                        # YandexLlmClient, LlmResponse, LlmException — нейросеть
├── matching/                   # подбор аналогов ниши
│   ├── NicheMatchingProperties, NicheMatchingConfig  # группы видов, признаки, веса (niche-matching.yml)
│   ├── CardFeatureExtractor, CardFeatures            # признаки карточки из content_txt
│   ├── SizeNormalizer, SizeScale, TemperatureNormalizer, CompositionAnalyzer, TextNumbers
│   ├── NicheScorer                                   # итоговая схожесть
│   └── DuplicateFilter                               # отсев задвоений
├── redis/                      # Redis-транспорт
│   ├── RedisKeys, GeoTopics, MarketplaceTopics       # ключи <поток>:<tasks|results>:<мп>:<гео>
│   ├── Select*/Parse*/Search* TaskMessage, ResultMessage
│   ├── SelectTaskProducer, ParseTaskProducer, SearchTaskProducer (план прогона)
│   ├── SelectResultConsumer, ParseResultConsumer, SearchResultConsumer
│   └── RedisResultListener                           # фоновый приём результатов (BRPOP)
├── service/
│   ├── SetImportService, SetFillService, SetFillRedisService   # сет: импорт, наполнение, добор
│   ├── CardWriteService, JsonHelper                  # запись карточки в базы
│   ├── FillBatch, FillBatchRegistry, FillBatchScheduler        # партии наполнения
│   ├── CardLifecycleService                          # статусы карточек, наличие
│   ├── MonitoringScheduler                           # потоковый обход
│   ├── RefillScheduler                               # автодобор страт (13:00)
│   ├── CardContentBackfillService                    # дозаполнение content_txt
│   ├── SearchSnapshotScheduler                       # снимки выдачи по расписанию и окну
│   ├── NicheService, NicheScheduler                  # ниши: приём, удаление, продвижение по статусам
│   ├── NicheReferenceService, ReferenceUrlParser, ReferenceResolver   # референс
│   ├── NicheQueryGenerator, NicheQueryResult         # поисковый запрос (нейросеть)
│   ├── NicheSelectionService                         # пачки подбора, приём результатов
│   ├── NicheRankingService, NicheRankingStore        # схожесть и отбор
│   ├── NicheVerificationService                      # проверочный парсинг кандидатов
│   └── NicheRefillService                            # автодобор ниш
├── controller/
│   ├── SetImportController, AnalyticsController, CardContentController
│   ├── NicheController, NicheCreateRequest
│   └── SearchSnapshotController
└── config/
    ├── DataSourceConfig        # Postgres + ClickHouse
    ├── RestClientConfig        # HTTP-клиенты (snake_case)
    ├── RedisConfig             # StringRedisTemplate
    └── NicheApiKeyFilter       # межсервисный ключ для API ниш

src/main/resources/
├── application.yaml            # настройки (значения по умолчанию, переопределяются .env)
├── niche-matching.yml          # группы видов изделия, признаки и веса подбора аналогов
├── db/migration/               # миграции Postgres (Flyway), сейчас до V15
└── clickhouse/schema.sql       # эталонная схема ClickHouse

docker/clickhouse-init/         # авто-накат схемы ClickHouse при первом старте
```

## Запуск в Docker

```
cp .env.example .env    # заполнить пароли и ключи
docker compose up -d --build
```

Поднимаются `tm-postgres`, `tm-clickhouse`, `tm-redis` (6379 наружу — к нему подключаются
парсеры) и `tm-app` (8080 наружу — API). Порты Postgres и ClickHouse наружу не проброшены;
для отладки с хоста проброс раскомментируется в `docker-compose.yml`.

Весь `.env` передаётся приложению (`env_file`): заданные переменные переопределяют
`application.yaml`, незаданные не передаются — работают значения по умолчанию. Явные строки
`environment` в compose имеют приоритет. После правки `.env` — пересоздать контейнер:
`docker compose up -d --force-recreate taskmanager`.

Схема Postgres накатывается Flyway при старте. Схема ClickHouse — при **первом** старте
контейнера из `docker/clickhouse-init/` (только на пустом volume); иначе вручную:

```
docker exec -i tm-clickhouse clickhouse-client --database taskmanager \
  --password "$CLICKHOUSE_PASSWORD" --multiquery < src/main/resources/clickhouse/schema.sql
```

Для ниш нужен сервис эмбеддингов в той же сети (`EMBEDDER_URL`, по умолчанию
`http://emb-app:8000`) и ключи Yandex AI Studio (`YANDEX_API_KEY`, `YANDEX_FOLDER_ID`).

Проверка после старта — в логе `tm-app`: применённые миграции,
`Снимки выдачи: включены, расписание (МСК) {…}, окно прогона 240 мин`, `Started`.

## Эндпоинты

Сеты:

```
POST /api/v1/sets/import               # плоский CSV → сет и страты (одежда)
POST /api/v1/sets/import-hierarchical  # иерархический CSV → сет, подкатегории, страты (детская одежда)
POST /api/v1/sets/{id}/fill            # наполнить синхронно (прямой HTTP, отладка)
POST /api/v1/sets/{id}/fill-async      # наполнить через Redis (202 Accepted)
```

Товарные ниши (вызывает аналитика, межсервисный ключ `PARSER_API_KEY`):

```
POST   /api/v1/niches                  # принять нишу: external_id, связка, ссылка на референс, описание
DELETE /api/v1/niches/{externalId}     # удалить нишу
```

Служебное:

```
POST /api/v1/search-snapshots/run      # ручной прогон снимков выдачи — все задачи сразу
POST /api/v1/cards/content-backfill    # поставить в обход карточки без content_txt (полный набор)
GET  /api/v1/analytics/cards/{cardId}/measurements   # временной ряд замеров карточки
GET  /api/v1/analytics/strata/{stratumId}/cards      # карточки страты
GET  /actuator/health
```

Пример загрузки:

```
curl -X POST http://localhost:8080/api/v1/sets/import \
  -F "file=@set.csv" -F "marketplace=ozon" -F "category=clothing" -F "geo=Санкт-Петербург"
```

## Формат CSV

Плоский сет (одежда): разделитель — запятая, UTF-8, с заголовком. По именам колонок берутся
гендер (Ж/М), слой («0 нательный» → 0), предмет одежды, кол-во SKU в страте, сезонный сплит
(TRUE/FALSE), доля базы (у сезонных), поисковый запрос. Остальные колонки игнорируются.

Иерархический сет (детская одежда) — подкатегории с сегментом и слоем, внутри них страты;
разбирает `KidsStratumCsvParser`. Одна загрузка = новый сет.

## Работа через Redis

Ключи списков: `<поток>:<tasks|results>:<маркетплейс>:<гео>`, сейчас маркетплейс `ozon`,
гео `spb`.

| Поток          | Задачи (пишет ядро)      | Результаты (читает ядро)   |
|----------------|--------------------------|----------------------------|
| Подбор         | `select:tasks:ozon:spb`  | `select:results:ozon:spb`  |
| Снимки выдачи  | `search:tasks:ozon:spb`  | `search:results:ozon:spb`  |
| Обход          | `parse:tasks:ozon:spb`   | `parse:results:ozon:spb`   |

Ядро кладёт задачи `LPUSH`, парсеры забирают `BRPOP` в порядке приоритета: подбор → выдача →
обход. Все парсеры читают общие списки — конкурентный пул: заблокированный или стоящий на
паузе парсер просто не берёт задачи, остальные продолжают.

План прогона снимков выдачи — отсортированное множество `search:planned` (вес — время
отправки, мс); раз в минуту созревшие задачи перекладываются в `search:tasks`.

Приём результатов — `RedisResultListener`: на каждый поток свой фоновый тред с `BRPOP`.
Сообщения — UTF-8 JSON в snake_case; эхо-поля (`task_id`, `set_id`, `stratum_id`, `card_id`,
`niche_id`, `geo`) привязывают результат к задаче; результат несёт `parser_id`. Битые
сообщения логируются и пропускаются.

Задача, взятая парсером, при его остановке пропадает: обход переотправляется по окну ожидания
(45 мин), пачка подбора ниши — по `select-timeout-hours`.

## Непрерывный обход

`MonitoringScheduler` держит в `parse:tasks` буфер задач (`queue-buffer`) и доливает его каждые
`refill-interval-ms`: берёт активные карточки, которых дольше всех не мерили
(`last_measured_at ASC NULLS FIRST`), исключая уже отправленные (`last_enqueued_at` свежее
окна ожидания), и помечает `last_enqueued_at`. Результат не пришёл за
`inflight-timeout-minutes` (45) — карточка снова кандидат.

Медленные категории (`slow-categories`, сейчас `clothing`) попадают в обход не чаще раза в
`slow-min-interval-hours` (24) — данные собираются, но реже.

## Наблюдаемость

- Длина очередей: `redis-cli LLEN parse:tasks:ozon:spb`, план выдачи — `ZCARD search:planned`.
- Счётчики парсеров (пишут сами парсеры): `parser:<id>:ozon:done`, `antibot` (капчи),
  `load_failed` (сбои загрузки), `search_done`, `search_antibot`.
- Состояние бана парсера: `HGETALL parser:<id>:ozon:ban` (уровень, до какого времени пауза,
  причина, поколение устройства).
- Свежесть замеров по парсерам — `measurement.parser_id` в ClickHouse.

## Учёт партий наполнения

Наполнение сета — партия: известно, сколько задач отправлено и сколько вернулось. Закрывается,
когда пришли все результаты или истёк `fill.batch-timeout` (4 ч). Новый цикл наполнения сета не
стартует, пока открыта партия. Состояние партий — в памяти (`FillBatchRegistry`), перезапуск
теряет только знание о завершённости, не данные.

## Жизненный цикл карточки

Статус — `card.status`, наличие — `card.out_of_stock_since`, неудачи — `card.failed_attempts`.

- `active` — в наборе, её обходят;
- `stale` — обход не удался, счётчик неудач растёт (порог `card.max-failed-attempts`, 5);
- `dropped` — выбыла;
- `candidate` — карточка-кандидат ниши до принятия (в обход не идёт).

Переходы (`CardLifecycleService`): успешный обход в наличии → `active`; товар пропал →
`out_of_stock_since`, не вернулся за `card.notify-grace-days` (7) → `dropped`; 404 → сразу
`dropped`; стабильная реальная ошибка → после порога `dropped`. Блокировки антибота
(`antibot_blocked`, в том числе сбои загрузки) счётчик неудач не увеличивают.

## Автодобор страт

`RefillScheduler` ежедневно в 13:00 МСК считает дефицит по стратам (`count − active`) и
отправляет задачи подбора замены с `exclude` — уже собранными SKU сета. Флаг
`taskmanager.refill.enabled`.

## Полный набор контента

`card.content_txt` (jsonb) — полный разбор карточки: название, характеристики целиком,
описание. Нужен подбору аналогов ниш. Заполняется обходом с пометкой `full_content` в задаче;
недостающие ставит в обход `POST /api/v1/cards/content-backfill`.

## Снимки поисковой выдачи

`SearchSnapshotScheduler` по расписанию (`search-snapshot.schedule`, время МСК) снимает
выдачу по поисковому запросу каждой страты. Задачи прогона раскладываются на окно
`spread-minutes` (240): окно делится на слоты по числу задач, в каждом — случайная точка;
так выдача идёт вперемешку с обходом, без плотной серии. `spread-minutes: 0` и ручной
`POST /api/v1/search-snapshots/run` отправляют все задачи сразу.

Результат — плитки с позициями — пишется в ClickHouse `search_snapshot` (`run_at` — время
прогона, `parsed_at` — время страницы).

## Товарные ниши

Ниша — набор карточек-аналогов к карточке-референсу в связке маркетплейс / категория / гео.
Создаёт её аналитика по запросу пользователя (`external_id` = id ниши в аналитике).
**Ссылка на референс обязательна**, описание — по желанию: без ссылки API отвечает 400,
в базе действует ограничение `reference_url IS NOT NULL OR status = 'deleted'` (V15).

Статусы (`NicheScheduler`, тик `scheduler-interval-ms`):

- `new` → парсинг референса (`reference_parsing`); ссылки нет — `failed`;
- `reference_parsing` → референс разобран → `query_generation`; недоступен — `failed`;
- `query_generation` → нейросеть составляет поисковый запрос → `selecting`;
  после `llm-max-attempts` неудач — `failed`; обращения пишутся в `niche_llm_log`;
- `selecting` → пачка подбора (до `select-batch-size` кандидатов) из выдачи и страт;
  результат не пришёл за `select-timeout-hours` — повтор; ошибка подбора — повтор через
  `select-retry-delay-minutes`;
- `ranking` → схожесть новых кандидатов, отбор; набрано `target-count` — `active`,
  иначе снова `selecting`;
- `active` → карточки ниши в обходе; автодобор раз в `refill-interval-hours`;
- `deleted` — удалена (общие с другими нишами карточки остаются у них).

Таблицы: `niche`, `niche_card` (состав), `niche_candidate` (кандидаты: схожесть,
`score_details`, `reject_reason`, `analog_sku`), `niche_llm_log`.

## Подбор аналогов

Настройки — `niche-matching.yml` (25 групп вида изделия, признаки групп, веса).

1. **Жёсткий фильтр:** группа вида изделия (по «Тип» или первому слову названия) и пол
   должны совпасть с референсом; иначе кандидат отсеивается с `reject_reason`.
2. **Текст для эмбеддинга** — признаки группы, заполненные у референса (из `content_txt`);
   эмбеддинг e5, косинус растягивается на шкалу 0,90–0,99.
3. **Числовые признаки:** цена за штуку (у многопака — за вещь), размер, доля натуральных
   волокон, температурный режим.
4. **Итог:** веса 0,55 текст / 0,15 цена / 0,10 размер / 0,10 состав / 0,10 температура;
   разложение — в `niche_candidate.score_details`.
5. **Отсев задвоений** (`DuplicateFilter`): та же модель, дубль по тексту при цене ±5%.
6. **Размер:** если у кандидата нет нужного размера, проверочным парсингом
   (`NicheVerificationService`) подбирается карточка нужного размера той же модели;
   в нишу входит она (`niche_candidate.card_id`).

Кандидаты без `content_txt` в раунде пропускаются. Цена за штуку служит только для
схожести и нигде не показывается.

## Хранилища

- **Postgres** — справочники, сеты, подкатегории, страты, карточки (статусы, `content_txt`),
  ниши. Схема — Flyway (`db/migration`).
- **ClickHouse** — `measurement` (замеры цен и наличия), `card_snapshot` (характеристики,
  варианты, эмбеддинг), `search_snapshot` (снимки выдачи).

Связь между базами — по `card_id` (UUID из Postgres) и артикулу. Аналитика читает Postgres
ядра под ролью `analytics_reader`.

## Резервные копии

Данные лежат в Docker volumes (`pg-data`, `ch-data`, `redis-data`) и переживают перезапуск.
Папка `./backups` смонтирована в контейнеры баз как `/backups` и исключена из git.

`backup.sh` (на сервере, вне git) снимает:

- `pg_*.sql` — дамп Postgres ядра; `an_pg_*.sql` — дамп Postgres аналитики;
- `ch_measurement_*.csv`, `ch_snapshot_*.csv`, `ch_search_*.csv` — таблицы ClickHouse;
- `cards_strata_*.csv` — карточки страт; `cards_content_*.jsonl` — `content_txt`.

Пароли берёт из `.env` (cron не наследует переменные сессии), копии старше 60 дней удаляет.
Запуск по cron на сервере, свежие копии забирает `pull-backups.sh` на локальный компьютер
(rsync). Настройка — в `deploy-guide.md`, часть «Автоматические резервные копии».

Ручной дамп:

```bash
cd ~/mercator_taskmanager && ./backup.sh && ls -lh backups/
```

Восстановление Postgres:

```bash
cat backups/pg_ГГГГММДД_ЧЧММ.sql | docker exec -i tm-postgres psql -U taskmanager -d taskmanager
```

## Тесты

```
./gradlew test               # модульные
./gradlew integrationTest    # интеграционные (@Tag("integration"), Testcontainers: Postgres, Redis)
```

Полный асинхронный контур наполнения проверяется двойником парсера (`FakeParserForTest`),
ниши — `NicheTestSupport` (подменённые эмбеддер и нейросеть, карточки с `content_txt`).
«Живые» тесты ClickHouse (`ClickHouse*Test`, `MeasurementSeriesTest`) требуют запущенного
compose с открытыми портами 5433 и 8123; без них падают на подключении.

## Настройки (application.yaml)

Переменные окружения — в скобках; полный перечень необязательных — в `.env.example`.

| Параметр | Назначение (по умолчанию) |
|----------|---------------------------|
| `spring.datasource.*` | Postgres (`SPRING_DATASOURCE_*`) |
| `clickhouse.*` | ClickHouse (`CLICKHOUSE_*`) |
| `spring.data.redis.*` | Redis (`SPRING_DATA_REDIS_HOST/PORT`, `REDIS_PASSWORD`) |
| `parser.ozon.base-url`, `.api-key` | парсер для синхронного пути; ключ — межсервисный (`PARSER_API_KEY`) |
| `taskmanager.geo` | регион экземпляра ядра (Санкт-Петербург) |
| `taskmanager.monitoring.queue-buffer` | буфер очереди обхода (10) |
| `taskmanager.monitoring.refill-interval-ms` | период долива (10000) |
| `taskmanager.monitoring.inflight-timeout-minutes` | окно ожидания результата обхода (45) |
| `taskmanager.monitoring.slow-categories` / `slow-min-interval-hours` | медленные категории (clothing / 24) |
| `taskmanager.redis.*-prefix` | префиксы ключей списков |
| `taskmanager.search-snapshot.enabled` | снимки выдачи (true) |
| `taskmanager.search-snapshot.schedule` | расписание, МСК (kids_clothing=06:37,18:37;clothing=18:37) |
| `taskmanager.search-snapshot.spread-minutes` | окно прогона (240) |
| `taskmanager.niche.target-count` / `plan-count` | размер ниши (30 / 40; на сервере 10 / 10) |
| `taskmanager.niche.candidate-multiplier` | запас кандидатов автодобора (2) |
| `taskmanager.niche.select-batch-size` | пачка подбора (10) |
| `taskmanager.niche.scheduler-interval-ms` | тик планировщика ниш (30000) |
| `taskmanager.niche.llm-max-attempts` / `llm-retry-delay-minutes` | нейросеть: попытки / пауза (3 / 5) |
| `taskmanager.niche.select-timeout-hours` / `select-retry-delay-minutes` | пачка подбора: ожидание / пауза после ошибки (3 / 30) |
| `taskmanager.niche.refill-interval-hours` | автодобор ниш (6) |
| `taskmanager.fill.batch-timeout` | таймаут партии наполнения (PT4H) |
| `taskmanager.card.max-failed-attempts` / `notify-grace-days` | списание карточки (5 / 7) |
| `taskmanager.refill.enabled` | автодобор страт |
| `yandex.ai.*` | нейросеть: адрес, ключ, каталог, модель (deepseek-v4-flash), таймаут |
| `embedder.url` / `timeout-seconds` | сервис эмбеддингов (http://emb-app:8000 / 600) |