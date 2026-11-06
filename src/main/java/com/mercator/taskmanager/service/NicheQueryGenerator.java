package com.mercator.taskmanager.service;

import com.mercator.taskmanager.entity.NicheEntity;
import com.mercator.taskmanager.entity.NicheLlmLogEntity;
import com.mercator.taskmanager.entity.StratumEntity;
import com.mercator.taskmanager.entity.SubcategoryEntity;
import com.mercator.taskmanager.llm.LlmException;
import com.mercator.taskmanager.llm.LlmGateway;
import com.mercator.taskmanager.llm.LlmRequest;
import com.mercator.taskmanager.llm.LlmResponse;
import com.mercator.taskmanager.repository.NicheLlmLogRepository;
import com.mercator.taskmanager.repository.StratumRepository;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Генерация поискового запроса ниши нейросетью.
 *
 * Вход: категория, описание пользователя, данные референса, нумерованный
 * список страт сетов той же связки. Выход (строго JSON):
 *   {"search_query": "...", "strata": [номера]}
 *
 * Каждое обращение пишется в niche_llm_log. При ошибке — LlmException;
 * статусы ниши и повторы решает планировщик.
 */
@Service
public class NicheQueryGenerator {

    private static final int MAX_QUERY_LENGTH = 120;
    private static final int MAX_STRATA = 3;
    private static final int MAX_CHARACTERISTICS = 15;
    private static final int MAX_DESCRIPTION_CHARS = 500;

    private static final String SYSTEM_PROMPT = """
            Ты помогаешь аналитику маркетплейса %s. По описанию от пользователя и данным \
            карточки-образца составь поисковый запрос для строки поиска маркетплейса, \
            по которому найдутся товары, максимально похожие на образец.

            Правила запроса:
            - 2–8 слов на русском, как его ввёл бы покупатель;
            - вид товара, для кого (пол) и отличительные признаки \
            (материал, цвет, фасон), если они важны для сходства;
            - для детской одежды обязательно укажи возраст или возрастной диапазон, \
            если он есть в описании или в карточке (например, «для девочки 10 лет»);
            - без артикула, цены, кавычек и размера (рост в сантиметрах, буквенный \
            или числовой размер);
            - бренд — только если пользователь сам указал его в описании.

            Также выбери из списка страт номера тех, к которым относится этот товар: \
            обычно одна, не больше трёх. Если ни одна не подходит — пустой список.

            Ответь строго одним JSON-объектом, без пояснений и без markdown:
            {"search_query": "...", "strata": [номера]}
            """;

    private final LlmGateway llm;
    private final StratumRepository stratumRepository;
    private final NicheLlmLogRepository logRepository;
    private final JsonMapper mapper = JsonMapper.builder().build();

    public NicheQueryGenerator(LlmGateway llm,
                               StratumRepository stratumRepository,
                               NicheLlmLogRepository logRepository) {
        this.llm = llm;
        this.stratumRepository = stratumRepository;
        this.logRepository = logRepository;
    }

    /**
     * Сгенерировать запрос и подобрать страты для ниши.
     *
     * @throws LlmException ошибка обращения к нейросети или непригодный ответ
     */
    public NicheQueryResult generate(NicheEntity niche) {
        // ---------- 1. Страты связки и промпт ----------
        List<StratumEntity> strata = stratumRepository.findForNicheHints(
                niche.getMarketplace().getId(),
                niche.getCategory().getId(),
                niche.getGeo().getId());
        String systemPrompt = SYSTEM_PROMPT.formatted(niche.getMarketplace().getTitle());
        String userPrompt = buildUserPrompt(niche, strata);

        NicheLlmLogEntity entry = new NicheLlmLogEntity();
        entry.setNiche(niche);
        entry.setSystemPrompt(systemPrompt);
        entry.setUserPrompt(userPrompt);

        // ---------- 2. Вызов и разбор; журнал пишется в любом случае ----------
        try {
            LlmResponse response = llm.call(LlmGateway.NICHE_QUERY, "niche", nicheRef(niche),
                    LlmRequest.text(null, systemPrompt, userPrompt, null));
            entry.setModel(response.model());
            entry.setTokens(response.tokens());
            entry.setResponse(response.content());

            NicheQueryResult result = parse(response, strata);
            entry.setSuccess(true);
            return result;
        } catch (LlmException e) {
            entry.setError(e.getKind() + ": " + e.getMessage());
            throw e;
        } catch (RuntimeException e) {
            entry.setError("OTHER: " + e.getMessage());
            throw new LlmException(LlmException.Kind.OTHER, e.getMessage(), e);
        } finally {
            if (entry.getModel() == null) {
                entry.setModel(llm.defaultModel());
            }
            logRepository.save(entry);
        }
    }

    private static String nicheRef(NicheEntity niche) {
        return niche.getId() == null ? null : niche.getId().toString();
    }

    // ---------- Пользовательский промпт ----------

    private String buildUserPrompt(NicheEntity niche, List<StratumEntity> strata) {
        String category = niche.getCategory().getTitle();
        StringBuilder sb = new StringBuilder();

        sb.append("Категория: ").append(categoryLabel(category)).append('\n');
        sb.append("Описание пользователя: ")
                .append(niche.getDescription() != null ? niche.getDescription() : "не указано")
                .append("\n\n");

        Map<String, Object> ref = niche.getReferenceSnapshot();
        if (ref != null) {
            sb.append("Карточка-образец:\n");
            appendField(sb, "Название", ref.get("name"));
            appendField(sb, "Бренд", ref.get("brand"));
            appendField(sb, "Раздел маркетплейса", ref.get("category_path"));
            appendCharacteristics(sb, ref.get("characteristics"));
            appendField(sb, "Описание", truncate(ref.get("description")));
        } else {
            sb.append("Карточки-образца нет.\n");
        }

        sb.append("\nСтраты:\n");
        for (int i = 0; i < strata.size(); i++) {
            StratumEntity st = strata.get(i);
            SubcategoryEntity sc = st.getSubcategory();
            sb.append(i + 1).append(". ");
            String segment = segmentLabel(category, sc.getAttrs());
            if (!segment.isEmpty()) {
                sb.append(segment).append(", ");
            }
            sb.append(sc.getTitle());
            // у взрослой одежды подкатегория — обёртка с названием предмета
            if (st.getItem() != null && !st.getItem().equals(sc.getTitle())) {
                sb.append(": ").append(st.getItem());
            }
            if (st.getQuery() != null && !st.getQuery().isBlank()) {
                sb.append(" (запрос «").append(st.getQuery()).append("»)");
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static void appendField(StringBuilder sb, String label, Object value) {
        if (value instanceof String s && !s.isBlank()) {
            sb.append("- ").append(label).append(": ").append(s.strip()).append('\n');
        }
    }

    private static void appendCharacteristics(StringBuilder sb, Object value) {
        if (!(value instanceof Map<?, ?> map) || map.isEmpty()) {
            return;
        }
        List<String> parts = new ArrayList<>();
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (parts.size() >= MAX_CHARACTERISTICS) {
                break;
            }
            parts.add(e.getKey() + ": " + e.getValue());
        }
        sb.append("- Характеристики: ").append(String.join("; ", parts)).append('\n');
    }

    private static String truncate(Object value) {
        if (!(value instanceof String s) || s.isBlank()) {
            return null;
        }
        String text = s.replaceAll("\\s+", " ").strip();
        return text.length() <= MAX_DESCRIPTION_CHARS ? text : text.substring(0, MAX_DESCRIPTION_CHARS) + "…";
    }

    private static String categoryLabel(String category) {
        return switch (category) {
            case "clothing" -> "одежда (взрослая)";
            case "kids_clothing" -> "детская одежда";
            default -> category;
        };
    }

    private static String segmentLabel(String category, Map<String, Object> attrs) {
        if (attrs == null) {
            return "";
        }
        // ключ segment планируется переименовать в gender — поддерживаем оба
        Object value = attrs.containsKey("gender") ? attrs.get("gender") : attrs.get("segment");
        if (value == null) {
            return "";
        }
        String code = value.toString();
        boolean kids = "kids_clothing".equals(category);
        return switch (code) {
            case "f" -> kids ? "девочки" : "женщины";
            case "m" -> kids ? "мальчики" : "мужчины";
            case "baby" -> "малыши";
            default -> code;
        };
    }

    // ---------- Разбор ответа ----------

    private NicheQueryResult parse(LlmResponse response, List<StratumEntity> strata) {
        String text = response.content().strip();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new LlmException(LlmException.Kind.OTHER, "в ответе нет JSON: " + abbreviate(text));
        }

        Map<?, ?> json;
        try {
            json = mapper.readValue(text.substring(start, end + 1), Map.class);
        } catch (Exception e) {
            throw new LlmException(LlmException.Kind.OTHER,
                    "JSON не разобран: " + e.getMessage(), e);
        }

        String query = json.get("search_query") instanceof String s ? normalizeQuery(s) : "";
        if (query.isEmpty()) {
            throw new LlmException(LlmException.Kind.OTHER, "пустой search_query");
        }
        if (query.length() > MAX_QUERY_LENGTH) {
            throw new LlmException(LlmException.Kind.OTHER,
                    "search_query длиннее " + MAX_QUERY_LENGTH + " символов");
        }

        List<StratumEntity> hinted = new ArrayList<>();
        if (json.get("strata") instanceof List<?> items) {
            for (Object item : items) {
                if (hinted.size() >= MAX_STRATA) {
                    break;
                }
                Integer index = null;
                if (item instanceof Number n) {
                    index = n.intValue();
                } else if (item instanceof String s && s.strip().matches("\\d+")) {
                    index = Integer.parseInt(s.strip());
                }
                if (index != null && index >= 1 && index <= strata.size()) {
                    StratumEntity st = strata.get(index - 1);
                    if (!hinted.contains(st)) {
                        hinted.add(st);
                    }
                }
            }
        }
        return new NicheQueryResult(query, hinted, response.model(), response.tokens());
    }

    private static String normalizeQuery(String s) {
        return s.replaceAll("[\"«»“”']", "").replaceAll("\\s+", " ").strip();
    }

    private static String abbreviate(String s) {
        return s.length() <= 200 ? s : s.substring(0, 200) + "…";
    }
}