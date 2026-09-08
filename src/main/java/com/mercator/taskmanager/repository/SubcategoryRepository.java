package com.mercator.taskmanager.repository;

import com.mercator.taskmanager.entity.SubcategoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/** Доступ к подкатегориям. */
@Repository
public interface SubcategoryRepository extends JpaRepository<SubcategoryEntity, UUID> {
    List<SubcategoryEntity> findBySetId(UUID setId);
}