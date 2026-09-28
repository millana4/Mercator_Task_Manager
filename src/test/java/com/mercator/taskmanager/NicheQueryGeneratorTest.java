package com.mercator.taskmanager;

import com.mercator.taskmanager.entity.CategoryEntity;
import com.mercator.taskmanager.entity.GeoEntity;
import com.mercator.taskmanager.entity.MarketplaceEntity;
import com.mercator.taskmanager.entity.NicheEntity;
import com.mercator.taskmanager.entity.NicheLlmLogEntity;
import com.mercator.taskmanager.entity.StratumEntity;
import com.mercator.taskmanager.entity.SubcategoryEntity;
import com.mercator.taskmanager.llm.LlmException;
import com.mercator.taskmanager.llm.LlmResponse;
import com.mercator.taskmanager.llm.YandexLlmClient;
import com.mercator.taskmanager.repository.NicheLlmLogRepository;
import com.mercator.taskmanager.repository.StratumRepository;
import com.mercator.taskmanager.service.NicheQueryGenerator;
import com.mercator.taskmanager.service.NicheQueryResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Генератор поискового запроса ниши: промпт, разбор ответа нейросети, журнал.
 * Нейросеть и репозитории — моки.
 */
class NicheQueryGeneratorTest {

    private YandexLlmClient llm;
    private NicheLlmLogRepository logRepository;
    private NicheQueryGenerator generator;

    private StratumEntity blouse;
    private StratumEntity hoodie;
    private StratumEntity overall;
    private StratumEntity tshirt;

    @BeforeEach
    void setUp() {
        llm = mock(YandexLlmClient.class);
        StratumRepository stratumRepository = mock(StratumRepository.class);
        logRepository = mock(NicheLlmLogRepository.class);
        generator = new NicheQueryGenerator(llm, stratumRepository, logRepository);
        when(llm.getModel()).thenReturn("deepseek-v4-flash");

        blouse = stratum("Блузка", "f", "Блузка", "Блузка женская");
        hoodie = stratum("Толстовки и свитшоты", "f", "Худи", "Худи для девочки");
        overall = stratum("Комбинезоны", "baby", "Комбинезон", "Комбинезон детский");
        tshirt = stratum("Футболка", "m", "Футболка", "Футболка мужская");
        when(stratumRepository.findForNicheHints(any(), any(), any()))
                .thenReturn(List.of(blouse, hoodie, overall, tshirt));
    }

    // ---------- 1. Разбор ответа ----------
    @Test
    void parsesPlainJson() {
        llmAnswers("{\"search_query\": \"блузка женская белая\", \"strata\": [1]}");

        NicheQueryResult r = generator.generate(niche("clothing", true));

        assertThat(r.searchQuery()).isEqualTo("блузка женская белая");
        assertThat(r.strata()).containsExactly(blouse);
        NicheLlmLogEntity log = savedLog();
        assertThat(log.getSuccess()).isTrue();
        assertThat(log.getModel()).isEqualTo("deepseek-v4-flash");
        assertThat(log.getTokens()).isEqualTo(120);
        assertThat(log.getError()).isNull();
    }

    @Test
    void parsesJsonInMarkdownFence() {
        llmAnswers("```json\n{\"search_query\": \"блузка белая\", \"strata\": [1]}\n```");

        assertThat(generator.generate(niche("clothing", true)).searchQuery())
                .isEqualTo("блузка белая");
    }

    @Test
    void parsesJsonWithSurroundingText() {
        llmAnswers("Вот ответ: {\"search_query\": \"блузка белая\", \"strata\": []} Готово.");

        NicheQueryResult r = generator.generate(niche("clothing", true));
        assertThat(r.searchQuery()).isEqualTo("блузка белая");
        assertThat(r.strata()).isEmpty();
    }

    @Test
    void stripsQuotesAndExtraSpaces() {
        llmAnswers("{\"search_query\": \"«блузка    белая»\", \"strata\": []}");

        assertThat(generator.generate(niche("clothing", true)).searchQuery())
                .isEqualTo("блузка белая");
    }

    @Test
    void dropsInvalidAndDuplicateStrataIndexes() {
        llmAnswers("{\"search_query\": \"худи\", \"strata\": [0, 2, 99, 2]}");

        assertThat(generator.generate(niche("kids_clothing", false)).strata())
                .containsExactly(hoodie);
    }

    @Test
    void acceptsStringIndexes() {
        llmAnswers("{\"search_query\": \"комбинезон\", \"strata\": [\"1\", \"3\"]}");

        assertThat(generator.generate(niche("kids_clothing", false)).strata())
                .containsExactly(blouse, overall);
    }

    @Test
    void limitsToThreeStrata() {
        llmAnswers("{\"search_query\": \"одежда\", \"strata\": [1, 2, 3, 4]}");

        assertThat(generator.generate(niche("clothing", false)).strata())
                .containsExactly(blouse, hoodie, overall);
    }

    // ---------- 2. Непригодный ответ ----------
    @Test
    void emptyQueryFailsAndIsLogged() {
        llmAnswers("{\"search_query\": \"\", \"strata\": [1]}");

        assertThatThrownBy(() -> generator.generate(niche("clothing", true)))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("пустой search_query");
        NicheLlmLogEntity log = savedLog();
        assertThat(log.getSuccess()).isFalse();
        assertThat(log.getError()).startsWith("OTHER").contains("пустой search_query");
        assertThat(log.getResponse()).contains("search_query");
    }

    @Test
    void tooLongQueryFails() {
        llmAnswers("{\"search_query\": \"" + "а".repeat(121) + "\", \"strata\": []}");

        assertThatThrownBy(() -> generator.generate(niche("clothing", true)))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("длиннее 120");
    }

    @Test
    void answerWithoutJsonFails() {
        llmAnswers("Не могу определить запрос.");

        assertThatThrownBy(() -> generator.generate(niche("clothing", true)))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("нет JSON");
        assertThat(savedLog().getSuccess()).isFalse();
    }

    @Test
    void llmErrorIsLoggedWithKind() {
        when(llm.chat(anyString(), anyString()))
                .thenThrow(new LlmException(LlmException.Kind.TIMEOUT, "таймаут 60 с"));

        assertThatThrownBy(() -> generator.generate(niche("clothing", true)))
                .isInstanceOf(LlmException.class);
        NicheLlmLogEntity log = savedLog();
        assertThat(log.getSuccess()).isFalse();
        assertThat(log.getError()).startsWith("TIMEOUT");
        assertThat(log.getModel()).isEqualTo("deepseek-v4-flash");  // модель из настроек
        assertThat(log.getResponse()).isNull();
    }

    // ---------- 3. Промпт ----------
    @Test
    void userPromptForAdultClothingWithReference() {
        llmAnswers("{\"search_query\": \"блузка\", \"strata\": [1]}");

        generator.generate(niche("clothing", true));

        String user = capturedUserPrompt();
        assertThat(user)
                .contains("Категория: одежда (взрослая)")
                .contains("Описание пользователя: белая хлопковая блузка")
                .contains("- Название: Блузка женская хлопковая")
                .contains("- Раздел маркетплейса: Одежда/Женщинам/Блузки")
                .contains("- Характеристики: Цвет: белый; Состав: хлопок 100%")
                // у обёртки взрослой одежды предмет совпадает с подкатегорией — не дублируется
                .contains("1. женщины, Блузка (запрос «Блузка женская»)")
                .contains("4. мужчины, Футболка (запрос «Футболка мужская»)");
    }

    @Test
    void userPromptForKidsClothingLabels() {
        llmAnswers("{\"search_query\": \"худи\", \"strata\": [2]}");

        generator.generate(niche("kids_clothing", false));

        assertThat(capturedUserPrompt())
                .contains("Категория: детская одежда")
                .contains("2. девочки, Толстовки и свитшоты: Худи (запрос «Худи для девочки»)")
                .contains("3. малыши, Комбинезоны: Комбинезон")
                .contains("4. мальчики, Футболка");
    }

    @Test
    void userPromptWithoutReference() {
        llmAnswers("{\"search_query\": \"блузка\", \"strata\": []}");

        generator.generate(niche("clothing", false));

        assertThat(capturedUserPrompt()).contains("Карточки-образца нет.");
    }

    @Test
    void systemPromptHasMarketplaceAndKidsAgeRule() {
        llmAnswers("{\"search_query\": \"блузка\", \"strata\": []}");

        generator.generate(niche("clothing", false));

        ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
        verify(llm).chat(system.capture(), anyString());
        assertThat(system.getValue())
                .contains("маркетплейса ozon")
                .contains("для детской одежды обязательно укажи возраст");
    }

    // ---------- вспомогательное ----------
    private void llmAnswers(String content) {
        when(llm.chat(anyString(), anyString()))
                .thenReturn(new LlmResponse(content, "deepseek-v4-flash", 120));
    }

    private NicheLlmLogEntity savedLog() {
        ArgumentCaptor<NicheLlmLogEntity> captor = ArgumentCaptor.forClass(NicheLlmLogEntity.class);
        verify(logRepository).save(captor.capture());
        return captor.getValue();
    }

    private String capturedUserPrompt() {
        ArgumentCaptor<String> user = ArgumentCaptor.forClass(String.class);
        verify(llm).chat(anyString(), user.capture());
        return user.getValue();
    }

    private static NicheEntity niche(String category, boolean withReference) {
        MarketplaceEntity mp = new MarketplaceEntity();
        mp.setId((short) 1);
        mp.setTitle("ozon");
        CategoryEntity cat = new CategoryEntity();
        cat.setId((short) 1);
        cat.setTitle(category);
        GeoEntity geo = new GeoEntity();
        geo.setId((short) 1);
        geo.setTitle("Санкт-Петербург");

        NicheEntity n = new NicheEntity();
        n.setMarketplace(mp);
        n.setCategory(cat);
        n.setGeo(geo);
        n.setDescription("белая хлопковая блузка");

        if (withReference) {
            Map<String, String> chars = new LinkedHashMap<>();
            chars.put("Цвет", "белый");
            chars.put("Состав", "хлопок 100%");
            Map<String, Object> ref = new LinkedHashMap<>();
            ref.put("name", "Блузка женская хлопковая");
            ref.put("brand", "TestBrand");
            ref.put("category_path", "Одежда/Женщинам/Блузки");
            ref.put("characteristics", chars);
            ref.put("description", "Лёгкая блузка");
            n.setReferenceSnapshot(ref);
        }
        return n;
    }

    private static StratumEntity stratum(String subcategory, String segment,
                                         String item, String query) {
        SubcategoryEntity sc = new SubcategoryEntity();
        sc.setTitle(subcategory);
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("segment", segment);
        attrs.put("layer", 1);
        sc.setAttrs(attrs);

        StratumEntity st = new StratumEntity();
        st.setSubcategory(sc);
        st.setItem(item);
        st.setQuery(query);
        return st;
    }
}