package com.erp;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.*;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/** Inicialização e migração de uma base antiga, exclusivamente em PostgreSQL descartável. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=optional:classpath:/sem-config-local.properties",
        "erp.ia.ollama.enabled=false", "server.shutdown=immediate"})
class FusoHorarioIntegrationTest {
    static final EmbeddedPostgres POSTGRES;
    static {
        try {
            POSTGRES = EmbeddedPostgres.start();
            var source = POSTGRES.getPostgresDatabase();
            new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(source);
            var legado = new JdbcTemplate(source);
            legado.execute("ALTER TABLE vendas ALTER COLUMN data TYPE TIMESTAMP USING data AT TIME ZONE 'UTC'");
            legado.execute("ALTER TABLE compras ALTER COLUMN data TYPE TIMESTAMP USING data AT TIME ZONE 'UTC'");
            legado.execute("ALTER TABLE estoque ALTER COLUMN updated_at TYPE TIMESTAMP USING updated_at AT TIME ZONE 'UTC'");
            legado.execute("INSERT INTO empresas(id,nome_fantasia,plano_assinatura) VALUES (1,'Horários','PLUS')");
            legado.execute("INSERT INTO usuarios(id,empresa_id,nome,email,senha_hash) VALUES (1,1,'Operador','horas@teste.invalid','teste')");
            legado.execute("INSERT INTO vendas(id,empresa_id,usuario_id,origem,data,total) VALUES "
                    + "(1,1,1,'POS','2026-10-24 01:24:00',10),"
                    + "(2,1,1,'ERP','2026-10-23 00:00:00',20),"
                    + "(3,1,1,'POS','2026-10-24 03:00:00',30)");
            legado.execute("INSERT INTO compras(empresa_id,data) VALUES (1,'2026-10-23 00:00:00')");
        } catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }

    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
    }

    @MockitoBean CurrentUser user;
    @MockitoBean OllamaClient ollama;
    @Autowired JdbcTemplate db;
    @Autowired TestRestTemplate http;
    @Autowired FusoHorarioMigration migration;
    @Autowired DashboardController dashboard;

    @Test void migratesLegacyPosAndCommercialDatesOnlyOnce() {
        assertEquals(Instant.parse("2026-10-24T01:24:00Z"), instanteVenda(1));
        assertEquals(Instant.parse("2026-10-23T03:00:00Z"), instanteVenda(2));
        assertEquals(LocalDate.of(2026,10,23), db.queryForObject(
                "SELECT (data AT TIME ZONE 'America/Fortaleza')::date FROM compras", LocalDate.class));
        migration.afterPropertiesSet();
        migration.afterPropertiesSet();
        assertEquals(Instant.parse("2026-10-24T01:24:00Z"), instanteVenda(1));
        assertEquals(Instant.parse("2026-10-23T03:00:00Z"), instanteVenda(2));
        assertEquals("timestamp with time zone", db.queryForObject(
                "SELECT data_type FROM information_schema.columns WHERE table_name='estoque' AND column_name='updated_at'", String.class));
    }

    @Test void receiptJsonAndDayFilterKeepNightSaleOnCorrectDay() {
        when(user.empresa("Bearer teste")).thenReturn(1);
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("teste");
        var request = new HttpEntity<>(headers);
        var receipt = http.exchange("/api/pos/vendas/1", HttpMethod.GET, request, Map.class);
        assertEquals(HttpStatus.OK, receipt.getStatusCode());
        String data = (String) receipt.getBody().get("data");
        var local = OffsetDateTime.parse(data).atZoneSameInstant(ZoneId.of("America/Fortaleza"));
        assertEquals(LocalDateTime.of(2026,10,23,22,24), local.toLocalDateTime());
        var dia23 = http.exchange("/api/vendas?data=2026-10-23", HttpMethod.GET, request, List.class);
        assertEquals(HttpStatus.OK, dia23.getStatusCode());
        assertEquals(2, dia23.getBody().size());
        var dia24 = http.exchange("/api/vendas?data=2026-10-24", HttpMethod.GET, request, List.class);
        assertEquals(1, dia24.getBody().size());
        assertEquals(3, ((Number)((Map<?,?>)dia24.getBody().getFirst()).get("id")).intValue());
    }

    private Instant instanteVenda(int id) {
        return db.queryForObject("SELECT data FROM vendas WHERE id=?",
                (rs,n) -> rs.getObject(1, OffsetDateTime.class).toInstant(), id);
    }

    @Test void dashboardCountsNightSaleInItsCommercialMonth() {
        when(user.empresa("Bearer teste")).thenReturn(1);
        db.execute("INSERT INTO vendas(id,empresa_id,origem,data,total) VALUES (4,1,'POS','2026-11-01T01:24:00Z',40)");
        try {
            var resultado = dashboard.faturamento("Bearer teste", "2026-10");
            assertEquals(100d, ((Number) resultado.get("faturamentoAtual")).doubleValue());
            assertEquals(0d, ((Number) dashboard.faturamento("Bearer teste", "2026-11")
                    .get("faturamentoAtual")).doubleValue());
        } finally { db.update("DELETE FROM vendas WHERE id=4"); }
    }
}
