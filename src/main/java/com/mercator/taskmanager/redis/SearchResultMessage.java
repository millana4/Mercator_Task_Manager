package com.mercator.taskmanager.redis;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;
import java.util.UUID;

/** Результат снимка выдачи от парсера (search:results:<маркетплейс>:<гео>), JSON snake_case. */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class SearchResultMessage {
    private UUID taskId;
    private UUID setId;
    private UUID stratumId;
    private String geo;
    private String marketplace;
    private String category;
    private String query;
    private String runAt;
    private String parsedAt;
    private String parserId;
    private Boolean ok;
    private String url;
    private Integer scrolls;
    private Integer tilesTotal;
    private List<Tile> tiles;
    private String error;
    private String errorCode;

    /** Плитка выдачи. */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Tile {
        private Integer position;
        private String sku;
        private String url;
        private String name;
        private String brand;
        private Boolean brandVerified;
        private Double price;
        private Double originalPrice;
        private Integer discountPct;
        private Integer quantity;
        private Double rating;
        private Integer reviews;
        private List<String> badges;
        private List<String> labels;
    }
}