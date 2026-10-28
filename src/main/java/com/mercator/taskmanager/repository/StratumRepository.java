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

    // Страты активного сета связки маркетплейс + категория + гео — список для нейросети
    // при генерации запроса ниши. Черновик, сбор и выведенные сеты не участвуют; страты
    // с нулевым количеством карточек не имеют. Подкатегория подгружается сразу (JOIN FETCH),
    // чтобы строить промпт без открытой транзакции.
    @Query("""
            SELECT st FROM StratumEntity st
            JOIN FETCH st.subcategory sc
            JOIN sc.set s
            WHERE s.marketplace.id = :marketplaceId
              AND s.category.id = :categoryId
              AND s.geo.id = :geoId
              AND s.status = 'active'
              AND st.count > 0
            ORDER BY sc.title, st.item
            """)
    List<StratumEntity> findForNicheHints(@Param("marketplaceId") Short marketplaceId,
                                          @Param("categoryId") Short categoryId,
                                          @Param("geoId") Short geoId);

    // Страты активных сетов категории вместе с подкатегорией и сетом (маркетплейс, гео)
    // - для задач снимка выдачи без открытой транзакции. Новый сезонный сет попадает
    // в выдачу только после переключения; страты с нулевым количеством не снимаются.
    @Query("""
            SELECT st FROM StratumEntity st
            JOIN FETCH st.subcategory sc
            JOIN FETCH sc.set s
            WHERE s.category.title = :category
              AND s.status = 'active'
              AND st.count > 0
            ORDER BY sc.title, st.item
            """)
    List<StratumEntity> findByCategoryTitleWithSet(@Param("category") String category);

    // Страты сетов в сборе (collecting) на маркетплейсе с количеством больше нуля,
    // вместе с подкатегорией и сетом - очередь подбора нового сезонного сета.
    @Query("""
            SELECT st FROM StratumEntity st
            JOIN FETCH st.subcategory sc
            JOIN FETCH sc.set s
            WHERE s.status = 'collecting'
              AND s.marketplace.id = :marketplaceId
              AND st.count > 0
            """)
    List<StratumEntity> findCollectingForSelect(@Param("marketplaceId") Short marketplaceId);

    // Страты активного сета связки с тем же поисковым запросом - источник частых SKU
    // выдачи для страты нового сезонного сета (своих снимков выдачи у неё ещё нет).
    @Query("""
            SELECT st FROM StratumEntity st
            JOIN st.subcategory sc
            JOIN sc.set s
            WHERE s.status = 'active'
              AND s.marketplace.id = :marketplaceId
              AND s.category.id = :categoryId
              AND s.geo.id = :geoId
              AND st.query = :query
            """)
    List<StratumEntity> findActiveByQuery(@Param("marketplaceId") Short marketplaceId,
                                          @Param("categoryId") Short categoryId,
                                          @Param("geoId") Short geoId,
                                          @Param("query") String query);
}