package com.mercator.taskmanager.service;

import com.mercator.taskmanager.contract.Ozon.OzonCard;
import com.mercator.taskmanager.entity.CardEntity;
import com.mercator.taskmanager.entity.NicheCandidateEntity;
import com.mercator.taskmanager.entity.NicheEntity;
import com.mercator.taskmanager.entity.NicheStatus;
import com.mercator.taskmanager.redis.ParseResultMessage;
import com.mercator.taskmanager.repository.CardRepository;
import com.mercator.taskmanager.repository.NicheCandidateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.UUID;

/**
 * Результат проверочного парсинга кандидата ниши: исторический кандидат автодобора
 * или подставленный размер модели (analog_sku).
 *
 * Проверяется sku: analog_sku, если подставлен размер, иначе sku карточки кандидата.
 * В наличии и есть цена — кандидат входит в нишу: выбывшая карточка возвращается
 * в обход (замер + снимок, markInStock) как член ниши — в страты не возвращается
 * (членство закрыто при выбытии, страту добрали заменой); карточки нет — создаётся
 * новая. Карточка товара на маркетплейсе одна (V19). Иначе — unavailable.
 * Антибот — проверка снимается, кандидат снова участвует в выборе.
 *
 * Проверка подставленного размера может вернуться ещё во время первичного подбора,
 * поэтому результат принимается в статусах ranking, selecting и active.
 *
 * Задача проверки идёт с полным разбором: новая карточка получает content_txt
 * при создании, существующая — через writeFullContent.
 */
@Service
public class NicheVerificationService {

    private static final Logger log = LoggerFactory.getLogger(NicheVerificationService.class);
    private static final Set<String> ACCEPTING = Set.of(NicheStatus.RANKING, NicheStatus.SELECTING, NicheStatus.ACTIVE);

    private final NicheCandidateRepository candidateRepository;
    private final CardRepository cardRepository;
    private final CardWriteService cardWriteService;
    private final CardLifecycleService lifecycle;

    public NicheVerificationService(NicheCandidateRepository candidateRepository,
                                    CardRepository cardRepository,
                                    CardWriteService cardWriteService,
                                    CardLifecycleService lifecycle) {
        this.candidateRepository = candidateRepository;
        this.cardRepository = cardRepository;
        this.cardWriteService = cardWriteService;
        this.lifecycle = lifecycle;
    }

    /** Принадлежит ли результат обхода проверке кандидата ниши. */
    public boolean isVerifyTask(UUID taskId) {
        return taskId != null && candidateRepository.existsByVerifyTaskId(taskId);
    }

    @Transactional
    public void handleResult(ParseResultMessage result) {
        NicheCandidateEntity c = candidateRepository.findByVerifyTaskId(result.getTaskId()).orElse(null);
        if (c == null) {
            return;
        }
        NicheEntity niche = c.getNiche();
        c.setVerifyTaskId(null);
        c.setVerifyRequestedAt(null);

        boolean substituted = c.getAnalogSku() != null;
        String sku = substituted ? c.getAnalogSku()
                : c.getCard() != null ? c.getCard().getSku() : c.getSku();

        if (!ACCEPTING.contains(niche.getStatus())) {
            log.info("Ниша id={}: результат проверки sku={} пропущен, статус ниши {}",
                    niche.getId(), sku, niche.getStatus());
            return;
        }

        // ---------- 1. Антибот — кандидат снова участвует в выборе ----------
        if ("antibot_blocked".equals(result.getErrorCode())) {
            log.warn("Ниша id={}: проверка sku={} заблокирована антиботом (parser_id={}), "
                    + "место освобождено", niche.getId(), sku, result.getParserId());
            markChoicePending(niche);
            return;
        }

        // ---------- 2. Не проходит — unavailable ----------
        OzonCard card = result.getCard();
        String failure = failureReason(result, card);
        if (failure != null) {
            c.setStatus("unavailable");
            log.info("Ниша id={}: кандидат sku={} не прошёл проверку ({}), unavailable",
                    niche.getId(), sku, failure);
            markChoicePending(niche);
            return;
        }

        // ---------- 3. Проходит — в нишу ----------
        String geo = niche.getGeo().getTitle();
        String parserId = result.getParserId() != null ? result.getParserId() : "";
        Short marketplaceId = niche.getMarketplace().getId();

        // подставленный размер — карточка размера, иначе карточка кандидата;
        // карточка товара на маркетплейсе одна (sku уникален)
        CardEntity target = !substituted && c.getCard() != null
                ? c.getCard()
                : cardRepository.findBySkuAndMarketplace(sku, marketplaceId).orElse(null);

        if (target == null) {
            // новая карточка: content_txt пишется при создании
            target = cardWriteService.saveNicheCard(niche, geo, card, parserId);
            log.info("Ниша id={}: кандидат sku={} прошёл проверку, создана карточка{}",
                    niche.getId(), sku, substituted ? " (подставленный размер)" : "");
        } else {
            if (!"active".equals(target.getStatus())) {
                cardWriteService.writeMeasurementAndSnapshot(target.getId(), geo, card, parserId);
                lifecycle.markInStock(target.getId());
                log.info("Ниша id={}: кандидат sku={} прошёл проверку, карточка возвращена в обход",
                        niche.getId(), sku);
            } else {
                log.info("Ниша id={}: кандидат sku={} прошёл проверку, карточка уже в обходе",
                        niche.getId(), sku);
            }
            // существующая карточка: полный набор из результата проверки
            cardWriteService.writeFullContent(target.getId(), card,
                    OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS));
        }

        cardRepository.addToNiche(niche.getId(), target.getId());
        c.setCard(target);
        c.setStatus("selected");
    }

    /**
     * Место в нише освободилось (кандидат недоступен или проверка снята) - автодобор
     * выберет следующего кандидата на ближайшем тике. Только у active-ниши: при
     * первичном подборе выбор делает ранжирование.
     */
    private static void markChoicePending(NicheEntity niche) {
        if (NicheStatus.ACTIVE.equals(niche.getStatus())) {
            niche.setRefillChoicePending(true);
        }
    }

    /** Причина непрохождения проверки или null, если кандидат пригоден. */
    private static String failureReason(ParseResultMessage result, OzonCard card) {
        if (Boolean.FALSE.equals(result.getOk()) || card == null) {
            String error = result.getError() != null ? result.getError() : result.getErrorCode();
            return "ошибка парсинга: " + error;
        }
        if ("not_found".equals(card.getButtonState())) {
            return "страница удалена";
        }
        if ("out_of_stock".equals(card.getButtonState())) {
            return "нет в наличии";
        }
        if (card.getPrice() == null
                || (card.getPrice().getCardPrice() == null && card.getPrice().getPrice() == null)) {
            return "нет цены";
        }
        return null;
    }
}