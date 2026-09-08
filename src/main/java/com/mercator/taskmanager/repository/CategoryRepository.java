package com.mercator.taskmanager.repository;

import com.mercator.taskmanager.entity.CategoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/** Доступ к справочнику категорий. */
@Repository
public interface CategoryRepository extends JpaRepository<CategoryEntity, Short> {
    Optional<CategoryEntity> findByTitle(String title);
}
