package com.mercator.taskmanager.repository;

import com.mercator.taskmanager.entity.MarketplaceEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/** Доступ к справочнику маркетплейсов. */
@Repository
public interface MarketplaceRepository extends JpaRepository<MarketplaceEntity, Short> {
    Optional<MarketplaceEntity> findByTitle(String title);
}