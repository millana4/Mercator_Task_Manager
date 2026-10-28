package com.mercator.taskmanager.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Настройки категории (category_setting, V18). seasonalSwitch — категория меняет
 * сет по сезону (сейчас только детская одежда). Нет строки — сезонной замены нет.
 */
@Entity
@Table(name = "category_setting")
@Getter
@Setter
public class CategorySettingEntity {

    @Id
    @Column(name = "category_id", nullable = false)
    private Short categoryId;

    @Column(name = "seasonal_switch", nullable = false)
    private Boolean seasonalSwitch = false;
}