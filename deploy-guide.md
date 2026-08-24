# Развёртывание с нуля: парсер + таск-менеджер

Полная пошаговая инструкция, проверенная на боевом развёртывании. Порядок важен:
сначала парсер, потом таск-менеджер + базы + Redis.

Предполагается: чистый сервер с установленными `git`, `docker`, `docker compose`.
Обе части (парсер и ядро) — на одной машине, но в двух независимых
docker-compose. Парсер общается с ядром через Redis (транспорт задач) и,
опционально, по HTTP (синхронная отладка).

---

## Общая картина

- **Таск-менеджер** (один docker-compose): Postgres + ClickHouse + Redis + приложение таск-менеджера.
- **Парсер** (отдельный docker-compose): HTTP-сервис + воркер. Воркер
  подключается к Redis ядра.
- **Связь**: воркер парсера читает задачи из Redis-очередей ядра (`BRPOP`) и
  пишет результаты обратно (`LPUSH`).

Ключевой секрет, общий для обеих частей: **межсервисный ключ**
(`PARSER_API_KEY` в ядре = `SERVICE_API_KEY` в парсере). Нужен для HTTP-доступа
к парсеру (синхронный путь). Через Redis ключ не требуется, но задать его надо
одинаковым в обоих `.env`.

---

## ЧАСТЬ 1. Подготовка

Зайти на сервер:

```bash
ssh имя-сервера@адрес-сервера
```

Проверить, что всё установлено:

```bash
git --version
docker --version
docker compose version
```

Придумать и записать три секрета. Сгенерировать случайные строки:

```bash
openssl rand -hex 24    # выполни трижды, получишь три разных значения
```

Три секрета:
- `POSTGRES_PASSWORD` — пароль Postgres (только ядро);
- `CLICKHOUSE_PASSWORD` — пароль ClickHouse (только ядро);
- `SERVICE_API_KEY` / `PARSER_API_KEY` — межсервисный ключ, **одинаковый** в
  парсере и ядре.

---

## ЧАСТЬ 2. Парсер

### 2.1 Склонировать

```bash
cd ~
git clone https://github.com/millana4/MP_Card_Parser mercator_parser
cd mercator_parser
```

### 2.2 Создать .env

`.env` не в git — создать заново:

```bash
nano .env
```

Заполнить (скопировать из `.env.example`, подставить значения). Критичные строки:

```
SERVICE_API_KEY=твой-межсервисный-ключ
REDIS_HOST=host.docker.internal
REDIS_PORT=6379
OZON_GEO_CODE=spb
OZON_GEO_NAME=Санкт-Петербург
PARSER_ID=1
```

**Внимание:** `REDIS_HOST` должен быть `host.docker.internal` (не `localhost`).
Через `host.docker.internal` контейнер парсера достучится до Redis ядра,
проброшенного на хост на порт 6379.

Сохранить: `Ctrl+O`, `Enter`, `Ctrl+X`.



### 2.3 Собрать и запустить парсер

```bash
docker compose up -d --build
```

Сборка долгая (Chrome + Selenium). Дождаться. Проверить контейнеры:

```bash
docker ps
```

Должны быть `mercator_parser-parsing-service-1` и `mercator_parser-worker-1`.

### 2.4 Проверить логи воркера

Воркер сейчас будет пытаться подключиться к Redis, которого ещё нет (ядро не
поднято) — это нормально, он ждёт и переподключается.

```bash
docker logs mercator_parser-worker-1 --tail 30
```

Должно быть `=== Запуск Ozon Redis-воркера ===` и попытки подключения к
`host.docker.internal:6379` (пока с ошибками — Redis ещё нет, это ОК).


---

## ЧАСТЬ 3. Таск-менеджер

### 3.1 Склонировать

```bash
cd ~
git clone https://github.com/millana4/MP_Task_Manager mercator_taskmanager
cd mercator_taskmanager
```

### 3.2 Создать .env

```bash
nano .env
```

Заполнить:
```
POSTGRES_PASSWORD=твой-пароль-postgres
CLICKHOUSE_PASSWORD=твой-пароль-clickhouse
PARSER_API_KEY=тот-же-ключ-что-SERVICE_API_KEY-в-парсере
```

**Важно:** `PARSER_API_KEY` = `SERVICE_API_KEY` из парсера (один и тот же ключ).

Сохранить: `Ctrl+O`, `Enter`, `Ctrl+X`.

### 3.3 Создать папку для дампов

```bash
mkdir -p backups
```

### 3.4 Проверить init-скрипт ClickHouse

Схема ClickHouse накатывается автоматически из `docker/clickhouse-init/`.
Проверить, что файл на месте - он должен приехать с git:

```bash
ls docker/clickhouse-init/
```

Должен быть `01-schema.sql`. **Если папки нет** — создать вручную:

```bash
mkdir -p docker/clickhouse-init
cp src/main/resources/clickhouse/schema.sql docker/clickhouse-init/01-schema.sql
```

И проверить, что в начале файла есть `USE taskmanager;`:
```bash
head -3 docker/clickhouse-init/01-schema.sql
```
Если нет и таблицы без префикса базы — добавить первой строкой:
```bash
sed -i '1i USE taskmanager;' docker/clickhouse-init/01-schema.sql
```

### 3.6 Собрать и запустить таск-менеджер

```bash
docker compose up -d --build
```

Первый раз долго - скачивает образы, собирает jar таск-менеджера. Дождаться.
Redis поднимается почти мгновенно.

### 3.7 Проверить, что всё поднялось

```bash
docker compose ps
```

Все четыре должны быть `Up`: `tm-postgres (healthy)`, `tm-clickhouse`,
`tm-redis (healthy)`, `tm-app`.

Логи таск-менеджера:

```bash
docker compose logs taskmanager --tail 40
```

Искать: `Started TaskmanagerApplication`, строки Flyway (миграции Postgres
накатились сами), `RedisResultListener запущен` (фоновые слушатели результатов
поднялись).

### 3.8 Загрузить переменные в сессию

Чтобы команды с паролями работали, загрузить `.env` в текущую сессию:

```bash
export $(grep -v '^#' .env | xargs)
```

Проверить:
```bash
echo "$POSTGRES_PASSWORD"
echo "$CLICKHOUSE_PASSWORD"
```
Должны вывести пароли. **Делать один раз в каждой новой SSH-сессии.**

### 3.9 Проверить схему ClickHouse

```bash
docker exec tm-clickhouse clickhouse-client --database taskmanager \
  --password "$CLICKHOUSE_PASSWORD" --query "SHOW TABLES"
```

Должны быть `measurement` и `card_snapshot`. **Если таблиц нет** — init не
сработал (volume был не пустой). Накатить вручную:

```bash
docker exec -i tm-clickhouse clickhouse-client --database taskmanager \
  --password "$CLICKHOUSE_PASSWORD" --multiquery < src/main/resources/clickhouse/schema.sql
```

И проверить снова `SHOW TABLES`.

### 3.10 Проверить схему Postgres

Flyway накатывает её сам при старте. Проверить:

```bash
docker exec -e PGPASSWORD="$POSTGRES_PASSWORD" tm-postgres \
  psql -U taskmanager -d taskmanager -c "\dt"
```

Должны быть `set`, `set_clothing`, `card`, `flyway_schema_history`.

---

## ЧАСТЬ 4. Связать парсер с Redis

Теперь Redis таск-менеджера поднят — воркер парсера должен к нему подключиться.
Перезапустить воркер, чтобы он переподключился начисто к уже здоровому Redis:

```bash
docker restart mercator_parser-worker-1
```

Подождать 20 сек, проверить логи:

```bash
docker logs mercator_parser-worker-1 --tail 30
```

**Успех выглядит так:**
- `ResultProducer подключён к Redis host.docker.internal:6379`;
- `TaskConsumer подключён к Redis host.docker.internal:6379 | ключи=['select:tasks:spb', 'parse:tasks:spb']`;
- `Воркер запущен | регион=spb (Санкт-Петербург) | роль=primary | parser_id=1`;
- **нет** ошибок `Connection refused`.

Воркер тянет задачи из общего пула через `BRPOP` (сначала подбор, затем обход) и
кладёт результаты через `LPUSH`. Партиций и consumer-групп нет — любой свободный
воркер берёт следующую задачу.


---

## ЧАСТЬ 5. Запустить сбор

### 5.1 Перенести CSV со стратами

С локальной машины (отдельный терминал, не SSH):

```bash
scp путь/к/set.csv имя-сервера@адрес-сервера:~/mercator_taskmanager/
```

### 5.2 Загрузить сет

В SSH, из папки ядра:

```bash
curl -X POST http://localhost:8080/api/v1/sets/import \
  -F "file=@set.csv" \
  -F "marketplace=ozon" \
  -F "category=clothing" \
  -F "geo=Санкт-Петербург"
```

Ответ — JSON с `setId` и числом страт. **Записать setId.**

### 5.3 Проверить сет в Postgres

```bash
docker exec -e PGPASSWORD="$POSTGRES_PASSWORD" tm-postgres \
  psql -U taskmanager -d taskmanager -c "SELECT count(*) FROM set_clothing;"
```

Число страт должно совпасть с ответом.

### 5.4 Запустить наполнение

Подставить свой setId:

```bash
curl -X POST http://localhost:8080/api/v1/sets/ТВОЙ-SET-ID/fill-async
```

Ответ — `202`, число отправленных задач.

### 5.5 Наблюдать за сбором

Логи таск-менеджера (приход результатов, запись карточек):

```bash
docker compose logs taskmanager -f --tail 20
```

Искать: `Задача подбора отправлена`, `Результат подбора: ok=true найдено=N`,
`записано N карточек`, `Партия сета ...: получено N/59`.

Логи парсера (как берёт задачи и парсит):

```bash
docker logs mercator_parser-worker-1 -f --tail 20
```

`Ctrl+C` — выйти из слежения (контейнеры продолжат работать).

Сбор медленный: около минуты на карточку. Десятки страт — часы. Это нормально.

---

## ЧАСТЬ 6. Проверить, что данные пишутся

### Карточки в Postgres:

```bash
docker exec -e PGPASSWORD="$POSTGRES_PASSWORD" tm-postgres \
  psql -U taskmanager -d taskmanager -c "SELECT count(*) FROM card;"
```

### Замеры в ClickHouse:

```bash
docker exec tm-clickhouse clickhouse-client --database taskmanager \
  --password "$CLICKHOUSE_PASSWORD" --query "SELECT count() FROM measurement"
```

### Снимки в ClickHouse:

```bash
docker exec tm-clickhouse clickhouse-client --database taskmanager \
  --password "$CLICKHOUSE_PASSWORD" --query "SELECT count() FROM card_snapshot"
```

### Живые данные - пример:

```bash
docker exec tm-clickhouse clickhouse-client --database taskmanager \
  --password "$CLICKHOUSE_PASSWORD" \
  --query "SELECT sku, price, quantity, parsed_at FROM measurement LIMIT 5 FORMAT Vertical"
```

Если все три счётчика растут — **вся цепочка работает**: CSV → сет → Redis →
парсер → Postgres + ClickHouse.

После появления карточек автоматически идёт непрерывный обход (потоковый
доливатель очереди): ядро держит в очереди обхода буфер задач и доливает его по
мере разбора, выбирая карточки, которых дольше всех не мерили. В `measurement`
начнут добавляться повторные замеры — временной ряд растёт вглубь.

---

## ЧАСТЬ 7. Автоматические резервные копии

Еженедельный дамп обеих баз на сервере + автозабор на локальный компьютер.
Данные и так в volumes переживают перезапуск.

### 6.1 Скрипт дампа на сервере

В папке ядра создать `backup.sh`:

```bash
cd ~/mercator_taskmanager
nano backup.sh
```

Вставить:

```bash
#!/usr/bin/env bash
# Дамп Postgres и ClickHouse в папку backups. Запускается по расписанию (cron).
set -e

# Грузим пароли из .env (cron не наследует переменные сессии).
cd "$(dirname "$0")"
export $(grep -v '^#' .env | xargs)

DATE=$(date +%Y%m%d_%H%M)
BACKUP_DIR=./backups

# Postgres: полный дамп базы
docker exec -e PGPASSWORD="$POSTGRES_PASSWORD" tm-postgres \
  pg_dump -U taskmanager taskmanager > "$BACKUP_DIR/pg_$DATE.sql"

# ClickHouse: выгрузка таблиц в CSV
docker exec tm-clickhouse clickhouse-client --database taskmanager \
  --password "$CLICKHOUSE_PASSWORD" \
  --query "SELECT * FROM measurement FORMAT CSVWithNames" > "$BACKUP_DIR/ch_measurement_$DATE.csv"

docker exec tm-clickhouse clickhouse-client --database taskmanager \
  --password "$CLICKHOUSE_PASSWORD" \
  --query "SELECT * FROM card_snapshot FINAL FORMAT CSVWithNames" > "$BACKUP_DIR/ch_snapshot_$DATE.csv"

# Удаляем дампы старше 60 дней
find "$BACKUP_DIR" -name "pg_*.sql" -mtime +60 -delete
find "$BACKUP_DIR" -name "ch_*.csv" -mtime +60 -delete

echo "Дамп готов: $DATE"
```

Сделать исполняемым и проверить вручную:

```bash
chmod +x backup.sh
./backup.sh
ls -lh backups/
```

Должны появиться `pg_*.sql`, `ch_measurement_*.csv`, `ch_snapshot_*.csv`.

Важно: скрипт сам грузит пароли из `.env` (внутри `export`), потому что cron
запускается без переменных твоей сессии.

### 6.2 Расписание на сервере - cron

```bash
crontab -e
```

Добавить строку - дамп каждый понедельник в 3:00:

```
0 3 * * 1 /home/имя-сервера/mercator_taskmanager/backup.sh >> /home/имя-сервера/mercator_taskmanager/backups/backup.log 2>&1
```

Формат: `минута час день месяц день-недели`. `0 3 * * 1` = понедельник 3:00.
Путь абсолютный (cron не знает текущую папку). Вывод пишется в `backup.log`.

Проверить, что записалось:

```bash
crontab -l
```

### 6.3 Автозабор на локальный компьютер

Выполняется на **локальном компе** Linux, не на сервере.

Настроить вход по SSH-ключу (чтобы не вводить пароль):

```bash
ssh-copy-id имя-сервера@адрес-сервера
```

Создать папку и скрипт забора:

```bash
mkdir -p ~/mercator-backups
nano ~/mercator-backups/pull-backups.sh
```

Вставить (подставить адрес сервера):

```bash
#!/usr/bin/env bash
# Забирает свежие дампы с сервера на локальный комп.
set -e

SERVER=имя-сервера@адрес-сервера
REMOTE_DIR=/home/имя-сервера/mercator_taskmanager/backups
LOCAL_DIR=~/mercator-backups

# rsync копирует только новые файлы (не перекачивает уже скачанное).
rsync -avz --exclude 'backup.log' "$SERVER:$REMOTE_DIR/" "$LOCAL_DIR/"

echo "Синхронизация готова: $(date)"
```

Сделать исполняемым и проверить:

```bash
chmod +x ~/mercator-backups/pull-backups.sh
~/mercator-backups/pull-backups.sh
ls -lh ~/mercator-backups/
```

Расписание на локальном компе. Забирать после дампа сервера — понедельник 12:00:

```bash
crontab -e
```

Добавить (подставить свой домашний путь):

```
0 12 * * 1 /home/твой-юзер/mercator-backups/pull-backups.sh >> /home/твой-юзер/mercator-backups/pull.log 2>&1
```


### Ручной дамп и восстановление

Разовый дамп — просто запусти `./backup.sh` на сервере.

Восстановить Postgres из дампа:

```bash
cat backups/pg_ГГГГММДД_ЧЧММ.sql | docker exec -i -e PGPASSWORD="$POSTGRES_PASSWORD" tm-postgres \
  psql -U taskmanager -d taskmanager
```

---

## ЧАСТЬ 8. Логи

### Логи таск-менеджера

```bash
# в реальном времени, последние 20 строк + новые
docker logs tm-app -f --tail 20

# или через compose (из папки ядра)
docker compose logs taskmanager -f --tail 20

# просто последние 50 строк без слежения
docker logs tm-app --tail 50
```

В логах ТМ видно: `Задача подбора отправлена`, `Результат подбора: ok=true
найдено=N`, `записано N карточек`, `Партия сета ...: получено N/59`, а также
работу доливателя очереди обхода (постановка задач и `parser_id` в результатах).

### Логи воркера парсера

```bash
# в реальном времени
docker logs mercator_parser-worker-1 -f --tail 20

# последние 50 строк
docker logs mercator_parser-worker-1 --tail 50
```

В логах воркера видно: `SELECT задача query='...'`, `ПОДБОР ЗАВЕРШЁН набрано N
из M`, `Результат отправлен в select:results:spb`, заходы в карточки.



### Смотреть оба сразу

Открыть два терминала (две SSH-сессии). В одном — логи ТМ, в другом — логи
воркера. Видно всю цепочку: слева ТМ шлёт задачи и принимает результаты, справа
парсер их берёт и парсит.

```bash
# терминал 1
docker logs tm-app -f --tail 20

# терминал 2
docker logs mercator_parser-worker-1 -f --tail 20
```

### Фильтр логов по grep

```bash
# только строки про партии и результаты
docker compose logs taskmanager | grep -iE "Партия|Результат|отправлено"

# только строки про конкретную страту в парсере
docker logs mercator_parser-worker-1 2>&1 | grep -iE "query|ПОДБОР"

# посчитать, сколько результатов пришло
docker compose logs taskmanager | grep -c "Результат подбора"

```

### Наблюдаемость через Redis

Вместо consumer-групп Kafka состояние очередей и воркеров смотрится прямо в Redis:

```bash
# сколько задач ждёт разбора (аналог LAG)
docker exec tm-redis redis-cli LLEN parse:tasks:spb
docker exec tm-redis redis-cli LLEN select:tasks:spb

# счётчики по воркеру: сделано / поймано антиботом
docker exec tm-redis redis-cli MGET parser:1:done parser:1:antibot
```

---

## Шпаргалка: частые команды

```bash
# статус контейнеров
docker compose ps

# логи (ядро)
docker compose logs taskmanager --tail 50
docker compose logs redis --tail 50

# логи парсера
docker logs mercator_parser-worker-1 --tail 50

# длина очередей задач (сколько ждёт разбора)
docker exec tm-redis redis-cli LLEN parse:tasks:spb
docker exec tm-redis redis-cli LLEN select:tasks:spb

# счётчики воркера (сделано / антибот)
docker exec tm-redis redis-cli MGET parser:1:done parser:1:antibot

# перезапустить приложение после git pull с новым кодом
git pull && docker compose up -d --build taskmanager

# загрузить .env в сессию (один раз за сессию)
export $(grep -v '^#' .env | xargs)

# остановить (данные сохранятся)
docker compose down

# остановить и стереть данные (ОСТОРОЖНО)
docker compose down -v
```