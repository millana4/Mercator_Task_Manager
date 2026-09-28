package com.mercator.taskmanager.repository;

import com.mercator.taskmanager.entity.NicheEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Доступ к нишам. */
@Repository
public interface NicheRepository extends JpaRepository<NicheEntity, UUID> {

    // Ниша по id из сервиса аналитики.
    Optional<NicheEntity> findByExternalId(UUID externalId);

    // Маршрутизация результатов обхода: принадлежит ли task_id задаче референса.
    boolean existsByReferenceTaskId(UUID referenceTaskId);

    Optional<NicheEntity> findByReferenceTaskId(UUID referenceTaskId);

    // Ниши в статусе — для планировщика.
    List<NicheEntity> findByStatus(String status);
}