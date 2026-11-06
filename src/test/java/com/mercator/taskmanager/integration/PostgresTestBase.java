package com.mercator.taskmanager.integration;

import org.flywaydb.core.Flyway;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

/**
 * Базовый класс для тестов, которым нужны настоящие Postgres и Redis.
 *
 * Контейнеры — singleton: стартуют ОДИН раз на весь прогон тестов и не гасятся
 * между классами (Ryuk уберёт их после JVM). Так нет гонок «контейнер уже
 * останавливается» между тест-классами и не тратится время на переподъём.
 *
 * @DynamicPropertySource подменяет адреса БД в настройках Spring на адреса
 * этих контейнеров (перебивает явные URL из application-test.yml).
 *
 * Миграции накатываются здесь же, до подъёма Spring, и сразу заполняется
 * справочник маркетплейсов: RedisResultListener читает его в @PostConstruct
 * и поднимает потоки приёма результатов по числу площадок. На пустом
 * справочнике слушатель не стартует, и асинхронные тесты не получили бы
 * ни одного результата. Когда Spring поднимется, Flyway увидит применённые
 * миграции и пропустит их.
 *
 * Наследники помечаются @SpringBootTest — он и активирует Spring-контекст.
 */
public abstract class PostgresTestBase {

    static final PostgreSQLContainer<?> postgres;
    static final GenericContainer<?> redis;

    static {
        postgres = new PostgreSQLContainer<>("postgres:16");
        redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
        // Стартуем один раз; не вызываем stop() — контейнеры живут до конца JVM.
        postgres.start();
        redis.start();

        migrateAndSeed();
    }

    /** Накатить миграции и заполнить справочники до старта Spring. */
    private static void migrateAndSeed() {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        try (Connection conn = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement st = conn.createStatement()) {

            st.execute("INSERT INTO marketplace (title) VALUES ('ozon') "
                    + "ON CONFLICT (title) DO NOTHING");
            st.execute("INSERT INTO geo (title) VALUES ('Санкт-Петербург') "
                    + "ON CONFLICT (title) DO NOTHING");
            st.execute("INSERT INTO category (title) VALUES ('clothing') "
                    + "ON CONFLICT (title) DO NOTHING");

        } catch (Exception e) {
            throw new IllegalStateException("Не удалось заполнить справочники в тестовой базе", e);
        }
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }
}