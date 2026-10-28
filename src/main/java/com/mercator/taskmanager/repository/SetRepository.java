package com.mercator.taskmanager.repository;

import com.mercator.taskmanager.entity.SetEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Доступ к сетам. Наследуя JpaRepository, автоматически получаем
 * save, findById, findAll, delete и т.д.
 */
@Repository
public interface SetRepository extends JpaRepository<SetEntity, UUID> {

    // Поиск по названиям справочников: Spring идёт по пути
    // marketplace.title, category.title, geo.title.
    List<SetEntity> findByMarketplaceTitleAndCategoryTitleAndGeoTitle(
            String marketplace, String category, String geo);

    // Сеты связки маркетплейс + категория + гео в указанных статусах
    // (например, есть ли уже черновик или идущий сбор).
    List<SetEntity> findByMarketplaceIdAndCategoryIdAndGeoIdAndStatusIn(
            Short marketplaceId, Short categoryId, Short geoId, Collection<String> statuses);

    // Сеты в статусе (добор — только активные).
    List<SetEntity> findByStatus(String status);
}