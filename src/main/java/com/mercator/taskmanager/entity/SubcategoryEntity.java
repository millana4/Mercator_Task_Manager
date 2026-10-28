package com.mercator.taskmanager.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Подкатегория внутри сета ("Домашняя одежда", "Верхняя одежда").
 * Атрибуты, специфичные для категории (пол, слой), лежат в attrs (jsonb) —
 * это позволяет добавлять новые категории с другим набором признаков
 * без изменения схемы.
 *
 * share — доля подкатегории в категории, tracked — «Отслеживать подкатегорию»
 * (своя аналитика, минимум карточек на подкатегорию). У сетов, которые
 * не ведёт админка, share пустая.
 */
@Entity
@Table(name = "subcategory")
@Getter
@Setter
public class SubcategoryEntity {

    @Id
    @UuidGenerator
    @Column(nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "set_id", nullable = false)
    private SetEntity set;

    @Column(nullable = false)
    private String title;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> attrs = new HashMap<>();

    @Column
    private BigDecimal share;

    @Column(nullable = false)
    private Boolean tracked = false;
}