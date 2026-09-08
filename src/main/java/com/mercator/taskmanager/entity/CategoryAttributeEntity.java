package com.mercator.taskmanager.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Описание атрибута подкатегории для конкретной категории.
 * Задаёт, какие ключи допустимы в subcategory.attrs и какие значения
 * они могут принимать — по нему валидируется импорт CSV.
 */
@Entity
@Table(name = "category_attribute")
@Getter
@Setter
public class CategoryAttributeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Short id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private CategoryEntity category;

    @Column(name = "key", nullable = false)
    private String key;

    @Column(nullable = false)
    private String title;

    @Column(name = "value_type", nullable = false)
    private String valueType;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "allowed_values", columnDefinition = "text[]")
    private String[] allowedValues;

    @Column(name = "is_required", nullable = false)
    private Boolean isRequired = true;
}