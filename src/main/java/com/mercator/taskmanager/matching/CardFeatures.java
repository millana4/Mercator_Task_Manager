package com.mercator.taskmanager.matching;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Признаки карточки для подбора аналогов ниши.
 *
 * @param group         группа вида изделия (жёсткий фильтр); null — вид не определён
 * @param genders       для кого вещь: «девочки», «мальчики» или оба; пусто — неизвестно
 * @param featureValues текстовые признаки группы в порядке текста; нет значения — пустая строка
 * @param sizeScale     шкала размера: рост или стопа
 * @param sizeMin       размер карточки, мин (ростовка или см стопы); null — не определён
 * @param tempMin       температурный режим, нижняя граница, °C; null — нет
 * @param naturalShare  доля натуральных материалов, %; null — не определена
 * @param packCount     штук (пар) в упаковке
 * @param unitPrice     цена за штуку; null — цены нет
 * @param modelSkus     sku модели: сама карточка и все значения переключателей «Размер» и «Цвет»
 * @param modelSizes    размеры модели в наличии из переключателя «Размер»
 */
public record CardFeatures(
        String sku,
        String name,
        String group,
        Set<String> genders,
        Map<String, String> featureValues,
        SizeScale sizeScale,
        Double sizeMin,
        Double sizeMax,
        Double tempMin,
        Double tempMax,
        Double naturalShare,
        int packCount,
        Double unitPrice,
        Set<String> modelSkus,
        List<ModelSize> modelSizes) {

    /** Размер модели в наличии: свой sku, размер в единой шкале, цена за штуку, подпись. */
    public record ModelSize(String sku, double min, double max, Double unitPrice, String label) {
    }

    /** Текст для эмбеддинга по всем признакам группы: «Ключ: значение; …». */
    public String text() {
        return textOn(new ArrayList<>(featureValues.keySet()));
    }

    /** Текст только по заданным признакам, в их порядке; пустые пропускаются. */
    public String textOn(List<String> keys) {
        StringBuilder sb = new StringBuilder();
        for (String key : keys) {
            String value = featureValues.get(key);
            if (value != null && !value.isEmpty()) {
                if (!sb.isEmpty()) {
                    sb.append("; ");
                }
                sb.append(key).append(": ").append(value);
            }
        }
        return sb.toString();
    }

    /** Признаки группы, заполненные у карточки, в порядке текста. */
    public List<String> filledKeys() {
        List<String> keys = new ArrayList<>();
        featureValues.forEach((key, value) -> {
            if (!value.isEmpty()) {
                keys.add(key);
            }
        });
        return keys;
    }
}