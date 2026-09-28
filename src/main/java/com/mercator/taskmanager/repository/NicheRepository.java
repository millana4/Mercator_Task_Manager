package com.mercator.taskmanager.repository;

import com.mercator.taskmanager.entity.NicheEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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

    // Маршрутизация результатов подбора: принадлежит ли task_id подбору ниши.
    boolean existsBySelectTaskId(UUID selectTaskId);

    Optional<NicheEntity> findBySelectTaskId(UUID selectTaskId);

    // Ниши в статусе — для планировщика.
    List<NicheEntity> findByStatus(String status);

    // id страт-подсказок ниши — без загрузки ленивой коллекции.
    @Query("SELECT st.id FROM NicheEntity n JOIN n.hintedStrata st WHERE n.id = :nicheId")
    List<UUID> findHintedStratumIds(@Param("nicheId") UUID nicheId);
}