package com.mercator.taskmanager.repository;

import com.mercator.taskmanager.entity.StratumEntity;
import org.springframework.data.jpa.repository.JpaRepository;
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
}