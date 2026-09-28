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

    // Кандидаты для потокового обхода: активные, которых дольше всех не мерили
    // (NULL — первыми), исключая те, что уже в очереди/в работе (last_enqueued_at
    // свежее порога). :threshold — граница окна ожидания (now − inflight-timeout):
    // карточки с last_enqueued_at старше порога или NULL снова становятся кандидатами.
    @Query(value = """
            SELECT * FROM card
            WHERE status = 'active'
              AND (last_enqueued_at IS NULL OR last_enqueued_at < :threshold)
            ORDER BY last_measured_at ASC NULLS FIRST
            LIMIT :limit
            """, nativeQuery = true)
    List<CardEntity> findQueueCandidates(@Param("threshold") OffsetDateTime threshold,
                                         @Param("limit") int limit);

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

    // Кандидаты для обхода в пределах одного маркетплейса: активные, которых
    // дольше всех не мерили, исключая уже отправленные (last_enqueued_at
    // свежее порога).
    // Маркетплейс карточки: из сета через card → stratum → subcategory → set,
    // а у карточки без страты — из ниши-создателя. LEFT JOIN, чтобы карточки
    // без страты тоже попадали в обход.
    @Query(value = """
            SELECT c.* FROM card c
            LEFT JOIN stratum st ON st.id = c.stratum_id
            LEFT JOIN subcategory sc ON sc.id = st.subcategory_id
            LEFT JOIN "set" s ON s.id = sc.set_id
            LEFT JOIN niche n ON n.id = c.niche_id
            WHERE c.status = 'active'
              AND COALESCE(s.marketplace_id, n.marketplace_id) = :marketplaceId
              AND (c.last_enqueued_at IS NULL OR c.last_enqueued_at < :threshold)
            ORDER BY c.last_measured_at ASC NULLS FIRST
            LIMIT :limit
            """, nativeQuery = true)
    List<CardEntity> findQueueCandidatesByMarketplace(
            @Param("marketplaceId") Short marketplaceId,
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