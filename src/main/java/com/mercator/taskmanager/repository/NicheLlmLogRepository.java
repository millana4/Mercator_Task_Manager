package com.mercator.taskmanager.repository;

import com.mercator.taskmanager.entity.NicheLlmLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/** Журнал обращений к нейросети. */
@Repository
public interface NicheLlmLogRepository extends JpaRepository<NicheLlmLogEntity, UUID> {

    // Число неудачных обращений по нише — для лимита попыток.
    long countByNicheIdAndSuccessFalse(UUID nicheId);

    // Последнее обращение по нише — для паузы между повторами.
    Optional<NicheLlmLogEntity> findTopByNicheIdOrderByCreatedAtDesc(UUID nicheId);
}