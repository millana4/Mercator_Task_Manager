package com.mercator.taskmanager.repository;

import com.mercator.taskmanager.entity.SetEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

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
}