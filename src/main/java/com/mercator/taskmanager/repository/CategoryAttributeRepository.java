package com.mercator.taskmanager.repository;

import com.mercator.taskmanager.entity.CategoryAttributeEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/** Описания атрибутов подкатегорий по категориям. */
@Repository
public interface CategoryAttributeRepository extends JpaRepository<CategoryAttributeEntity, Short> {
    List<CategoryAttributeEntity> findByCategoryId(Short categoryId);
}