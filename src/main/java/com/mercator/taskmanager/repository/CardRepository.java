package com.mercator.taskmanager.repository;

import com.mercator.taskmanager.entity.CardEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Доступ к карточкам. Карточка — товар маркетплейса (один sku — одна строка, V19);
 * состав страт — stratum_card, состав ниш — niche_card.
 */
@Repository
public interface CardRepository extends JpaRepository<CardEntity, UUID> {

    // ---------- Условие «карточка в работе» (обход, отставание, учащение, полный набор) ----------
    // Товар активен и состоит в страте живого сета (active / collecting, членство не закрыто)
    // или в неудалённой нише. Параметр :marketplaceId; в варианте с категорией — и :category.

    String IN_WORK = """
            c.status = 'active'
              AND c.marketplace_id = :marketplaceId
              AND (EXISTS (SELECT 1 FROM stratum_card sm
                           JOIN stratum st ON st.id = sm.stratum_id
                           JOIN subcategory sc ON sc.id = st.subcategory_id
                           JOIN "set" s ON s.id = sc.set_id
                           WHERE sm.card_id = c.id AND sm.removed_at IS NULL
                             AND s.status IN ('active', 'collecting'))
                   OR EXISTS (SELECT 1 FROM niche_card nc
                              JOIN niche n ON n.id = nc.niche_id
                              WHERE nc.card_id = c.id AND n.status <> 'deleted'))
            """;

    String IN_WORK_IN_CATEGORY = """
            c.status = 'active'
              AND c.marketplace_id = :marketplaceId
              AND (EXISTS (SELECT 1 FROM stratum_card sm
                           JOIN stratum st ON st.id = sm.stratum_id
                           JOIN subcategory sc ON sc.id = st.subcategory_id
                           JOIN "set" s ON s.id = sc.set_id
                           JOIN category cat ON cat.id = s.category_id
                           WHERE sm.card_id = c.id AND sm.removed_at IS NULL
                             AND s.status IN ('active', 'collecting') AND cat.title = :category)
                   OR EXISTS (SELECT 1 FROM niche_card nc
                              JOIN niche n ON n.id = nc.niche_id
                              JOIN category cat ON cat.id = n.category_id
                              WHERE nc.card_id = c.id AND n.status <> 'deleted'
                                AND cat.title = :category))
            """;

    List<CardEntity> findByStatus(String status);

    // Карточка товара на маркетплейсе (sku уникален в пределах маркетплейса).
    @Query(value = """
            SELECT c.* FROM card c
            WHERE c.sku = :sku AND c.marketplace_id = :marketplaceId
            """, nativeQuery = true)
    Optional<CardEntity> findBySkuAndMarketplace(@Param("sku") String sku,
                                                 @Param("marketplaceId") Short marketplaceId);

    // ---------- Поиск по списку sku (команда разметки признаков) ----------

    /** id и sku найденной карточки. */
    interface IdSku {
        UUID getId();

        String getSku();
    }

    // Карточки по списку sku на маркетплейсе (по названию).
    @Query(value = """
            SELECT c.id AS id, c.sku AS sku FROM card c
            JOIN marketplace m ON m.id = c.marketplace_id
            WHERE m.title = :marketplace AND c.sku IN (:skus)
            """, nativeQuery = true)
    List<IdSku> findIdsBySkus(@Param("marketplace") String marketplace,
                              @Param("skus") List<String> skus);

    // ---------- Состав страт (stratum_card) ----------

    // Все карточки, когда-либо входившие в страту, и выбывшие тоже — exclude подбора
    // (парсер не повторит собранное и выбывшее), выдача внешней аналитике.
    @Query(value = """
            SELECT c.* FROM card c
            JOIN stratum_card sm ON sm.card_id = c.id
            WHERE sm.stratum_id = :stratumId
            """, nativeQuery = true)
    List<CardEntity> findStratumMembers(@Param("stratumId") UUID stratumId);

    // Действующие члены страты: членство открыто и товар активен.
    @Query(value = """
            SELECT c.* FROM card c
            JOIN stratum_card sm ON sm.card_id = c.id
            WHERE sm.stratum_id = :stratumId AND sm.removed_at IS NULL AND c.status = 'active'
            """, nativeQuery = true)
    List<CardEntity> findActiveStratumMembers(@Param("stratumId") UUID stratumId);

    // Число действующих членов страты — лимит count при приёме подбора, дефицит при доборе.
    @Query(value = """
            SELECT count(*) FROM card c
            JOIN stratum_card sm ON sm.card_id = c.id
            WHERE sm.stratum_id = :stratumId AND sm.removed_at IS NULL AND c.status = 'active'
            """, nativeQuery = true)
    long countActiveStratumMembers(@Param("stratumId") UUID stratumId);

    // Действующих членов сета (открытое членство, товар активен) — ход сбора в админке.
    @Query(value = """
            SELECT count(*) FROM stratum_card sm
            JOIN card c ON c.id = sm.card_id
            JOIN stratum st ON st.id = sm.stratum_id
            JOIN subcategory sc ON sc.id = st.subcategory_id
            WHERE sc.set_id = :setId AND sm.removed_at IS NULL AND c.status = 'active'
            """, nativeQuery = true)
    long countActiveSetMembers(@Param("setId") UUID setId);

    // Состоит ли карточка (в том числе выбывшей) в какой-либо страте сета:
    // один товар в сете — не больше одного раза.
    @Query(value = """
            SELECT EXISTS (SELECT 1 FROM stratum_card sm
                           JOIN stratum st ON st.id = sm.stratum_id
                           JOIN subcategory sc ON sc.id = st.subcategory_id
                           WHERE sm.card_id = :cardId AND sc.set_id = :setId)
            """, nativeQuery = true)
    boolean isInSet(@Param("setId") UUID setId, @Param("cardId") UUID cardId);

    // Маркетплейс сета страты — запросом, без ленивых связей страты, загруженной в другой сессии.
    @Query(value = """
            SELECT s.marketplace_id FROM stratum st
            JOIN subcategory sc ON sc.id = st.subcategory_id
            JOIN "set" s ON s.id = sc.set_id
            WHERE st.id = :stratumId
            """, nativeQuery = true)
    Short findMarketplaceIdOfStratum(@Param("stratumId") UUID stratumId);

    // В скольких стратах живых сетов (active / collecting) карточка состоит.
    @Query(value = """
            SELECT count(*) FROM stratum_card sm
            JOIN stratum st ON st.id = sm.stratum_id
            JOIN subcategory sc ON sc.id = st.subcategory_id
            JOIN "set" s ON s.id = sc.set_id
            WHERE sm.card_id = :cardId AND sm.removed_at IS NULL
              AND s.status IN ('active', 'collecting')
            """, nativeQuery = true)
    long countLiveStrata(@Param("cardId") UUID cardId);

    // Включить карточку в страту (повторное включение ничего не меняет).
    // Своя транзакция: вызывается и вне транзакции сервиса (запись результата подбора).
    @Transactional
    @Modifying
    @Query(value = """
            INSERT INTO stratum_card (stratum_id, card_id, added_at)
            VALUES (:stratumId, :cardId, :now)
            ON CONFLICT (stratum_id, card_id) DO NOTHING
            """, nativeQuery = true)
    int addToStratum(@Param("stratumId") UUID stratumId, @Param("cardId") UUID cardId,
                     @Param("now") OffsetDateTime now);

    // Карточка выбыла: членство во всех стратах закрывается (добор поставит замену,
    // вернувшийся товар в страту не возвращается).
    @Transactional
    @Modifying
    @Query(value = """
            UPDATE stratum_card SET removed_at = :now
            WHERE card_id = :cardId AND removed_at IS NULL
            """, nativeQuery = true)
    int closeStratumMemberships(@Param("cardId") UUID cardId, @Param("now") OffsetDateTime now);

    // ---------- Отметки обхода и полный набор ----------

    // Пометить карточки отправленными (проставить last_enqueued_at) одним запросом.
    @Modifying
    @Query(value = """
            UPDATE card SET last_enqueued_at = :now
            WHERE id IN (:ids)
            """, nativeQuery = true)
    void markEnqueued(@Param("ids") List<UUID> ids, @Param("now") OffsetDateTime now);

    // Отметить время последнего замера карточки (потоковый обход).
    @Modifying
    @Query(value = """
            UPDATE card SET last_measured_at = :now
            WHERE id = :id
            """, nativeQuery = true)
    void markMeasured(@Param("id") UUID id, @Param("now") OffsetDateTime now);

    // Главное фото карточки: новое или изменившееся. Вызывается только с непустым адресом,
    // поэтому отсутствие фото в ответе парсера прежнее не затирает.
    @Modifying
    @Query(value = """
            UPDATE card SET image_url = CAST(:imageUrl AS text)
            WHERE id = :id AND image_url IS DISTINCT FROM CAST(:imageUrl AS text)
            """, nativeQuery = true)
    int updateImageUrl(@Param("id") UUID id, @Param("imageUrl") String imageUrl);

    // Полный набор карточки (полный разбор): content_txt и время разбора
    // перезаписываются, ТН ВЭД меняется только на непустое значение.
    @Modifying
    @Query(value = """
            UPDATE card
            SET content_txt = CAST(:content AS jsonb),
                content_txt_parsed_at = :parsedAt,
                tnved = COALESCE(CAST(:tnved AS text), tnved)
            WHERE id = :id
            """, nativeQuery = true)
    int updateContent(@Param("id") UUID id,
                      @Param("content") String content,
                      @Param("parsedAt") OffsetDateTime parsedAt,
                      @Param("tnved") String tnved);

    // ---------- Плановый обход по интервалам категорий ----------

    // Просроченные карточки категории: с последнего замера прошёл интервал категории
    // (:dueBefore = now − интервал), карточка не в работе (last_enqueued_at старше окна
    // ожидания или NULL). Первыми — самые просроченные. Товар в двух категориях попадает
    // в выборку обеих — фактически действует более короткий интервал.
    @Query(value = "SELECT c.* FROM card c WHERE " + IN_WORK_IN_CATEGORY + """
              AND (c.last_enqueued_at IS NULL OR c.last_enqueued_at < :inflightThreshold)
              AND (c.last_measured_at IS NULL OR c.last_measured_at < :dueBefore)
            ORDER BY c.last_measured_at ASC NULLS FIRST
            LIMIT :limit
            """, nativeQuery = true)
    List<CardEntity> findDueCandidates(@Param("marketplaceId") Short marketplaceId,
                                       @Param("category") String category,
                                       @Param("dueBefore") OffsetDateTime dueBefore,
                                       @Param("inflightThreshold") OffsetDateTime inflightThreshold,
                                       @Param("limit") int limit);

    // Карточек в работе в категории на маркетплейсе — для темпа обхода и доли просроченных.
    @Query(value = "SELECT count(*) FROM card c WHERE " + IN_WORK_IN_CATEGORY, nativeQuery = true)
    long countActiveInCategory(@Param("marketplaceId") Short marketplaceId,
                               @Param("category") String category);

    // Карточек в работе в категории без замера с :overdueBefore — для сигнала резерва.
    @Query(value = "SELECT count(*) FROM card c WHERE " + IN_WORK_IN_CATEGORY + """
              AND (c.last_measured_at IS NULL OR c.last_measured_at < :overdueBefore)
            """, nativeQuery = true)
    long countOverdueInCategory(@Param("marketplaceId") Short marketplaceId,
                                @Param("category") String category,
                                @Param("overdueBefore") OffsetDateTime overdueBefore);

    // ---------- Учащённый обход после скачка цены ----------

    // Карточки в работе на учащении, у которых с замера прошёл интервал учащения
    // (:dueBefore = now − интервал) и которые не в работе. Первыми — самые давние.
    @Query(value = "SELECT c.* FROM card c WHERE " + IN_WORK + """
              AND c.boost_started_at IS NOT NULL
              AND (c.last_enqueued_at IS NULL OR c.last_enqueued_at < :inflightThreshold)
              AND (c.last_measured_at IS NULL OR c.last_measured_at < :dueBefore)
            ORDER BY c.last_measured_at ASC NULLS FIRST
            LIMIT :limit
            """, nativeQuery = true)
    List<CardEntity> findBoostCandidates(@Param("marketplaceId") Short marketplaceId,
                                         @Param("dueBefore") OffsetDateTime dueBefore,
                                         @Param("inflightThreshold") OffsetDateTime inflightThreshold,
                                         @Param("limit") int limit);

    // Сколько активных карточек сейчас на учащении — для потолка.
    @Query(value = """
            SELECT count(*) FROM card
            WHERE status = 'active' AND boost_started_at IS NOT NULL
            """, nativeQuery = true)
    long countBoosted();

    // Добор полного набора: карточки в работе с пустым content_txt,
    // кроме уже отправленных в обход (last_enqueued_at свежее порога).
    @Query(value = "SELECT c.* FROM card c WHERE " + IN_WORK + """
              AND c.content_txt IS NULL
              AND (c.last_enqueued_at IS NULL OR c.last_enqueued_at < :threshold)
            ORDER BY c.last_measured_at ASC NULLS FIRST
            LIMIT :limit
            """, nativeQuery = true)
    List<CardEntity> findContentBackfillCandidates(@Param("marketplaceId") Short marketplaceId,
                                                   @Param("threshold") OffsetDateTime threshold,
                                                   @Param("limit") int limit);

    // ---------- Состав ниш (niche_card) ----------

    // Включить карточку в нишу (повторное включение ничего не меняет).
    @Modifying
    @Query(value = """
            INSERT INTO niche_card (niche_id, card_id) VALUES (:nicheId, :cardId)
            ON CONFLICT DO NOTHING
            """, nativeQuery = true)
    int addToNiche(@Param("nicheId") UUID nicheId, @Param("cardId") UUID cardId);

    // Исключить карточку из ниши.
    @Modifying
    @Query(value = """
            DELETE FROM niche_card WHERE niche_id = :nicheId AND card_id = :cardId
            """, nativeQuery = true)
    int removeFromNiche(@Param("nicheId") UUID nicheId, @Param("cardId") UUID cardId);

    // В скольких нишах состоит карточка.
    @Query(value = "SELECT count(*) FROM niche_card WHERE card_id = :cardId", nativeQuery = true)
    long countNiches(@Param("cardId") UUID cardId);

    // Число активных карточек в нише.
    @Query(value = """
            SELECT count(*) FROM niche_card nc
            JOIN card c ON c.id = nc.card_id
            WHERE nc.niche_id = :nicheId AND c.status = 'active'
            """, nativeQuery = true)
    long countActiveInNiche(@Param("nicheId") UUID nicheId);

    // Удаление ниши, шаг 1: убрать весь её состав.
    @Modifying
    @Query(value = "DELETE FROM niche_card WHERE niche_id = :nicheId", nativeQuery = true)
    int removeAllFromNiche(@Param("nicheId") UUID nicheId);

    // Удаление ниши, шаг 2: карточки, созданные для неё и входящие в другие ниши,
    // передать одной из этих ниш (они остаются в обходе).
    @Modifying
    @Query(value = """
            UPDATE card c
            SET niche_id = (SELECT nc.niche_id FROM niche_card nc
                            WHERE nc.card_id = c.id
                            ORDER BY nc.added_at LIMIT 1)
            WHERE c.niche_id = :nicheId
              AND EXISTS (SELECT 1 FROM niche_card nc WHERE nc.card_id = c.id)
            """, nativeQuery = true)
    int transferOwnedCards(@Param("nicheId") UUID nicheId);

    // Удаление ниши, шаг 3: оставшиеся карточки, созданные для неё, выбывают из обхода —
    // кроме тех, что состоят в страте живого сета (их обход решает категория).
    @Modifying
    @Query(value = """
            UPDATE card c SET status = 'dropped', dropped_at = :now
            WHERE c.niche_id = :nicheId AND c.status <> 'dropped'
              AND NOT EXISTS (SELECT 1 FROM stratum_card sm
                              JOIN stratum st ON st.id = sm.stratum_id
                              JOIN subcategory sc ON sc.id = st.subcategory_id
                              JOIN "set" s ON s.id = sc.set_id
                              WHERE sm.card_id = c.id AND sm.removed_at IS NULL
                                AND s.status IN ('active', 'collecting'))
            """, nativeQuery = true)
    int dropOwnedCards(@Param("nicheId") UUID nicheId, @Param("now") OffsetDateTime now);

    // Карточки состава ниши, выбывшие по жизненному циклу.
    @Query(value = """
            SELECT nc.card_id FROM niche_card nc
            JOIN card c ON c.id = nc.card_id
            WHERE nc.niche_id = :nicheId AND c.status = 'dropped'
            """, nativeQuery = true)
    List<UUID> findDroppedMembers(@Param("nicheId") UUID nicheId);
}