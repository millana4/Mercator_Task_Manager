package com.mercator.taskmanager.repository;

import com.mercator.taskmanager.entity.NicheCandidateEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/** Доступ к кандидатам подбора ниш. */
@Repository
public interface NicheCandidateRepository extends JpaRepository<NicheCandidateEntity, UUID> {

    // Все кандидаты ниши.
    List<NicheCandidateEntity> findByNicheId(UUID nicheId);
}