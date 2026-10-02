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
 *  - эмбеддер: «#NN» в тексте даёт близость 0.NN к референсу (у референса «#» нет);
 *  - ClickHouse: замеры/снимки не пишутся, тексты карточек страт — из stratumCardNames.
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
            String name = "Худи страты #" + s;
            UUID cardId = uuid("INSERT INTO card (stratum_id, sku, name) VALUES (?, ?, ?) RETURNING id",
                    st.getId(), stratumSku(s), name);
            stratumCardNames.put(cardId, name);
        }
        return st;
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

    // ---------- Сообщения парсера ----------

    protected String referenceResult(UUID taskId) {
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("sku", REFERENCE_SKU);
        card.put("name", "Худи для девочки розовое");
        card.put("button_state", "in_stock");
        card.put("category_path", "Одежда/Детям/Толстовки");
        card.put("characteristics", new LinkedHashMap<>(Map.of("Цвет", "розовый")));
        return parseResult(taskId, REFERENCE_SKU, card);
    }

    protected String verifyResult(UUID taskId, String sku, boolean inStock) {
        return parseResult(taskId, sku, card(sku, "Худи проверка " + sku, inStock));
    }

    protected String selectResult(UUID taskId, int... scores) {
        List<Map<String, Object>> cards = new ArrayList<>();
        for (int s : scores) {
            cards.add(card(searchSku(s), "Худи #" + s + " для девочки", true));
        }
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("task_id", taskId.toString());
        msg.put("set_id", null);
        msg.put("stratum_id", null);
        msg.put("geo", GEO);
        msg.put("parser_id", "it");
        msg.put("ok", true);
        msg.put("requested_count", scores.length);
        msg.put("found_count", scores.length);
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

    private Map<String, Object> card(String sku, String name, boolean inStock) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("sku", sku);
        c.put("name", name);
        c.put("url", "https://www.ozon.ru/product/it-" + sku + "/");
        c.put("button_state", inStock ? "in_stock" : "out_of_stock");
        c.put("category_path", "Одежда/Детям/Толстовки");
        c.put("characteristics", new LinkedHashMap<>(Map.of("Цвет", "розовый")));
        c.put("description", name);
        if (inStock) {
            Map<String, Object> price = new LinkedHashMap<>();
            price.put("card_price", 1500.0);
            price.put("price", 1600.0);
            price.put("original_price", 3000.0);
            c.put("price", price);
        }
        return c;
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
                double s = Integer.parseInt(m.group(1)) / 100.0;
                v[0] = (float) s;
                v[1] = (float) Math.sqrt(1 - s * s);
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