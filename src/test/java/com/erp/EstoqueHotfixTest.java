package com.erp;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * Desafio 5 - Testes rigorosos (Módulo de Qualidade).
 *
 * Testes de regressão do bug de "estoque fantasma" (saída de estoque menor que a
 * quantidade vendida), executados em um PostgreSQL embutido e descartável, sem
 * tocar no banco configurado localmente:
 *   1. 500 vendas de 5 produtos: estoque exato, auditoria sem divergência e performance;
 *   2. venda acima do estoque é recusada e não altera nada;
 *   3. a segunda verificação cancela a venda quando o saldo gravado não bate;
 *   4. cenário do incidente (100 de entrada, 10 vendas de 10) termina com estoque 0.
 */
@SpringBootTest(properties = {"spring.config.import=optional:classpath:/sem-config-local.properties",
        "erp.ia.ollama.enabled=false"})
class EstoqueHotfixTest {
    static final EmbeddedPostgres POSTGRES;
    static { try { POSTGRES = EmbeddedPostgres.start(); } catch (Exception e) { throw new ExceptionInInitializerError(e); } }
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
    }

    private static final String TOKEN = "T";
    private static final int TOTAL_VENDAS = 500;
    private static final int TOTAL_PRODUTOS = 5;
    private static final int ESTOQUE_INICIAL = 250;
    /** Banco local embutido: cada venda deve levar bem menos que isso. */
    private static final double LIMITE_MEDIA_POR_VENDA_MS = 250;

    @MockitoBean CurrentUser user;
    @MockitoBean OllamaClient ollama;
    @Autowired JdbcTemplate db;
    @Autowired MovimentoController movimentos;
    @Autowired AuditoriaEstoqueController auditoria;

    int empresa, cliente, fornecedor;
    final List<Integer> produtos = new ArrayList<>();

    @BeforeEach void setup() {
        db.execute("DROP TRIGGER IF EXISTS trg_simula_bug ON estoque");
        db.execute("TRUNCATE empresas CASCADE");
        produtos.clear();
        empresa = db.queryForObject("INSERT INTO empresas(nome_fantasia) VALUES ('Teste Desafio 5') RETURNING id", Integer.class);
        int usuario = db.queryForObject("INSERT INTO usuarios(empresa_id,nome,email,senha_hash) VALUES (?,'Operador','desafio5@teste.local','x') RETURNING id", Integer.class, empresa);
        when(user.empresa(TOKEN)).thenReturn(empresa);
        when(user.usuario(TOKEN)).thenReturn(usuario);
        cliente = db.queryForObject("INSERT INTO clientes(empresa_id,nome) VALUES (?,'Cliente') RETURNING id", Integer.class, empresa);
        fornecedor = db.queryForObject("INSERT INTO fornecedores(empresa_id,nome) VALUES (?,'Fornecedor') RETURNING id", Integer.class, empresa);
        for (int i = 1; i <= TOTAL_PRODUTOS; i++) {
            int id = db.queryForObject("INSERT INTO produtos(empresa_id,nome,codigo,preco,preco_compra,preco_venda) VALUES (?,?,?,10,5,10) RETURNING id",
                    Integer.class, empresa, "Produto " + i, "P" + i);
            db.update("INSERT INTO estoque(empresa_id,produto_id,quantidade,estoque_minimo) VALUES (?,?,0,0)", empresa, id);
            produtos.add(id);
        }
    }

    @AfterEach void limparTrigger() {
        db.execute("DROP TRIGGER IF EXISTS trg_simula_bug ON estoque");
    }

    @Test
    @DisplayName("500 vendas de produtos diferentes: estoque exato, auditoria sem divergência e performance")
    void quinhentasVendas() {
        comprar(ESTOQUE_INICIAL, produtos.toArray(Integer[]::new));

        Map<Integer, Integer> vendido = new HashMap<>();
        long inicio = System.currentTimeMillis();
        for (int i = 0; i < TOTAL_VENDAS; i++) {
            int produto = produtos.get(i % TOTAL_PRODUTOS);
            int quantidade = 1 + (i / TOTAL_PRODUTOS) % 3;
            vender(produto, quantidade);
            vendido.merge(produto, quantidade, Integer::sum);
        }
        long duracao = System.currentTimeMillis() - inicio;
        double media = duracao / (double) TOTAL_VENDAS;

        System.out.printf("%n=== TESTE 500 VENDAS ===%nTempo total: %.1f s | Média por venda: %.1f ms%n", duracao / 1000.0, media);
        for (int produto : produtos) {
            int esperado = ESTOQUE_INICIAL - vendido.get(produto);
            int gravado = estoque(produto);
            System.out.printf("Produto %d: entrada=%d vendido=%d esperado=%d gravado=%d%n",
                    produto, ESTOQUE_INICIAL, vendido.get(produto), esperado, gravado);
            assertEquals(esperado, gravado, "Estoque incorreto para o produto " + produto);
        }
        for (Map<String, Object> linha : auditoria.auditoria(TOKEN)) {
            assertEquals("OK", linha.get("situacao"), "Auditoria acusou divergência: " + linha);
        }
        assertEquals(TOTAL_VENDAS, db.queryForObject("SELECT COUNT(*) FROM vendas WHERE empresa_id=?", Integer.class, empresa));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM movimentacoes_estoque WHERE empresa_id=? AND saldo_conferido IS NULL", Integer.class, empresa),
                "Toda movimentação deve gravar o saldo conferido");
        assertTrue(media <= LIMITE_MEDIA_POR_VENDA_MS, "Média de " + media + " ms por venda (limite: " + LIMITE_MEDIA_POR_VENDA_MS + " ms)");
        System.out.println("Resultado: OK - nenhuma divergência de estoque");
    }

    @Test
    @DisplayName("Venda acima do estoque é recusada e não altera o estoque")
    void vendaAcimaDoEstoqueRecusada() {
        int produto = produtos.get(0);
        comprar(10, produto);
        assertThrows(IllegalArgumentException.class, () -> vender(produto, 11));
        assertEquals(10, estoque(produto));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM movimentacoes_estoque WHERE tipo='SAIDA'", Integer.class));
    }

    @Test
    @DisplayName("Segunda verificação cancela a venda quando o saldo gravado não bate com o esperado")
    void segundaVerificacaoCancelaVendaInconsistente() {
        int produto = produtos.get(0);
        comprar(100, produto);
        // Simula uma falha na gravação do estoque (como o bug original): toda redução grava 3 unidades a menos.
        db.execute("""
                CREATE OR REPLACE FUNCTION simula_bug() RETURNS trigger AS $$
                BEGIN
                  IF NEW.quantidade < OLD.quantidade THEN NEW.quantidade := NEW.quantidade + 3; END IF;
                  RETURN NEW;
                END $$ LANGUAGE plpgsql""");
        db.execute("CREATE TRIGGER trg_simula_bug BEFORE UPDATE ON estoque FOR EACH ROW EXECUTE FUNCTION simula_bug()");

        IllegalArgumentException erro = assertThrows(IllegalArgumentException.class, () -> vender(produto, 10));
        assertTrue(erro.getMessage().contains("Falha na conferência"), erro.getMessage());
        // Rollback completo: estoque, venda e histórico ficam como antes.
        assertEquals(100, estoque(produto));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM vendas WHERE empresa_id=?", Integer.class, empresa));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM movimentacoes_estoque WHERE tipo='SAIDA'", Integer.class));
    }

    @Test
    @DisplayName("Cenário do incidente: 100 de entrada e 10 vendas de 10 terminam com estoque 0")
    void cenarioDoIncidente() {
        int produto = produtos.get(0);
        comprar(100, produto);
        for (int i = 0; i < 10; i++) vender(produto, 10);
        assertEquals(0, estoque(produto));
        assertEquals("OK", auditoria.auditoria(TOKEN).stream()
                .filter(l -> ((Number) l.get("id")).intValue() == produto).findFirst().orElseThrow().get("situacao"));
        assertThrows(IllegalArgumentException.class, () -> vender(produto, 1));
    }

    private void comprar(int quantidade, Integer... ids) {
        List<Map<String, Object>> itens = new ArrayList<>();
        for (int id : ids) itens.add(new HashMap<>(Map.of("produtoId", id, "quantidade", quantidade)));
        movimentos.compra(new HashMap<>(Map.of("fornecedorId", fornecedor, "itens", itens)), TOKEN);
    }

    private void vender(int produto, int quantidade) {
        movimentos.venda(new HashMap<>(Map.of("clienteId", cliente,
                "itens", List.of(new HashMap<>(Map.of("produtoId", produto, "quantidade", quantidade))))), TOKEN);
    }

    private int estoque(int produto) {
        return db.queryForObject("SELECT quantidade FROM estoque WHERE produto_id=? AND empresa_id=?", Integer.class, produto, empresa);
    }
}
