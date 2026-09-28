package com.mercator.taskmanager.repository;

import com.mercator.taskmanager.entity.StratumEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/** Доступ к стратам. */
@Repository
public interface StratumRepository extends JpaRepository<StratumEntity, UUID> {

    // Все страты сета — путь subcategory.set.id.
    List<StratumEntity> findBySubcategorySetId(UUID setId);

    // Страты одной подкатегории.
    List<StratumEntity> findBySubcategoryId(UUID subcategoryId);

    // Страты всех сетов связки маркетплейс + категория + гео — список для нейросети
    // при генерации запроса ниши. Подкатегория подгружается сразу (JOIN FETCH),
    // чтобы строить промпт без открытой транзакции.
    @Query("""
            SELECT st FROM StratumEntity st
            JOIN FETCH st.subcategory sc
            JOIN sc.set s
            WHERE s.marketplace.id = :marketplaceId
              AND s.category.id = :categoryId
              AND s.geo.id = :geoId
            ORDER BY sc.title, st.item
            """)
    List<StratumEntity> findForNicheHints(@Param("marketplaceId") Short marketplaceId,
                                          @Param("categoryId") Short categoryId,
                                          @Param("geoId") Short geoId);
}