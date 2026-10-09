package com.mercator.taskmanager.matching;

import com.mercator.taskmanager.embedding.EmbedderClient;
import com.mercator.taskmanager.matching.CardFeatures.ModelSize;
import com.mercator.taskmanager.matching.NicheMatchingProperties.Weights;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Схожесть кандидатов ниши с референсом.
 *
 * 1. Жёсткий фильтр: та же группа вида изделия и совместимый пол.
 * 2. Текст кандидата и референса — по признакам, заполненным у референса; эмбеддинг
 *    пересчитывается, только если текст изменился (иначе — сохранённый вектор).
 * 3. Итоговая схожесть S — взвешенная сумма составляющих, которые есть у пары:
 *    текст (растянутый косинус), цена за штуку, размер (с подстановкой нужного размера модели),
 *    доля натуральных, температура; веса отсутствующих составляющих перераспределяются.
 *
 * Перенос ячейки 21 ноутбука разбора признаков. Отсев задвоений — DuplicateFilter.
 */
@Component
public class NicheScorer {

    public static final String REJECT_TYPE = "другой вид изделия";
    public static final String REJECT_GENDER = "другой пол";

    private final NicheMatchingConfig config;
    private final EmbedderClient embedder;

    public NicheScorer(NicheMatchingConfig config, EmbedderClient embedder) {
        this.config = config;
        this.embedder = embedder;
    }

    /** Кандидат ниши: признаки карточки и сохранённые текст и вектор (могут быть пусты). */
    public record Candidate(UUID id, CardFeatures features, String storedText, float[] storedVector) {
    }

    /** Составляющие схожести, каждая 0–1; null — не считается для пары. */
    public record Parts(Double text, Double price, Double size, Double natural, Double temperature) {
    }

    /**
     * Результат по кандидату.
     *
     * @param score           итоговая схожесть; null — кандидат отсеян фильтром
     * @param analogSku       sku, который пойдёт в нишу (нужный размер модели или сама карточка)
     * @param sizeLabel       подпись подставленного размера; null — идёт сама карточка
     * @param analogUnitPrice цена аналога за штуку
     * @param text            текст по признакам референса; пусто — общих признаков нет
     * @param vector          вектор текста; null — текста нет или кандидат отсеян
     * @param rejectReason    причина отсева фильтром; null — прошёл
     */
    public record Scored(UUID id, CardFeatures features, Double score, Parts parts, Double cosine,
                         String analogSku, String sizeLabel, Double analogUnitPrice,
                         String text, float[] vector, String rejectReason) {

        public boolean passed() {
            return rejectReason == null;
        }
    }

    /** Размер аналога: близость, sku, подпись размера, цена за штуку. */
    record SizeChoice(Double similarity, String sku, String label, Double unitPrice) {
    }

    /** Все кандидаты: прошедшие фильтр — по убыванию схожести, затем отсеянные. */
    public List<Scored> score(CardFeatures reference, List<Candidate> candidates) {
        List<String> refKeys = reference.filledKeys();
        String refText = reference.textOn(refKeys);

        List<Candidate> passed = new ArrayList<>();
        List<Scored> rejected = new ArrayList<>();
        for (Candidate c : candidates) {
            String reason = filterReason(reference, c.features());
            if (reason == null) {
                passed.add(c);
            } else {
                rejected.add(new Scored(c.id(), c.features(), null, null, null, c.features().sku(), null,
                        c.features().unitPrice(), null, null, reason));
            }
        }

        // Тексты по признакам референса; эмбеддинг — только для новых и изменившихся текстов
        Map<UUID, String> texts = new HashMap<>();
        Set<String> toEmbed = new LinkedHashSet<>();
        if (!refText.isEmpty()) {
            toEmbed.add(refText);
        }
        for (Candidate c : passed) {
            String text = c.features().textOn(refKeys);
            texts.put(c.id(), text);
            boolean reuse = c.storedVector() != null && text.equals(c.storedText());
            if (!refText.isEmpty() && !text.isEmpty() && !reuse) {
                toEmbed.add(text);
            }
        }
        Map<String, float[]> vectors = new HashMap<>();
        if (!toEmbed.isEmpty()) {
            List<String> list = new ArrayList<>(toEmbed);
            List<float[]> embedded = embedder.embed(list);
            for (int i = 0; i < list.size(); i++) {
                vectors.put(list.get(i), embedded.get(i));
            }
        }
        float[] refVector = vectors.get(refText);

        List<Scored> scored = new ArrayList<>();
        for (Candidate c : passed) {
            String text = texts.get(c.id());
            float[] vector = null;
            Double cosine = null;
            if (refVector != null && !text.isEmpty()) {
                vector = vectors.containsKey(text) ? vectors.get(text) : c.storedVector();
                cosine = EmbedderClient.similarity(refVector, vector);
            }
            scored.add(scoreOne(reference, c, text, vector, cosine));
        }
        scored.sort(Comparator.comparing(Scored::score, Comparator.reverseOrder()));
        scored.addAll(rejected);
        return scored;
    }

    private Scored scoreOne(CardFeatures ref, Candidate c, String text, float[] vector, Double cosine) {
        CardFeatures cand = c.features();
        SizeChoice size = bestSize(ref, cand);
        Parts parts = new Parts(
                cosine == null ? null : textScore(cosine),
                priceSimilarity(ref.unitPrice(), size.unitPrice()),
                size.similarity(),
                naturalSimilarity(ref.naturalShare(), cand.naturalShare()),
                temperatureSimilarity(ref, cand));
        return new Scored(c.id(), cand, weighted(parts), parts, cosine, size.sku(), size.label(),
                size.unitPrice(), text, vector, null);
    }

    /** Причина отсева жёстким фильтром или null. */
    static String filterReason(CardFeatures ref, CardFeatures cand) {
        if (!Objects.equals(ref.group(), cand.group())) {
            return REJECT_TYPE;
        }
        if (!gendersMatch(ref.genders(), cand.genders())) {
            return REJECT_GENDER;
        }
        return null;
    }

    /**
     * «Для мальчиков» не выдаётся к вещи «для девочек» и наоборот; вещь для всех подходит
     * к любой. Пол неизвестен — фильтр не применяется.
     */
    static boolean gendersMatch(Set<String> ref, Set<String> cand) {
        if (ref.isEmpty() || cand.isEmpty()) {
            return true;
        }
        for (String g : cand) {
            if (ref.contains(g)) {
                return true;
            }
        }
        return false;
    }

    /** Взвешенная сумма составляющих, которые есть у пары; веса отсутствующих перераспределяются. */
    Double weighted(Parts p) {
        Weights w = config.weights();
        double sum = 0;
        double weights = 0;
        double[][] pairs = {
                {w.text(), p.text() == null ? Double.NaN : p.text()},
                {w.price(), p.price() == null ? Double.NaN : p.price()},
                {w.size(), p.size() == null ? Double.NaN : p.size()},
                {w.natural(), p.natural() == null ? Double.NaN : p.natural()},
                {w.temperature(), p.temperature() == null ? Double.NaN : p.temperature()}};
        for (double[] pair : pairs) {
            if (!Double.isNaN(pair[1])) {
                sum += pair[0] * pair[1];
                weights += pair[0];
            }
        }
        return weights == 0 ? 0.0 : sum / weights;
    }

    /** Косинус, растянутый на 0–1: text-sim-floor → 0, text-sim-ceil → 1. */
    double textScore(double cosine) {
        double s = (cosine - config.textSimFloor()) / (config.textSimCeil() - config.textSimFloor());
        return Math.min(1.0, Math.max(0.0, s));
    }

    /** 1 − |ln(p / p_ref)| / ln(price-zero-ratio), не меньше 0; цены за штуку. */
    Double priceSimilarity(Double refPrice, Double price) {
        if (refPrice == null || price == null || refPrice == 0 || price == 0) {
            return null;
        }
        return Math.max(0.0, 1 - Math.abs(Math.log(price / refPrice)) / Math.log(config.priceZeroRatio()));
    }

    /** Пересечение размеров — 1; соседний размер (6 см роста, 1 см стопы) — 0,5; дальше — 0. */
    static double sizeGapSimilarity(double refMin, double refMax, double min, double max, SizeScale scale) {
        double gap = Math.max(0.0, Math.max(min - refMax, refMin - max));
        double step = scale == SizeScale.FOOT ? SizeNormalizer.FOOT_STEP : SizeNormalizer.HEIGHT_STEP;
        return gap == 0 ? 1.0 : gap <= step ? 0.5 : 0.0;
    }

    /**
     * Размер аналога: сама карточка или размер её модели в наличии, ближайший к референсу.
     * Нашёлся нужный или соседний размер — аналогом идёт sku этого размера, с его ценой за штуку;
     * иначе остаётся сама карточка с близостью размера 0.
     */
    static SizeChoice bestSize(CardFeatures ref, CardFeatures cand) {
        if (ref.sizeMin() == null || ref.sizeMax() == null || ref.sizeScale() != cand.sizeScale()) {
            return new SizeChoice(null, cand.sku(), null, cand.unitPrice());
        }
        SizeChoice best = null;
        if (cand.sizeMin() != null && cand.sizeMax() != null) {
            best = new SizeChoice(sizeGapSimilarity(ref.sizeMin(), ref.sizeMax(), cand.sizeMin(), cand.sizeMax(),
                    ref.sizeScale()), cand.sku(), null, cand.unitPrice());
        }
        for (ModelSize m : cand.modelSizes()) {
            double sim = sizeGapSimilarity(ref.sizeMin(), ref.sizeMax(), m.min(), m.max(), ref.sizeScale());
            if (best == null || sim > best.similarity()) {           // при равенстве остаётся сама карточка
                best = new SizeChoice(sim, m.sku(), m.label(),
                        m.unitPrice() != null ? m.unitPrice() : cand.unitPrice());
            }
        }
        if (best == null) {
            return new SizeChoice(null, cand.sku(), null, cand.unitPrice());
        }
        if (best.similarity() == 0) {                                   // нужного размера нет
            return new SizeChoice(0.0, cand.sku(), null, cand.unitPrice());
        }
        return best;
    }

    /** По границам, которые есть у обеих карточек: средняя разница / temp-zero-diff. */
    Double temperatureSimilarity(CardFeatures ref, CardFeatures cand) {
        List<Double> diffs = new ArrayList<>();
        if (ref.tempMin() != null && cand.tempMin() != null) {
            diffs.add(Math.abs(ref.tempMin() - cand.tempMin()));
        }
        if (ref.tempMax() != null && cand.tempMax() != null) {
            diffs.add(Math.abs(ref.tempMax() - cand.tempMax()));
        }
        if (diffs.isEmpty()) {
            return null;
        }
        double mean = diffs.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        return Math.max(0.0, 1 - mean / config.tempZeroDiff());
    }

    static Double naturalSimilarity(Double ref, Double cand) {
        return ref == null || cand == null ? null : 1 - Math.abs(ref - cand) / 100;
    }
}