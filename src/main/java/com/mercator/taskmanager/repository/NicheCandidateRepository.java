package com.mercator.taskmanager.repository;

import com.mercator.taskmanager.entity.NicheCandidateEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/** Доступ к кандидатам подбора ниш. Запросы добавляются по мере появления логики ниш. */
@Repository
public interface NicheCandidateRepository extends JpaRepository<NicheCandidateEntity, UUID> {
}
