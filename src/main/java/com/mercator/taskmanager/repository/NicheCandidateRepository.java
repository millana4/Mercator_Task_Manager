package com.mercator.taskmanager.repository;

import com.mercator.taskmanager.entity.NicheCandidateEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Доступ к кандидатам подбора ниш. */
@Repository
public interface NicheCandidateRepository extends JpaRepository<NicheCandidateEntity, UUID> {

    // Все кандидаты ниши.
    List<NicheCandidateEntity> findByNicheId(UUID nicheId);

    // Кандидат ниши по sku.
    Optional<NicheCandidateEntity> findByNicheIdAndSku(UUID nicheId, String sku);

    // Кандидаты ниши на проверочном парсинге (автодобор).
    long countByNicheIdAndVerifyTaskIdIsNotNull(UUID nicheId);

    // Маршрутизация результатов обхода: принадлежит ли task_id проверке кандидата.
    boolean existsByVerifyTaskId(UUID verifyTaskId);

    Optional<NicheCandidateEntity> findByVerifyTaskId(UUID verifyTaskId);
}