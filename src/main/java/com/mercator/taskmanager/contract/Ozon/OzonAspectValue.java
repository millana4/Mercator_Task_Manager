package com.mercator.taskmanager.contract.Ozon;

import lombok.Data;

/**
 * Значение переключателя вариантов: свой sku, доступность и цены.
 * active=true — значение открытой карточки. Соответствует OzonAspectValue из парсера.
 */
@Data
public class OzonAspectValue {
    private String sku;
    private String value;           // 'розовый', '128 RU / 128'
    private String availability;    // inStock / outOfStock
    private Double price;
    private Double originalPrice;   // original_price
    private Boolean active;
}