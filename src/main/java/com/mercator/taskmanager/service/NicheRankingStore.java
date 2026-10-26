package com.mercator.taskmanager.service;

import com.mercator.taskmanager.entity.CardEntity;
import com.mercator.taskmanager.entity.NicheCandidateEntity;
import com.mercator.taskmanager.entity.NicheEntity;
import com.mercator.taskmanager.entity.NicheStatus;
import com.mercator.taskmanager.matching.CardFeatures;
import com.mercator.taskmanager.matching.DuplicateFilter;
import com.mercator.taskmanager.repository.CardRepository;
import com.mercator.taskmanager.repository.NicheCandidateRepository;
import com.mercator.taskmanager.repository.NicheRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Транзакционная часть ранжирования и автодобора ниши: чтение кандидатов,
 * сохранение схожести, применение отбора. Расчёт схожести и обращение к эмбеддеру —
 * вне транзакции, в NicheRankingService.
 *
 * Состав ниши — niche_card: карточка может входить в несколько ниш.
 * Карточка кандидата из выдачи создаётся при подборе (статус candidate);
 * отбор в нишу переводит её в active, вытеснение из последней ниши — обратно
 * в candidate. Если на маркетплейсе уже есть активная карточка с тем же sku,
 * в нишу идёт она.
 *
 * Подставленный размер модели (analog_sku отличается от sku карточки кандидата):
 * карточки этого размера ещё нет — место резервируется, по analog_sku уходит
 * проверочный парсинг (NicheVerificationService).
 */
@Service
public class NicheRankingStore {

    private final NicheRepository nicheRepository;
    private final NicheCandidateRepository candidateRepository;
    private final CardRepository cardRepository;

    public NicheRankingStore(NicheRepository nicheRepository,
                             NicheCandidateRepository candidateRepository,
                             CardRepository cardRepository) {
        this.nicheRepository = nicheRepository;
        this.candidateRepository = candidateRepository;
        this.cardRepository = cardRepository;
    }

    /**
     * Кандидат без ленивых связей — для работы вне транзакции.
     * content — полный набор карточки (card.content_txt), null — его нет.
     */
    public record CandidateView(UUID id, String sku, String source, UUID cardId,
                                Map<String, Object> content, String embedText, float[] embedding,
                                Double similarity, String status) {
    }

    /**
     * Схожесть кандидата для сохранения.
     *
     * @param similarity итоговая схожесть; null — кандидат отсеян фильтром
     * @param analogSku  sku нужного размера; null — идёт сама карточка кандидата
     */
    public record ScoreUpdate(UUID candidateId, Double similarity, Map<String, Object> details,
                              String rejectReason, String analogSku, String embedText, float[] embedding) {
    }

    /** Кандидат, прошедший фильтр, в порядке убывания схожести. */
    public record Choice(UUID candidateId, String analogSku, CardFeatures features) {
    }

    /** Задача проверочного парсинга: исторический кандидат или подставленный размер. */
    public record VerifyTask(UUID candidateId, UUID taskId, String sku) {
    }

    /**
     * Итог применения отбора (первичный подбор).
     *
     * @param selected в нише и на проверке
     * @param reserved из них отправлено на проверку в этом проходе
     * @param done     selected = plan-count, ниша active
     */
    public record ApplyResult(int selected, int added, int reserved, int removed, int skipped,
                              int duplicates, List<VerifyTask> toVerify, boolean done) {
    }

    /** Итог прохода автодобора. */
    public record RefillResult(int taken, int unavailable, int duplicates, List<VerifyTask> toVerify) {
    }

    @Transactional(readOnly = true)
    public List<CandidateView> loadCandidates(UUID nicheId) {
        List<CandidateView> views = new ArrayList<>();
        for (NicheCandidateEntity c : candidateRepository.findByNicheId(nicheId)) {
            CardEntity card = c.getCard();
            views.add(new CandidateView(
                    c.getId(), c.getSku(), c.getSource(),
                    card != null ? card.getId() : null,
                    card != null ? card.getContentTxt() : null,
                    c.getEmbedText(), c.getEmbedding(),
                    c.getSimilarity(), c.getStatus()));
        }
        return views;
    }

    /**
     * Сохранить схожесть кандидатов. Пока кандидат на проверке, analog_sku не меняется:
     * результат проверки должен относиться к тому sku, который отправлен.
     */
    @Transactional
    public void saveScores(List<ScoreUpdate> updates) {
        Map<UUID, ScoreUpdate> byId = new HashMap<>();
        for (ScoreUpdate u : updates) {
            byId.put(u.candidateId(), u);
        }
        for (NicheCandidateEntity c : candidateRepository.findAllById(byId.keySet())) {
            ScoreUpdate u = byId.get(c.getId());
            c.setSimilarity(u.similarity());
            c.setScoreDetails(u.details());
            c.setRejectReason(u.rejectReason());
            c.setEmbedText(u.embedText());
            c.setEmbedding(u.embedding());
            if (c.getVerifyTaskId() == null) {
                c.setAnalogSku(u.analogSku());
            }
        }
    }

    /** Флаг автодобора «есть что выбирать»: ставится при новых кандидатах и освободившихся местах. */
    @Transactional
    public void setRefillChoicePending(UUID nicheId, boolean pending) {
        nicheRepository.findById(nicheId).ifPresent(niche -> niche.setRefillChoicePending(pending));
    }

    /** Ниша не может быть подобрана (например, нет референса). */
    @Transactional
    public void failNiche(UUID nicheId, String error) {
        nicheRepository.findById(nicheId).ifPresent(niche -> {
            niche.setStatus(NicheStatus.FAILED);
            niche.setError(error);
            niche.setSelectTaskId(null);
        });
    }

    /**
     * Первичный подбор: кандидаты по убыванию схожести берутся, пока «в нише + на проверке»
     * меньше plan-count; дубли (DuplicateFilter) пропускаются с причиной. Ранее взятые,
     * не попавшие в первые plan-count, отсеянные фильтром или дублем, — выбывают.
     * Затем статус ниши: набрано plan-count — active, иначе selecting с очищенной задачей
     * (следующая пачка на недостающее число).
     *
     * @return null — ниша уже не в статусе ranking (например, удалена)
     */
    @Transactional
    public ApplyResult applySelection(UUID nicheId, List<Choice> ranked, DuplicateFilter duplicates) {
        NicheEntity niche = nicheRepository.findById(nicheId).orElse(null);
        if (niche == null || !NicheStatus.RANKING.equals(niche.getStatus())) {
            return null;
        }

        Map<UUID, NicheCandidateEntity> byId = candidatesById(nicheId);
        int planCount = niche.getPlanCount();
        int selected = 0, added = 0, reserved = 0, removed = 0, skipped = 0, duplicateCount = 0;
        List<VerifyTask> toVerify = new ArrayList<>();
        Set<UUID> passed = new HashSet<>();

        for (Choice choice : ranked) {
            NicheCandidateEntity c = byId.get(choice.candidateId());
            if (c == null) {
                continue;
            }
            passed.add(c.getId());
            boolean isSelected = "selected".equals(c.getStatus());
            boolean verifying = c.getVerifyTaskId() != null;

            if (selected >= planCount) {
                if (isSelected) {
                    detach(niche, c.getCard());
                    c.setStatus("candidate");
                    removed++;
                }
                continue;
            }

            String duplicate = duplicates.rejectReason(choice.features());
            if (duplicate != null) {
                c.setRejectReason(duplicate);
                duplicateCount++;
                if (isSelected) {
                    detach(niche, c.getCard());
                    c.setStatus("candidate");
                    removed++;
                }
                continue;
            }

            if (isSelected || verifying) {
                duplicates.take(choice.features());
                selected++;
                continue;
            }

            if (needsVerification(c, choice)) {
                toVerify.add(reserve(c, choice.analogSku()));
                duplicates.take(choice.features());
                selected++;
                reserved++;
                continue;
            }

            CardEntity card = attach(niche, c);
            if (card == null) {
                skipped++;
                continue;
            }
            c.setCard(card);
            c.setStatus("selected");
            duplicates.take(choice.features());
            selected++;
            added++;
        }

        // Ранее взятые, отсеянные теперь фильтром (другой вид, другой пол), — из ниши
        for (NicheCandidateEntity c : byId.values()) {
            if ("selected".equals(c.getStatus()) && !passed.contains(c.getId()) && c.getRejectReason() != null) {
                detach(niche, c.getCard());
                c.setStatus("candidate");
                removed++;
            }
        }

        boolean done = selected >= planCount;

        // Задача пачки обработана. При active — чтобы автодобор видел, что поиск
        // не идёт; при selecting — планировщик отправит следующую пачку.
        niche.setSelectTaskId(null);
        niche.setStatus(done ? NicheStatus.ACTIVE : NicheStatus.SELECTING);
        niche.setError(null);
        return new ApplyResult(selected, added, reserved, removed, skipped, duplicateCount, toVerify, done);
    }

    /**
     * Автодобор, уборка: карточки состава, выбывшие по жизненному циклу
     * (товар закончился, удалён, стабильно не парсится), убираются из ниши,
     * их кандидаты — unavailable (строка остаётся, чтобы sku был в exclude поиска).
     *
     * @return сколько карточек убрано
     */
    @Transactional
    public int cleanupDroppedMembers(UUID nicheId) {
        List<UUID> dropped = cardRepository.findDroppedMembers(nicheId);
        for (UUID cardId : dropped) {
            cardRepository.removeFromNiche(nicheId, cardId);
            cardRepository.findById(cardId)
                    .flatMap(card -> candidateRepository.findByNicheIdAndSku(nicheId, card.getSku()))
                    .ifPresent(c -> {
                        c.setStatus("unavailable");
                        c.setVerifyTaskId(null);
                        c.setVerifyRequestedAt(null);
                    });
        }
        return dropped.size();
    }

    /**
     * Автодобор: снять проверки без результата дольше порога (задача потеряна).
     * Кандидат остаётся candidate и снова участвует в выборе.
     *
     * @return сколько проверок снято
     */
    @Transactional
    public int releaseStaleVerifications(UUID nicheId, OffsetDateTime threshold) {
        int released = 0;
        for (NicheCandidateEntity c : candidateRepository.findByNicheId(nicheId)) {
            if (c.getVerifyTaskId() != null
                    && (c.getVerifyRequestedAt() == null || c.getVerifyRequestedAt().isBefore(threshold))) {
                c.setVerifyTaskId(null);
                c.setVerifyRequestedAt(null);
                released++;
            }
        }
        return released;
    }

    /**
     * Автодобор, выбор: проход по кандидатам (по убыванию схожести), пока есть места.
     * Текущий состав и кандидаты на проверке сначала передаются в фильтр задвоений.
     *  - дубль — пропуск с причиной;
     *  - подставлен другой размер модели — место резервируется, проверка по analog_sku;
     *  - у кандидата активная карточка (обходится, данные свежие) — сразу в нишу;
     *  - выбывшая карточка страты — unavailable;
     *  - новый кандидат последнего поиска (карточка candidate, кандидат создан
     *    не раньше freshSince) — сразу в нишу: данные только что спарсены;
     *  - исторический — место резервируется, проверка по своему sku.
     * Парсинг проверок отправляет вызывающий код.
     */
    @Transactional
    public RefillResult applyRefill(UUID nicheId, List<Choice> ranked, DuplicateFilter duplicates,
                                    int slots, OffsetDateTime freshSince) {
        NicheEntity niche = nicheRepository.findById(nicheId).orElse(null);
        if (niche == null || !NicheStatus.ACTIVE.equals(niche.getStatus())) {
            return new RefillResult(0, 0, 0, List.of());
        }

        Map<UUID, NicheCandidateEntity> byId = candidatesById(nicheId);
        for (Choice choice : ranked) {
            NicheCandidateEntity c = byId.get(choice.candidateId());
            if (c != null && ("selected".equals(c.getStatus()) || c.getVerifyTaskId() != null)) {
                duplicates.take(choice.features());
            }
        }

        int taken = 0, unavailable = 0, duplicateCount = 0;
        List<VerifyTask> toVerify = new ArrayList<>();

        for (Choice choice : ranked) {
            if (slots <= 0) {
                break;
            }
            NicheCandidateEntity c = byId.get(choice.candidateId());
            if (c == null || !"candidate".equals(c.getStatus()) || c.getVerifyTaskId() != null) {
                continue;
            }

            String duplicate = duplicates.rejectReason(choice.features());
            if (duplicate != null) {
                c.setRejectReason(duplicate);
                duplicateCount++;
                continue;
            }

            // Подставлен другой размер модели — карточки этого размера ещё нет
            if (needsVerification(c, choice)) {
                toVerify.add(reserve(c, choice.analogSku()));
                duplicates.take(choice.features());
                slots--;
                continue;
            }

            CardEntity card = c.getCard();

            // Карточка обходится — данные свежие, проверка не нужна
            if (card != null && "active".equals(card.getStatus())) {
                cardRepository.addToNiche(nicheId, card.getId());
                c.setStatus("selected");
                duplicates.take(choice.features());
                taken++;
                slots--;
                continue;
            }

            // Карточка страты выбыла — товара нет
            if (card != null && "stratum".equals(c.getSource())) {
                c.setStatus("unavailable");
                unavailable++;
                continue;
            }

            // Новый кандидат последнего поиска — данные только что спарсены
            boolean fresh = (card == null || "candidate".equals(card.getStatus()))
                    && freshSince != null && c.getCreatedAt() != null
                    && !c.getCreatedAt().isBefore(freshSince);
            if (fresh) {
                CardEntity attached = attach(niche, c);
                if (attached != null) {
                    c.setCard(attached);
                    c.setStatus("selected");
                    duplicates.take(choice.features());
                    taken++;
                    slots--;
                }
                continue;
            }

            // Исторический — проверочный парсинг своего sku, место резервируется
            toVerify.add(reserve(c, null));
            duplicates.take(choice.features());
            slots--;
        }
        return new RefillResult(taken, unavailable, duplicateCount, toVerify);
    }

    /** Нужна проверка: подставлен размер, которого у кандидата нет карточки. */
    private static boolean needsVerification(NicheCandidateEntity c, Choice choice) {
        String cardSku = c.getCard() != null ? c.getCard().getSku() : c.getSku();
        return choice.analogSku() != null && !choice.analogSku().equals(cardSku);
    }

    /**
     * Зарезервировать место и задачу проверки.
     *
     * @param analogSku sku подставленного размера; null — проверяется своя карточка
     */
    private static VerifyTask reserve(NicheCandidateEntity c, String analogSku) {
        UUID taskId = UUID.randomUUID();
        c.setVerifyTaskId(taskId);
        c.setVerifyRequestedAt(OffsetDateTime.now());
        c.setAnalogSku(analogSku);
        String sku = analogSku != null ? analogSku
                : c.getCard() != null ? c.getCard().getSku() : c.getSku();
        return new VerifyTask(c.getId(), taskId, sku);
    }

    private Map<UUID, NicheCandidateEntity> candidatesById(UUID nicheId) {
        Map<UUID, NicheCandidateEntity> byId = new HashMap<>();
        for (NicheCandidateEntity c : candidateRepository.findByNicheId(nicheId)) {
            byId.put(c.getId(), c);
        }
        return byId;
    }

    /**
     * Включить кандидата в нишу. Карточка-кандидат (или кандидат без карточки,
     * созданный до перехода на карточки candidate): если на маркетплейсе уже есть
     * активная карточка с этим sku — берётся она, иначе карточка-кандидат
     * переходит в active (попадает в обход).
     *
     * @return null — взять нельзя: карточка выбыла из обхода или карточки нет
     *         (кандидат без карточки — unavailable)
     */
    private CardEntity attach(NicheEntity niche, NicheCandidateEntity c) {
        CardEntity card = c.getCard();
        if (card == null || "candidate".equals(card.getStatus())) {
            Optional<CardEntity> active = cardRepository
                    .findBySkuAndMarketplace(c.getSku(), niche.getMarketplace().getId())
                    .filter(found -> "active".equals(found.getStatus()));
            if (active.isPresent()) {
                card = active.get();
            }
        }
        if (card == null) {
            c.setStatus("unavailable");
            return null;
        }
        if ("candidate".equals(card.getStatus())) {
            card.setStatus("active");  // отобрана в нишу — в обход
        } else if (!"active".equals(card.getStatus())) {
            return null;  // выбыла из обхода
        }
        cardRepository.addToNiche(niche.getId(), card.getId());
        return card;
    }

    /**
     * Карточка выпала из выборки ниши: убрать из состава. Карточка без страты,
     * больше не входящая ни в одну нишу, становится кандидатом (из обхода уходит,
     * в базе остаётся).
     */
    private void detach(NicheEntity niche, CardEntity card) {
        if (card == null) {
            return;
        }
        cardRepository.removeFromNiche(niche.getId(), card.getId());
        if (card.getStratum() == null
                && cardRepository.countNiches(card.getId()) == 0
                && !"dropped".equals(card.getStatus())
                && !"candidate".equals(card.getStatus())) {
            card.setStatus("candidate");
        }
    }
}