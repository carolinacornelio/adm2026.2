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
 * Integração das branches atendimento + compliance + desafio 5:
 * compras com alçada só dão entrada no estoque (pelos lotes, com segunda verificação)
 * depois da aprovação final, e uma compra nunca entra no estoque duas vezes.
 */
@SpringBootTest(properties = {"spring.config.import=optional:classpath:/sem-config-local.properties",
        "erp.ia.ollama.enabled=false"})
class ComplianceEstoqueIntegracaoTest {
    static final EmbeddedPostgres POSTGRES;
    static { try { POSTGRES = EmbeddedPostgres.start(); } catch (Exception e) { throw new ExceptionInInitializerError(e); } }
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
    }

    @MockitoBean CurrentUser user;
    @MockitoBean OllamaClient ollama;
    @Autowired JdbcTemplate db;
    @Autowired MovimentoController movimentos;

    int empresa, fornecedor, produto, perecivel;

    @BeforeEach void setup() {
        db.execute("TRUNCATE empresas CASCADE");
        empresa = db.queryForObject("INSERT INTO empresas(nome_fantasia) VALUES ('Integração') RETURNING id", Integer.class);
        usuario("SOL", "solicitante@teste.local", "padrao");
        usuario("GER", "gerente@teste.local", "gerente");
        usuario("DIR", "diretor@teste.local", "diretor");
        fornecedor = db.queryForObject("INSERT INTO fornecedores(empresa_id,nome) VALUES (?,'Fornecedor') RETURNING id", Integer.class, empresa);
        produto = produto("Produto caro", "CARO", false, 1000);
        perecivel = produto("Leite", "LEITE", true, 5);
    }

    private void usuario(String token, String email, String perfil) {
        int id = db.queryForObject("INSERT INTO usuarios(empresa_id,nome,email,senha_hash,perfil) VALUES (?,?,?,'x',?) RETURNING id",
                Integer.class, empresa, token, email, perfil);
        when(user.empresa(token)).thenReturn(empresa);
        when(user.usuario(token)).thenReturn(id);
        when(user.perfil(token)).thenReturn(perfil);
    }

    private int produto(String nome, String codigo, boolean controlaValidade, int precoCompra) {
        int id = db.queryForObject("INSERT INTO produtos(empresa_id,nome,codigo,preco,preco_compra,preco_venda,controla_validade) VALUES (?,?,?,?,?,?,?) RETURNING id",
                Integer.class, empresa, nome, codigo, precoCompra * 2, precoCompra, precoCompra * 2, controlaValidade);
        db.update("INSERT INTO estoque(empresa_id,produto_id,quantidade) VALUES (?,?,0)", empresa, id);
        return id;
    }

    private Map<String, Object> comprar(String token, int produtoId, int quantidade, Map<String, Object> extras) {
        Map<String, Object> item = new HashMap<>(Map.of("produtoId", produtoId, "quantidade", quantidade));
        item.putAll(extras);
        return movimentos.compra(new HashMap<>(Map.of("fornecedorId", fornecedor, "itens", List.of(item))), token);
    }

    private int estoque(int produtoId) {
        return db.queryForObject("SELECT quantidade FROM estoque WHERE produto_id=?", Integer.class, produtoId);
    }

    @Test
    @DisplayName("Compra abaixo de R$ 10 mil entra no estoque na hora, pelos lotes, com saldo conferido")
    void compraPequenaEntraNaHora() {
        var resp = comprar("SOL", produto, 5, Map.of());
        assertEquals("CONCLUIDA", resp.get("status"));
        assertEquals(5, estoque(produto));
        assertEquals(5, db.queryForObject("SELECT SUM(quantidade) FROM estoque_lotes WHERE produto_id=?", Integer.class, produto));
        assertEquals(5, db.queryForObject("SELECT saldo_conferido FROM movimentacoes_estoque WHERE produto_id=?", Integer.class, produto));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM contas_pagar", Integer.class));
    }

    @Test
    @DisplayName("Compra de R$ 10 mil ou mais só entra no estoque após a dupla alçada, uma única vez")
    void compraGrandeExigeDuplaAlcada() {
        var resp = comprar("SOL", produto, 20, Map.of()); // 20 x R$ 1.000 = R$ 20.000
        int compra = ((Number) resp.get("id")).intValue();
        assertEquals("PENDENTE_APROVACAO", resp.get("status"));
        assertEquals(0, estoque(produto), "Compra pendente não pode dar entrada no estoque");

        // Perfil operacional não aprova compra de alto valor (e o incidente fica auditado).
        assertThrows(ComplianceException.class, () -> movimentos.aprovarCompra(compra, "SOL"));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM compliance_alertas", Integer.class));

        assertEquals("AGUARDANDO_NIVEL_2", movimentos.aprovarCompra(compra, "GER").get("status"));
        assertEquals(0, estoque(produto));
        // O segundo nível precisa ser outra pessoa.
        assertThrows(ComplianceException.class, () -> movimentos.aprovarCompra(compra, "GER"));

        assertEquals("CONCLUIDA", movimentos.aprovarCompra(compra, "DIR").get("status"));
        assertEquals(20, estoque(produto));
        // Aprovar de novo não pode dar uma segunda entrada (estoque fantasma).
        assertThrows(IllegalArgumentException.class, () -> movimentos.aprovarCompra(compra, "DIR"));
        assertEquals(20, estoque(produto));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM movimentacoes_estoque WHERE tipo='ENTRADA'", Integer.class));

        // Regra de Ouro SoD: quem aprovou não liquida o pagamento; um terceiro pode.
        assertThrows(ComplianceException.class, () -> movimentos.pagarCompra(compra, "DIR"));
        assertThrows(ComplianceException.class, () -> movimentos.pagarCompra(compra, "SOL"));
    }

    @Test
    @DisplayName("Lote e validade de perecíveis são exigidos na criação e usados na entrada após a aprovação")
    void pereciveisEmAlcadaGuardamLoteEValidade() {
        assertThrows(IllegalArgumentException.class, () -> comprar("SOL", perecivel, 2000, Map.of()));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM compras", Integer.class));

        String validade = InventarioService.hoje().plusDays(20).toString();
        var resp = comprar("SOL", perecivel, 2000, Map.of("lote", "L-ALCADA", "validade", validade)); // R$ 10.000
        int compra = ((Number) resp.get("id")).intValue();
        assertEquals("PENDENTE_APROVACAO", resp.get("status"));
        assertEquals(0, estoque(perecivel));

        movimentos.aprovarCompra(compra, "GER");
        movimentos.aprovarCompra(compra, "DIR");
        assertEquals(2000, estoque(perecivel));
        assertEquals(validade, db.queryForObject("SELECT validade::text FROM estoque_lotes WHERE codigo='L-ALCADA'", String.class));
    }

    @Test
    @DisplayName("Fornecedor com dados bancários em análise não pode receber pedidos")
    void fornecedorBloqueadoNaoCompra() {
        db.update("UPDATE fornecedores SET dados_bancarios_bloqueados=true WHERE id=?", fornecedor);
        assertThrows(ComplianceException.class, () -> comprar("SOL", produto, 1, Map.of()));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM compras", Integer.class));
    }
}
