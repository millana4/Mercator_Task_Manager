package com.mercator.taskmanager.repository;

import com.mercator.taskmanager.entity.ProdSpecsCardEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/** Признаки карточек от нейросети (ключ — card_id). */
@Repository
public interface ProdSpecsCardRepository extends JpaRepository<ProdSpecsCardEntity, UUID> {
}