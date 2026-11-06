package com.mercator.taskmanager.repository;

import com.mercator.taskmanager.entity.LlmRequestLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/** Журнал обращений к нейросети. */
@Repository
public interface LlmRequestLogRepository extends JpaRepository<LlmRequestLogEntity, UUID> {
}