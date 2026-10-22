package com.mercator.taskmanager.repository;

import com.mercator.taskmanager.entity.CardEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Доступ к карточкам. */
@Repository
public interface CardRepository extends JpaRepository<CardEntity, UUID> {

    // Все карточки страты.
    List<CardEntity> findByStratumId(UUID stratumId);

    List<CardEntity> findByStatus(String status);

    // Активные карточки страты — понадобится для периодического обхода
    // (парсим только те, что в продаже).
    List<CardEntity> findByStratumIdAndStatus(UUID stratumId, String status);

    // Число активных карточек страты — для контроля лимита count при доборе.
    int countByStratumIdAndStatus(UUID stratumId, String status);

    // Есть ли уже карточка с таким sku в этом сете, в любой его страте
    boolean existsByStratumSubcategorySetIdAndSku(UUID setId, String sku);


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
    // ожидания или NULL). Первыми — самые просроченные.
    // Маркетплейс и категория — из сета страты или из ниши-создателя.
    @Query(value = """
            SELECT c.* FROM card c
            LEFT JOIN stratum st ON st.id = c.stratum_id
            LEFT JOIN subcategory sc ON sc.id = st.subcategory_id
            LEFT JOIN "set" s ON s.id = sc.set_id
            LEFT JOIN niche n ON n.id = c.niche_id
            JOIN category cat ON cat.id = COALESCE(s.category_id, n.category_id)
            WHERE c.status = 'active'
              AND COALESCE(s.marketplace_id, n.marketplace_id) = :marketplaceId
              AND cat.title = :category
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

    // Активных карточек категории на маркетплейсе — для темпа обхода и доли просроченных.
    @Query(value = """
            SELECT count(*) FROM card c
            LEFT JOIN stratum st ON st.id = c.stratum_id
            LEFT JOIN subcategory sc ON sc.id = st.subcategory_id
            LEFT JOIN "set" s ON s.id = sc.set_id
            LEFT JOIN niche n ON n.id = c.niche_id
            JOIN category cat ON cat.id = COALESCE(s.category_id, n.category_id)
            WHERE c.status = 'active'
              AND COALESCE(s.marketplace_id, n.marketplace_id) = :marketplaceId
              AND cat.title = :category
            """, nativeQuery = true)
    long countActiveInCategory(@Param("marketplaceId") Short marketplaceId,
                               @Param("category") String category);

    // Активных карточек категории без замера с :overdueBefore — для сигнала резерва.
    @Query(value = """
            SELECT count(*) FROM card c
            LEFT JOIN stratum st ON st.id = c.stratum_id
            LEFT JOIN subcategory sc ON sc.id = st.subcategory_id
            LEFT JOIN "set" s ON s.id = sc.set_id
            LEFT JOIN niche n ON n.id = c.niche_id
            JOIN category cat ON cat.id = COALESCE(s.category_id, n.category_id)
            WHERE c.status = 'active'
              AND COALESCE(s.marketplace_id, n.marketplace_id) = :marketplaceId
              AND cat.title = :category
              AND (c.last_measured_at IS NULL OR c.last_measured_at < :overdueBefore)
            """, nativeQuery = true)
    long countOverdueInCategory(@Param("marketplaceId") Short marketplaceId,
                                @Param("category") String category,
                                @Param("overdueBefore") OffsetDateTime overdueBefore);

    // ---------- Учащённый обход после скачка цены ----------

    // Карточки на учащении, у которых с замера прошёл интервал учащения
    // (:dueBefore = now − интервал) и которые не в работе. Первыми — самые давние.
    @Query(value = """
            SELECT c.* FROM card c
            LEFT JOIN stratum st ON st.id = c.stratum_id
            LEFT JOIN subcategory sc ON sc.id = st.subcategory_id
            LEFT JOIN "set" s ON s.id = sc.set_id
            LEFT JOIN niche n ON n.id = c.niche_id
            WHERE c.status = 'active'
              AND c.boost_started_at IS NOT NULL
              AND COALESCE(s.marketplace_id, n.marketplace_id) = :marketplaceId
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


    // Добор полного набора: активные карточки маркетплейса с пустым content_txt,
    // кроме уже отправленных в обход (last_enqueued_at свежее порога).
    // Маркетплейс — из сета страты или из ниши-создателя, как у обхода.
    @Query(value = """
            SELECT c.* FROM card c
            LEFT JOIN stratum st ON st.id = c.stratum_id
            LEFT JOIN subcategory sc ON sc.id = st.subcategory_id
            LEFT JOIN "set" s ON s.id = sc.set_id
            LEFT JOIN niche n ON n.id = c.niche_id
            WHERE c.status = 'active'
              AND c.content_txt IS NULL
              AND COALESCE(s.marketplace_id, n.marketplace_id) = :marketplaceId
              AND (c.last_enqueued_at IS NULL OR c.last_enqueued_at < :threshold)
            ORDER BY c.last_measured_at ASC NULLS FIRST
            LIMIT :limit
            """, nativeQuery = true)
    List<CardEntity> findContentBackfillCandidates(@Param("marketplaceId") Short marketplaceId,
                                                   @Param("threshold") OffsetDateTime threshold,
                                                   @Param("limit") int limit);


    // Карточка с таким sku на маркетплейсе (в любой страте или нише), активная — первой.
    // Для кандидатов ниши из выдачи: не заводить дубль уже существующей карточки.
    @Query(value = """
            SELECT c.* FROM card c
            LEFT JOIN stratum st ON st.id = c.stratum_id
            LEFT JOIN subcategory sc ON sc.id = st.subcategory_id
            LEFT JOIN "set" s ON s.id = sc.set_id
            LEFT JOIN niche n ON n.id = c.niche_id
            WHERE c.sku = :sku
              AND COALESCE(s.marketplace_id, n.marketplace_id) = :marketplaceId
            ORDER BY (c.status = 'active') DESC
            LIMIT 1
            """, nativeQuery = true)
    Optional<CardEntity> findBySkuAndMarketplace(@Param("sku") String sku,
                                                 @Param("marketplaceId") Short marketplaceId);

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

    // Удаление ниши, шаг 3: оставшиеся карточки, созданные для неё, выбывают из обхода.
    @Modifying
    @Query(value = """
            UPDATE card SET status = 'dropped', dropped_at = :now
            WHERE niche_id = :nicheId AND status <> 'dropped'
            """, nativeQuery = true)
    int dropOwnedCards(@Param("nicheId") UUID nicheId, @Param("now") OffsetDateTime now);

    // Карточки состава ниши, выбывшие по жизненному циклу.
    @Query(value = """
            SELECT nc.card_id FROM niche_card nc
            JOIN card c ON c.id = nc.card_id
            WHERE nc.niche_id = :nicheId AND c.status = 'dropped'
            """, nativeQuery = true)
    List<UUID> findDroppedMembers(@Param("nicheId") UUID nicheId);

    // sku активных карточек, созданных для ниш на маркетплейсе (без страты) —
    // в exclude подбора страт, чтобы категория не завела вторую карточку.
    @Query(value = """
            SELECT c.sku FROM card c
            JOIN niche n ON n.id = c.niche_id
            WHERE c.stratum_id IS NULL
              AND c.status = 'active'
              AND n.marketplace_id = :marketplaceId
            """, nativeQuery = true)
    List<String> findActiveNicheOwnedSkus(@Param("marketplaceId") Short marketplaceId);
}