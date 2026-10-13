package com.mercator.taskmanager;

import com.mercator.taskmanager.clickhouse.CardSnapshotRepository;
import com.mercator.taskmanager.clickhouse.CardSnapshotRepository.SnapshotText;
import com.mercator.taskmanager.clickhouse.MeasurementRepository;
import com.mercator.taskmanager.controller.NicheCreateRequest;
import com.mercator.taskmanager.embedding.EmbedderClient;
import com.mercator.taskmanager.entity.SetEntity;
import com.mercator.taskmanager.entity.StratumEntity;
import com.mercator.taskmanager.entity.SubcategoryEntity;
import com.mercator.taskmanager.llm.LlmResponse;
import com.mercator.taskmanager.llm.YandexLlmClient;
import com.mercator.taskmanager.redis.ParseResultConsumer;
import com.mercator.taskmanager.redis.SelectResultConsumer;
import com.mercator.taskmanager.repository.SetRepository;
import com.mercator.taskmanager.repository.StratumRepository;
import com.mercator.taskmanager.repository.SubcategoryRepository;
import com.mercator.taskmanager.service.NicheScheduler;
import com.mercator.taskmanager.service.NicheService;
import com.mercator.taskmanager.service.ReferenceResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Общая основа интеграционных тестов ниши.
 *
 * Настоящие Postgres и Redis (PostgresTestBase). Подмены:
 *  - нейросеть: запрос + номер страты-подсказки, найденный в промпте по hintItem;
 *  - эмбеддер: «#NN» в тексте даёт косинус 0,90 + 0,09·NN/100 — после растяжения
 *    (text-sim-floor 0,90, text-sim-ceil 0,99) текстовая близость ровно 0,NN;
 *    у референса «#» нет — его вектор единичный;
 *  - ClickHouse: замеры и снимки не пишутся.
 * Карточки — с полным набором (content_txt), как от парсера при full_content=true:
 * «Худи» для девочек, метка «#NN» — в «Цвете» (входит в текст, фильтр задвоений
 * его не учитывает), «Коллекция» у каждой своя (в текст не входит, у референса её нет).
 * Планировщик ниш — раз в час (tick вызывается вручную), пачка подбора — 3 карточки.
 * Результаты парсера подаются прямо в консьюмеры, минуя Redis.
 */
@Tag("integration")
@ActiveProfiles("test")
@SpringBootTest(properties = {
        "taskmanager.niche.scheduler-interval-ms=3600000",
        "taskmanager.niche.select-batch-size=3",
        "taskmanager.niche.candidate-multiplier=2"
})
abstract class NicheTestSupport extends PostgresTestBase {

    static final String GEO = "Санкт-Петербург";
    static final String CATEGORY = "kids_clothing";
    static final String SELECT_TASKS = "select:tasks:ozon:spb";
    static final String PARSE_TASKS = "parse:tasks:ozon:spb";
    static final String REFERENCE_SKU = "1234567890";
    static final double PRICE = 1600.0;

    private static final double TEXT_FLOOR = 0.90;
    private static final double TEXT_CEIL = 0.99;
    private static final Pattern SCORE = Pattern.compile("#(\\d{2})");
    protected static final JsonMapper MAPPER = JsonMapper.builder().build();

    @MockitoBean protected YandexLlmClient llm;
    @MockitoBean protected EmbedderClient embedder;
    @MockitoBean protected MeasurementRepository measurementRepository;
    @MockitoBean protected CardSnapshotRepository snapshotRepository;

    @Autowired protected NicheService nicheService;
    @Autowired protected NicheScheduler scheduler;
    @Autowired protected ParseResultConsumer parseConsumer;
    @Autowired protected SelectResultConsumer selectConsumer;
    @Autowired protected StringRedisTemplate redis;
    @Autowired protected ReferenceResolver references;
    @Autowired protected SetRepository setRepository;
    @Autowired protected SubcategoryRepository subcategoryRepository;
    @Autowired protected StratumRepository stratumRepository;

    protected String prefix;     // уникальный префикс sku теста
    protected String hintItem;   // уникальное название предмета страты-подсказки
    protected final Map<UUID, String> stratumCardNames = new ConcurrentHashMap<>();
    protected final List<UUID> createdNiches = new ArrayList<>();

    /** Ниша теста: id в ядре и id из аналитики. */
    protected record NicheRef(UUID id, UUID externalId) {
    }

    @BeforeEach
    void setUpSupport() {
        prefix = "IT" + Math.abs(System.nanoTime() % 1_000_000_000L);
        hintItem = "ХудиИТ" + prefix;
        redis.delete(List.of(SELECT_TASKS, PARSE_TASKS));

        when(llm.getModel()).thenReturn("deepseek-v4-flash");
        when(llm.chat(anyString(), anyString()))
                .thenAnswer(inv -> llmAnswer(inv.getArgument(1)));
        when(embedder.embed(anyList()))
                .thenAnswer(inv -> vectors(inv.getArgument(0)));
        // старый путь текста карточек страт; новый подбор берёт content_txt
        when(snapshotRepository.findTextsByCardIds(anyCollection()))
                .thenAnswer(inv -> texts(inv.getArgument(0)));
    }

    @AfterEach
    void cleanUpSupport() {
        // каскад: кандидаты, подсказки, журнал, состав, карточки, созданные для ниши
        for (UUID id : createdNiches) {
            exec("DELETE FROM niche WHERE id = ?", id);
        }
    }

    // ---------- Подготовка данных ----------

    /** Сет kids_clothing со стратой-подсказкой и активными карточками страты с заданной близостью. */
    protected StratumEntity createHintStratum(int... scores) {
        SetEntity set = new SetEntity();
        set.setMarketplace(references.marketplace("ozon"));
        set.setCategory(references.category(CATEGORY));
        set.setGeo(references.geo(GEO));
        set = setRepository.save(set);

        SubcategoryEntity sc = new SubcategoryEntity();
        sc.setSet(set);
        sc.setTitle("Толстовки ИТ " + prefix);
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("segment", "f");
        attrs.put("layer", 2);
        sc.setAttrs(attrs);
        sc = subcategoryRepository.save(sc);

        StratumEntity st = new StratumEntity();
        st.setSubcategory(sc);
        st.setItem(hintItem);
        st.setQuery("худи для девочки");
        st.setCount(10);
        st.setIsSeasonal(false);
        st = stratumRepository.save(st);

        for (int s : scores) {
            addStratumCard(st, hoodie(stratumSku(s), "Худи страты #" + s, s, true));
        }
        return st;
    }

    /** Активная карточка страты с полным набором content (sku и name — из него). */
    protected void addStratumCard(StratumEntity st, Map<String, Object> content) {
        String name = String.valueOf(content.get("name"));
        UUID cardId = uuid("INSERT INTO card (stratum_id, sku, name, content_txt) "
                        + "VALUES (?, ?, ?, ?::jsonb) RETURNING id",
                st.getId(), String.valueOf(content.get("sku")), name, MAPPER.writeValueAsString(content));
        stratumCardNames.put(cardId, name);
    }

    /** Активная карточка страты без полного набора (content_txt пуст). */
    protected void addStratumCardWithoutContent(StratumEntity st, int score) {
        String name = "Худи страты #" + score;
        UUID cardId = uuid("INSERT INTO card (stratum_id, sku, name) VALUES (?, ?, ?) RETURNING id",
                st.getId(), stratumSku(score), name);
        stratumCardNames.put(cardId, name);
    }

    /** Ниша через сервис; размер ниши: target 2, plan — planCount. */
    protected NicheRef createNiche(boolean withUrl, int planCount) {
        UUID ext = UUID.randomUUID();
        nicheService.create(new NicheCreateRequest(ext, "ozon", CATEGORY, GEO,
                "розовое худи для девочки",
                withUrl ? "https://www.ozon.ru/product/hudi-" + REFERENCE_SKU + "/" : null));
        UUID id = uuid("SELECT id FROM niche WHERE external_id = ?", ext);
        exec("UPDATE niche SET target_count = 2, plan_count = ? WHERE id = ?", planCount, id);
        createdNiches.add(id);
        return new NicheRef(id, ext);
    }

    /**
     * Провести нишу до active одной пачкой: референс, запрос, пачка #80 #70 #60, ранжирование.
     * Подбор завершается, когда отобрано plan-count, поэтому plan-count не должен превышать
     * число кандидатов пачки и страт-подсказок.
     */
    protected void bringToActive(UUID nicheId) {
        scheduler.tick();
        parseConsumer.handle(referenceResult(uuid(
                "SELECT reference_task_id FROM niche WHERE id = ?", nicheId)));
        scheduler.tick();
        selectConsumer.handle(selectResult(selectTaskId(nicheId), 80, 70, 60));
        scheduler.tick();
    }

    protected String stratumSku(int score) {
        return prefix + "S" + score;
    }

    protected String searchSku(int score) {
        return prefix + "C" + score;
    }

    // ---------- Карточки с полным набором ----------

    /** «Худи #NN для девочки» в наличии, цена PRICE. */
    protected Map<String, Object> hoodie(String sku, int score) {
        return hoodie(sku, "Худи #" + score + " для девочки", score, true);
    }

    /**
     * Карточка «Худи» для девочек с полным набором: «#NN» — в «Цвете» (текст эмбеддинга),
     * «Коллекция» — своя у каждой карточки. В наличии — с ценой PRICE.
     */
    protected Map<String, Object> hoodie(String sku, String name, int score, boolean inStock) {
        Map<String, String> full = new LinkedHashMap<>();
        full.put("Тип", "Худи");
        full.put("Пол", "Девочки");
        full.put("Цвет", "розовый #" + score);
        full.put("Материал", "футер");
        full.put("Коллекция", "коллекция " + sku);

        Map<String, Object> c = new LinkedHashMap<>();
        c.put("sku", sku);
        c.put("name", name);
        c.put("url", "https://www.ozon.ru/product/it-" + sku + "/");
        c.put("button_state", inStock ? "in_stock" : "out_of_stock");
        c.put("category_path", "Одежда/Детям/Толстовки");
        c.put("characteristics", new LinkedHashMap<>(Map.of("Цвет", "розовый")));
        c.put("characteristics_full", full);
        c.put("aspects", new ArrayList<Map<String, Object>>());
        c.put("description", name);
        c.put("full_content", true);
        if (inStock) {
            setPrice(c, PRICE);
        }
        return c;
    }

    /** Референс: «Худи для девочки розовое», признаки Цвет и Материал, без метки и коллекции. */
    protected Map<String, Object> referenceCard() {
        Map<String, Object> c = hoodie(REFERENCE_SKU, "Худи для девочки розовое", 0, true);
        chars(c).put("Цвет", "розовый");
        chars(c).remove("Коллекция");
        return c;
    }

    @SuppressWarnings("unchecked")
    protected static Map<String, String> chars(Map<String, Object> card) {
        return (Map<String, String>) card.get("characteristics_full");
    }

    /** Цена карточки: price — основная (по ней цена за штуку), card_price на 100 меньше. */
    protected static void setPrice(Map<String, Object> card, double price) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("card_price", price - 100);
        p.put("price", price);
        p.put("original_price", price * 2);
        card.put("price", p);
    }

    /** Переключатель вариантов: key «Color» / type «colorsMix» или key «size» / type «sizes». */
    @SuppressWarnings("unchecked")
    protected static void addAspect(Map<String, Object> card, String key, String type,
                                    List<Map<String, Object>> values) {
        Map<String, Object> aspect = new LinkedHashMap<>();
        aspect.put("key", key);
        aspect.put("name", "size".equals(key) ? "Размер" : "Цвет");
        aspect.put("type", type);
        aspect.put("values", values);
        ((List<Map<String, Object>>) card.get("aspects")).add(aspect);
    }

    /** Значение переключателя в наличии. */
    protected static Map<String, Object> aspectValue(String sku, String value, double price) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("sku", sku);
        v.put("value", value);
        v.put("price", price);
        v.put("availability", "inStock");
        return v;
    }

    // ---------- Сообщения парсера ----------

    protected String referenceResult(UUID taskId) {
        return referenceResult(taskId, referenceCard());
    }

    protected String referenceResult(UUID taskId, Map<String, Object> card) {
        return parseResult(taskId, REFERENCE_SKU, card);
    }

    protected String verifyResult(UUID taskId, String sku, boolean inStock) {
        return parseResult(taskId, sku, hoodie(sku, "Худи проверка " + sku, 50, inStock));
    }

    protected String selectResult(UUID taskId, int... scores) {
        List<Map<String, Object>> cards = new ArrayList<>();
        for (int s : scores) {
            cards.add(hoodie(searchSku(s), s));
        }
        return selectResultCards(taskId, cards);
    }

    protected String selectResultCards(UUID taskId, List<Map<String, Object>> cards) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("task_id", taskId.toString());
        msg.put("set_id", null);
        msg.put("stratum_id", null);
        msg.put("geo", GEO);
        msg.put("parser_id", "it");
        msg.put("ok", true);
        msg.put("requested_count", cards.size());
        msg.put("found_count", cards.size());
        msg.put("cards", cards);
        return MAPPER.writeValueAsString(msg);
    }

    private String parseResult(UUID taskId, String sku, Map<String, Object> card) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("task_id", taskId.toString());
        msg.put("card_id", null);
        msg.put("sku", sku);
        msg.put("geo", GEO);
        msg.put("parser_id", "it");
        msg.put("ok", true);
        msg.put("card", card);
        return MAPPER.writeValueAsString(msg);
    }

    // ---------- Проверки ----------

    protected UUID selectTaskId(UUID nicheId) {
        return uuid("SELECT select_task_id FROM niche WHERE id = ?", nicheId);
    }

    protected String nicheStatus(UUID nicheId) {
        return str("SELECT status FROM niche WHERE id = ?", nicheId);
    }

    protected List<String> members(UUID nicheId) {
        return strings("SELECT c.sku FROM niche_card nc JOIN card c ON c.id = nc.card_id "
                + "WHERE nc.niche_id = ? ORDER BY c.sku", nicheId);
    }

    protected String candidateStatus(UUID nicheId, String sku) {
        return str("SELECT status FROM niche_candidate WHERE niche_id = ? AND sku = ?", nicheId, sku);
    }

    protected String cardStatus(String sku) {
        return str("SELECT status FROM card WHERE sku = ?", sku);
    }

    /** Задача подбора из очереди Redis по task_id. */
    protected Map<?, ?> selectTask(UUID taskId) {
        List<String> all = redis.opsForList().range(SELECT_TASKS, 0, -1);
        if (all != null) {
            for (String json : all) {
                Map<?, ?> m = MAPPER.readValue(json, Map.class);
                if (taskId.toString().equals(m.get("task_id"))) {
                    return m;
                }
            }
        }
        throw new AssertionError("Задача подбора " + taskId + " не найдена в очереди");
    }

    /** Задача обхода из очереди Redis по task_id. */
    protected Map<?, ?> parseTask(UUID taskId) {
        List<String> all = redis.opsForList().range(PARSE_TASKS, 0, -1);
        if (all != null) {
            for (String json : all) {
                Map<?, ?> m = MAPPER.readValue(json, Map.class);
                if (taskId.toString().equals(m.get("task_id"))) {
                    return m;
                }
            }
        }
        throw new AssertionError("Задача обхода " + taskId + " не найдена в очереди");
    }

    protected List<String> excludeSkus(Map<?, ?> task) {
        List<String> skus = new ArrayList<>();
        for (Object e : (List<?>) task.get("exclude")) {
            skus.add(String.valueOf(((Map<?, ?>) e).get("sku")));
        }
        return skus;
    }

    // ---------- Подмены ----------

    private LlmResponse llmAnswer(String userPrompt) {
        Matcher m = Pattern.compile("(?m)^(\\d+)\\. .*" + Pattern.quote(hintItem)).matcher(userPrompt);
        String strata = m.find() ? m.group(1) : "";
        return new LlmResponse("{\"search_query\": \"розовое худи для девочки\", \"strata\": ["
                + strata + "]}", "deepseek-v4-flash", 100);
    }

    private List<float[]> vectors(List<String> texts) {
        List<float[]> out = new ArrayList<>();
        for (String t : texts) {
            float[] v = new float[EmbedderClient.VECTOR_SIZE];
            Matcher m = SCORE.matcher(t);
            if (m.find()) {
                double cos = TEXT_FLOOR + (TEXT_CEIL - TEXT_FLOOR) * Integer.parseInt(m.group(1)) / 100.0;
                v[0] = (float) cos;
                v[1] = (float) Math.sqrt(1 - cos * cos);
            } else {
                v[0] = 1f;  // референс
            }
            out.add(v);
        }
        return out;
    }

    private Map<UUID, SnapshotText> texts(Collection<UUID> cardIds) {
        Map<UUID, SnapshotText> out = new HashMap<>();
        for (UUID id : cardIds) {
            String name = stratumCardNames.get(id);
            if (name != null) {
                out.put(id, new SnapshotText(name, "Одежда/Детям/Толстовки", "", ""));
            }
        }
        return out;
    }

    // ---------- SQL ----------

    private static Connection conn() throws SQLException {
        return DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private static PreparedStatement prepare(Connection c, String sql, Object... args) throws SQLException {
        PreparedStatement ps = c.prepareStatement(sql);
        for (int i = 0; i < args.length; i++) {
            ps.setObject(i + 1, args[i]);
        }
        return ps;
    }

    protected static void exec(String sql, Object... args) {
        try (Connection c = conn(); PreparedStatement ps = prepare(c, sql, args)) {
            ps.execute();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    protected static UUID uuid(String sql, Object... args) {
        try (Connection c = conn(); PreparedStatement ps = prepare(c, sql, args);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getObject(1, UUID.class) : null;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    protected static String str(String sql, Object... args) {
        try (Connection c = conn(); PreparedStatement ps = prepare(c, sql, args);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getString(1) : null;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    protected static long count(String sql, Object... args) {
        try (Connection c = conn(); PreparedStatement ps = prepare(c, sql, args);
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    protected static List<String> strings(String sql, Object... args) {
        List<String> out = new ArrayList<>();
        try (Connection c = conn(); PreparedStatement ps = prepare(c, sql, args);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}