package com.mercator.taskmanager.repository;

import com.mercator.taskmanager.entity.GeoEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/** Доступ к справочнику геолокаций. */
@Repository
public interface GeoRepository extends JpaRepository<GeoEntity, Short> {
    Optional<GeoEntity> findByTitle(String title);
}