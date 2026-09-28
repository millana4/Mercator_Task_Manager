package com.mercator.taskmanager.repository;

import com.mercator.taskmanager.entity.CardEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.OffsetDateTime;

import java.util.List;
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
    // а у карточки без страты — из ниши. LEFT JOIN, чтобы карточки без страты
    // (только в нише) тоже попадали в обход.
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

    // Удаление ниши: у карточек страт снять нишу (карточка остаётся в страте).
    @Modifying
    @Query(value = """
            UPDATE card SET niche_id = NULL
            WHERE niche_id = :nicheId AND stratum_id IS NOT NULL
            """, nativeQuery = true)
    int detachStratumCardsFromNiche(@Param("nicheId") UUID nicheId);

    // Удаление ниши: карточки только этой ниши выбывают из обхода.
    @Modifying
    @Query(value = """
            UPDATE card SET status = 'dropped', dropped_at = :now
            WHERE niche_id = :nicheId AND stratum_id IS NULL AND status <> 'dropped'
            """, nativeQuery = true)
    int dropNicheOnlyCards(@Param("nicheId") UUID nicheId, @Param("now") OffsetDateTime now);
}