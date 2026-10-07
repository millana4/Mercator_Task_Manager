package com.mercator.taskmanager.contract.Ozon;

import lombok.Data;

import java.util.List;

/**
 * Переключатель вариантов карточки Ozon (виджет webAspects): «Цвет»
 * (type=colorsMix), «Размер» (type=sizes) и т. п. Соответствует OzonAspect из парсера.
 */
@Data
public class OzonAspect {
    private String name;                    // 'Цвет', 'Размер'
    private String key;                     // 'Color', 'size'
    private String type;                    // 'colorsMix', 'sizes'
    private List<OzonAspectValue> values;
}