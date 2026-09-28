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

import java.util.UUID;

/**
 * Результат проверочного парсинга исторического кандидата ниши (автодобор).
 *
 * В наличии и есть цена — кандидат входит в нишу: выбывшая карточка ниши
 * возвращается в обход (замер + снимок, markInStock), выбывшая карточка страты
 * не возвращается (её жизненный цикл решает категория) — создаётся своя,
 * карточки нет — создаётся новая. Иначе — unavailable.
 * Антибот — проверка снимается, кандидат снова участвует в выборе.
 */
@Service
public class NicheVerificationService {

    private static final Logger log = LoggerFactory.getLogger(NicheVerificationService.class);

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

        if (!NicheStatus.ACTIVE.equals(niche.getStatus())) {
            log.info("Ниша id={}: результат проверки sku={} пропущен, статус ниши {}",
                    niche.getId(), c.getSku(), niche.getStatus());
            return;
        }

        // ---------- 1. Антибот — кандидат снова участвует в выборе ----------
        if ("antibot_blocked".equals(result.getErrorCode())) {
            log.warn("Ниша id={}: проверка sku={} заблокирована антиботом (parser_id={}), "
                    + "место освобождено", niche.getId(), c.getSku(), result.getParserId());
            return;
        }

        // ---------- 2. Не проходит — unavailable ----------
        OzonCard card = result.getCard();
        String failure = failureReason(result, card);
        if (failure != null) {
            c.setStatus("unavailable");
            log.info("Ниша id={}: кандидат sku={} не прошёл проверку ({}), unavailable",
                    niche.getId(), c.getSku(), failure);
            return;
        }

        // ---------- 3. Проходит — в нишу ----------
        String geo = niche.getGeo().getTitle();
        String parserId = result.getParserId() != null ? result.getParserId() : "";

        CardEntity target = c.getCard() != null
                ? c.getCard()
                : cardRepository.findBySkuAndMarketplace(c.getSku(), niche.getMarketplace().getId())
                .orElse(null);
        // выбывшую карточку страты не возвращаем: её жизненный цикл решает категория
        if (target != null && target.getStratum() != null && !"active".equals(target.getStatus())) {
            target = null;
        }

        if (target == null) {
            target = cardWriteService.saveNicheCard(niche, geo, card, parserId);
            log.info("Ниша id={}: кандидат sku={} прошёл проверку, создана карточка",
                    niche.getId(), c.getSku());
        } else if (!"active".equals(target.getStatus())) {
            cardWriteService.writeMeasurementAndSnapshot(target.getId(), geo, card, parserId);
            lifecycle.markInStock(target.getId());
            log.info("Ниша id={}: кандидат sku={} прошёл проверку, карточка возвращена в обход",
                    niche.getId(), c.getSku());
        } else {
            log.info("Ниша id={}: кандидат sku={} прошёл проверку, карточка уже в обходе",
                    niche.getId(), c.getSku());
        }

        cardRepository.addToNiche(niche.getId(), target.getId());
        c.setCard(target);
        c.setStatus("selected");
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