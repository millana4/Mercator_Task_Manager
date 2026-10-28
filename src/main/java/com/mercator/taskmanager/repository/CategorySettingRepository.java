package com.mercator.taskmanager.repository;

import com.mercator.taskmanager.entity.CategorySettingEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Настройки категорий. */
@Repository
public interface CategorySettingRepository extends JpaRepository<CategorySettingEntity, Short> {

    // Меняет ли категория сет по сезону (нет строки настроек — нет).
    boolean existsByCategoryIdAndSeasonalSwitchTrue(Short categoryId);
}