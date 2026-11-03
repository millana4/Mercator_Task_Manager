package com.mercator.taskmanager.contract.Ozon;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Агрегированная карточка товара Ozon. Соответствует OzonCard из парсера.
 *
 * Поля, которые парсер не нашёл на странице, приходят null — это нормально,
 * набор виджетов у разных товаров отличается. Поэтому типы — обёртки
 * (Integer, Double, String, Boolean), способные быть пустыми, а не примитивы.
 *
 * price, seller, location в парсере всегда объект (не null), characteristics
 * и variants — всегда коллекция (в худшем случае пустая). Поля полного разбора
 * (characteristicsFull, aspects, tnved, fullContent) от парсера старой версии
 * не приходят и остаются null.
 */
@Data
public class OzonCard {

    // Идентификация
    private String sku;         // артикул товара
    private String url;         // URL карточки
    private String imageUrl;    // image_url — главное фото (og:image); null — не найдено
    private String parsedAt;    // parsed_at — дата и время парсинга (ISO-строка)
    private String name;        // наименование
    private String brand;       // бренд

    // Цены и остаток
    private OzonPrice price;
    private Integer quantity;   // количество/остаток, если указано

    // Состояние кнопки покупки (из парсера): in_cart / out_of_stock /
    // not_found / unknown. Надёжный признак активности карточки.
    private String buttonState;

    // Оценки (парсер отдаёт строками: "4.9", "103")
    private String rating;
    private String reviewsCount;  // reviews_count

    // Характеристики: плоский словарь {название: значение}.
    // Краткий блок (первые 5): "Коллекция", материал, состав, размер.
    private Map<String, String> characteristics;

    // Полный блок характеристик (characteristics_full): все пары без отбора.
    private Map<String, String> characteristicsFull;

    // Код ТН ВЭД (цифры), если Ozon его показывает.
    private String tnved;

    // Описание и категория
    private String description;
    private String category;
    private String categoryPath;    // category_path — путь иерархии

    // Варианты (размерная сетка): первый переключатель
    private String variantsAspect;         // variants_aspect — по чему варианты
    private List<OzonVariant> variants;

    // Все переключатели вариантов (цвет, размер и т. п.), у каждого значения свой sku
    private List<OzonAspect> aspects;

    // Полный разбор: в разметке найден полный блок характеристик (full_content)
    private Boolean fullContent;

    // Продавец и регион выдачи
    private OzonSeller seller;
    private OzonLocation location;
}