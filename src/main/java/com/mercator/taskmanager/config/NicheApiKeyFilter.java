package com.mercator.taskmanager.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * Проверка межсервисного ключа для эндпоинтов ниш (/api/v1/niches...) и админки
 * (/api/v1/admin... — вызывает шлюз сервиса аналитики). Заголовок Authorization:
 * Bearer <ключ>, ключ — PARSER_API_KEY (единый межсервисный ключ на этой машине).
 * Остальные эндпоинты не затрагиваются.
 *
 * Ключ не задан — защищённые эндпоинты закрыты (500), а не открыты.
 */
@Component
public class NicheApiKeyFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(NicheApiKeyFilter.class);

    private static final List<String> PATH_PREFIXES = List.of("/api/v1/niches", "/api/v1/admin");
    private static final String BEARER = "Bearer ";

    private final byte[] expectedKey;

    public NicheApiKeyFilter(@Value("${parser.ozon.api-key:}") String apiKey) {
        this.expectedKey = apiKey == null ? new byte[0] : apiKey.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return PATH_PREFIXES.stream().noneMatch(uri::startsWith);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (expectedKey.length == 0) {
            log.error("PARSER_API_KEY не задан — эндпоинты ниш и админки закрыты");
            reject(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "Сервис не сконфигурирован: отсутствует PARSER_API_KEY");
            return;
        }

        String header = request.getHeader("Authorization");
        // MessageDigest.isEqual — сравнение за постоянное время
        boolean valid = header != null
                && header.startsWith(BEARER)
                && MessageDigest.isEqual(
                header.substring(BEARER.length()).getBytes(StandardCharsets.UTF_8),
                expectedKey);

        if (!valid) {
            log.warn("Отклонён запрос {} {}: неверный или отсутствующий ключ",
                    request.getMethod(), request.getRequestURI());
            reject(response, HttpServletResponse.SC_UNAUTHORIZED, "Invalid or missing API key");
            return;
        }

        chain.doFilter(request, response);
    }

    private static void reject(HttpServletResponse response, int status, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }
}