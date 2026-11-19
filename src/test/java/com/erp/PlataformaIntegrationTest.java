package com.erp;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/** Multiempresa, permissões, funcionários, Squads, OKRs e financeiro em banco PostgreSQL descartável. */
@SpringBootTest(properties = {"spring.config.import=optional:classpath:/sem-config-local.properties", "erp.ia.ollama.enabled=false"})
class PlataformaIntegrationTest {
    static final EmbeddedPostgres POSTGRES;
    static { try { POSTGRES = EmbeddedPostgres.start(); } catch (Exception e) { throw new ExceptionInInitializerError(e); } }
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl("postgres", "postgres"));
        r.add("spring.datasource.username", () -> "postgres");
        r.add("spring.datasource.password", () -> "");
    }
    @MockitoBean CurrentUser user;
    @MockitoBean OllamaClient ollama;
    @Autowired JdbcTemplate db;
    @Autowired AuthService auth;
    @Autowired Autorizacao aut;
    @Autowired FuncionarioService funcionarios;
    @Autowired PerfilService perfis;
    @Autowired SquadService squads;
    @Autowired OkrService okrs;
    @Autowired FinanceiroService financeiro;
    @Autowired PermissaoInterceptor interceptor;

    int empA, empB, donoA, donoB;
    String tokenA;

    @BeforeEach void setup() {
        db.execute("TRUNCATE empresas CASCADE");
        Map<String, Object> a = cadastro("Empresa A", "dono@a.test"), b = cadastro("Empresa B", "dono@b.test");
        tokenA = (String) a.get("token");
        empA = ((Number) ((Map<?, ?>) a.get("usuario")).get("empresaId")).intValue();
        donoA = ((Number) ((Map<?, ?>) a.get("usuario")).get("id")).intValue();
        empB = ((Number) ((Map<?, ?>) b.get("usuario")).get("empresaId")).intValue();
        donoB = ((Number) ((Map<?, ?>) b.get("usuario")).get("id")).intValue();
        mock("A", donoA, empA); mock("B", donoB, empB);
    }

    Map<String, Object> cadastro(String empresa, String email) {
        Map<String, Object> d = new HashMap<>(Map.of("nome", "Dono", "email", email, "senha", "segredo1", "empresa", empresa,
                "perguntaSeguranca1", "AMIGO_INFANCIA", "perguntaSeguranca2", "EMPREGO_SONHOS", "perguntaSeguranca3", "CIDADE_PAIS"));
        d.putAll(Map.of("respostaSeguranca1", "ana", "respostaSeguranca2", "medico", "respostaSeguranca3", "recife"));
        return auth.cadastrar(d);
    }
    void mock(String h, int usuario, int empresa) { when(user.usuario(h)).thenReturn(usuario); when(user.empresa(h)).thenReturn(empresa); }
    int perfil(int empresa, String chave) { return db.queryForObject("SELECT id FROM perfis_acesso WHERE empresa_id=? AND chave_sistema=?", Integer.class, empresa, chave); }
    int func(String header, int empresa, String email, String chave) {
        int id = ((Number) funcionarios.criar(aut.contexto("A".equals(header) ? "A" : "B"), Map.of("nome", "Func " + email, "email", email, "senha", "segredo1",
                "perfis", List.of(perfil(empresa, chave)))).get("id")).intValue();
        return id;
    }

    // ---------- empresa, proprietário, perfis iniciais ----------
    @Test void cadastroCriaProprietarioEPerfisIniciais() {
        assertTrue(db.queryForObject("SELECT proprietario FROM usuarios WHERE id=?", Boolean.class, donoA));
        assertEquals(5, db.queryForObject("SELECT COUNT(*) FROM perfis_acesso WHERE empresa_id=? AND sistema", Integer.class, empA));
        Autorizacao.Contexto c = aut.contexto("A");
        assertTrue(c.proprietario()); assertTrue(c.pode("users.manage")); assertTrue(c.pode("okrs.manage"));
    }

    // ---------- isolamento entre empresas ----------
    @Test void empresaBNaoVeNemAlteraFuncionariosDaEmpresaA() {
        int f = func("A", empA, "f1@a.test", "VENDEDOR");
        assertTrue(funcionarios.listar(empB, null, null).stream().noneMatch(u -> ((Number) u.get("id")).intValue() == f));
        assertThrows(NaoEncontradoException.class, () -> funcionarios.buscar(empB, f));
        assertThrows(NaoEncontradoException.class, () -> funcionarios.atualizar(aut.contexto("B"), f, Map.of("nome", "Hack")));
        assertThrows(NaoEncontradoException.class, () -> funcionarios.alterarStatus(aut.contexto("B"), f, false));
        assertThrows(NaoEncontradoException.class, () -> funcionarios.redefinirSenha(aut.contexto("B"), f, Map.of()));
    }
    @Test void perfilEPessoaDeOutraEmpresaSaoRecusados() {
        int perfilB = perfil(empB, "VENDEDOR");
        assertThrows(IllegalArgumentException.class, () -> funcionarios.criar(aut.contexto("A"),
                Map.of("nome", "X", "email", "x@a.test", "senha", "segredo1", "perfis", List.of(perfilB))));
        assertThrows(NaoEncontradoException.class, () -> perfis.buscar(empA, perfilB));
        int squadB = ((Number) squads.criar(aut.contexto("B"), Map.of("nome", "SB")).get("id")).intValue();
        assertThrows(NaoEncontradoException.class, () -> squads.buscar(empA, squadB));
        assertThrows(IllegalArgumentException.class, () -> squads.criar(aut.contexto("A"), Map.of("nome", "SA", "integrantes", List.of(donoB))));
        // o próprio banco recusa o vínculo cruzado
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> db.update("INSERT INTO squad_membros(squad_id, usuario_id, empresa_id) VALUES (?,?,?)", squadB, donoA, empB));
    }

    // ---------- proprietário e funcionários ----------
    @Test void proprietarioNaoPodeSerDesativadoNemReceberAcessoDeFuncionario() {
        assertThrows(IllegalArgumentException.class, () -> funcionarios.alterarStatus(aut.contexto("A"), donoA, false));
        assertThrows(IllegalArgumentException.class, () -> funcionarios.criar(aut.contexto("A"),
                Map.of("nome", "X", "email", "x@a.test", "senha", "segredo1", "perfis", List.of(perfil(empA, "PROPRIETARIO")))));
        assertThrows(IllegalArgumentException.class, () -> perfis.atualizar(aut.contexto("A"), perfil(empA, "PROPRIETARIO"), Map.of("permissoes", List.of())));
    }
    @Test void criacaoNuncaGeraProprietarioEEmailEUnico() {
        Map<String, Object> f = funcionarios.criar(aut.contexto("A"), Map.of("nome", "Z", "email", "z@a.test", "senha", "segredo1", "proprietario", true,
                "perfis", List.of(perfil(empA, "VENDEDOR"))));
        assertEquals(Boolean.FALSE, f.get("proprietario"));
        assertFalse(f.containsKey("senha_hash")); assertFalse(f.containsKey("senhaHash"));
        assertThrows(IllegalArgumentException.class, () -> funcionarios.criar(aut.contexto("B"), Map.of("nome", "Z", "email", "z@a.test", "senha", "segredo1", "perfis", List.of(perfil(empB, "VENDEDOR")))));
    }
    @Test void senhaDoFuncionarioFicaEmBCryptERedefinicaoGeraTemporaria() {
        int f = func("A", empA, "s@a.test", "VENDEDOR");
        assertTrue(db.queryForObject("SELECT senha_hash FROM usuarios WHERE id=?", String.class, f).startsWith("$2"));
        Map<String, Object> r = funcionarios.redefinirSenha(aut.contexto("A"), f, Map.of());
        assertEquals(12, ((String) r.get("senhaTemporaria")).length());
        assertThrows(AcessoNegadoException.class, () -> { mock("F", f, empA); funcionarios.redefinirSenha(aut.contexto("F"), donoA, Map.of("senha", "novasenha")); });
    }

    // ---------- permissões ----------
    @Test void vendedorTemSoSuasPermissoesNoBackend() {
        int f = func("A", empA, "v@a.test", "VENDEDOR"); mock("V", f, empA);
        assertNotNull(aut.exigir("V", "sales.create"));
        assertThrows(AcessoNegadoException.class, () -> aut.exigir("V", "users.manage"));
        assertThrows(AcessoNegadoException.class, () -> aut.exigir("V", "finance.view"));
        assertThrows(AcessoNegadoException.class, () -> aut.exigir("V", "okrs.manage"));
    }
    @Test void naoProprietarioNaoConcedePermissaoQueNaoTem() {
        int p = ((Number) perfis.criar(aut.contexto("A"), Map.of("nome", "Admin RH", "permissoes", List.of("permissions.manage", "users.view", "users.manage"))).get("id")).intValue();
        int f = ((Number) funcionarios.criar(aut.contexto("A"), Map.of("nome", "RH", "email", "rh@a.test", "senha", "segredo1", "perfis", List.of(p))).get("id")).intValue();
        mock("RH", f, empA);
        Autorizacao.Contexto rh = aut.contexto("RH");
        assertThrows(AcessoNegadoException.class, () -> perfis.criar(rh, Map.of("nome", "Hack", "permissoes", List.of("finance.view"))));
        assertThrows(AcessoNegadoException.class, () -> funcionarios.criar(rh, Map.of("nome", "T", "email", "t@a.test", "senha", "segredo1", "perfis", List.of(perfil(empA, "GERENTE")))));
    }
    @Test void perfilPersonalizadoValidaCodigosEImpedeExclusaoEmUso() {
        assertThrows(IllegalArgumentException.class, () -> perfis.criar(aut.contexto("A"), Map.of("nome", "Q", "permissoes", List.of("inexistente.x"))));
        int p = ((Number) perfis.criar(aut.contexto("A"), Map.of("nome", "Só leitura", "permissoes", List.of("products.view"))).get("id")).intValue();
        funcionarios.criar(aut.contexto("A"), Map.of("nome", "L", "email", "l@a.test", "senha", "segredo1", "perfis", List.of(p)));
        assertThrows(IllegalArgumentException.class, () -> perfis.excluir(aut.contexto("A"), p));
    }
    @Test void interceptorMapeiaRotasEBloqueiaRotaSemRegra() {
        assertEquals("products.view", interceptor.exigida("GET", "/api/produtos"));
        assertEquals("products.create", interceptor.exigida("POST", "/api/produtos"));
        assertEquals("finance.update", interceptor.exigida("POST", "/api/financeiro/pagar/5/pagamento"));
        assertEquals("finance.create", interceptor.exigida("POST", "/api/financeiro/pagar"));
        assertEquals(PermissaoInterceptor.AUTENTICADA, interceptor.exigida("PUT", "/api/okrs/resultados/7/progresso"));
        assertEquals(PermissaoInterceptor.AUTENTICADA, interceptor.exigida("PUT", "/api/okrs/submetas/12/conclusao"));
        assertEquals(PermissaoInterceptor.AUTENTICADA, interceptor.exigida("POST", "/api/okrs/resultados/7/submetas"));
        assertEquals("dashboard.strategic", interceptor.exigida("GET", "/api/dashboard/estrategico"));
        assertEquals(PermissaoInterceptor.PUBLICA, interceptor.exigida("POST", "/api/auth/login"));
        assertNull(interceptor.exigida("GET", "/api/modulo-novo-sem-regra"));
    }

    // ---------- usuário desativado ----------
    @Test void usuarioDesativadoPerdeSessaoELogin() {
        int f = func("A", empA, "d@a.test", "VENDEDOR"); mock("D", f, empA);
        assertNotNull(aut.contexto("D"));
        funcionarios.alterarStatus(aut.contexto("A"), f, false);
        assertThrows(SecurityException.class, () -> aut.contexto("D"));
        assertThrows(SecurityException.class, () -> auth.login(Map.of("email", "d@a.test", "senha", "segredo1")));
        funcionarios.alterarStatus(aut.contexto("A"), f, true);
        assertNotNull(aut.contexto("D"));
    }
    @Test void revogarSessoesInvalidaTokenAntigo() {
        assertNotNull(auth.usuario(tokenA));
        auth.revogarSessoesDoUsuario(donoA);
        assertThrows(SecurityException.class, () -> auth.usuario(tokenA));
    }

    // ---------- Squads ----------
    @Test void squadLiderMembrosEContagem() {
        int f1 = func("A", empA, "a1@a.test", "VENDEDOR"), f2 = func("A", empA, "a2@a.test", "VENDEDOR");
        int s = ((Number) squads.criar(aut.contexto("A"), Map.of("nome", "Alfa", "liderId", f1, "integrantes", List.of(f2))).get("id")).intValue();
        Map<String, Object> d = squads.buscar(empA, s);
        assertEquals(f1, ((Number) d.get("liderId")).intValue());
        assertEquals(2L, ((Number) d.get("totalIntegrantes")).longValue());
        d = squads.removerIntegrante(empA, s, f1); // líder removido
        assertNull(d.get("liderId"));
        assertEquals(1L, ((Number) d.get("totalIntegrantes")).longValue());
        assertThrows(IllegalArgumentException.class, () -> squads.definirLider(empA, s, donoB));
        funcionarios.alterarStatus(aut.contexto("A"), f2, false);
        assertThrows(IllegalArgumentException.class, () -> squads.definirLider(empA, s, f2)); // inativo não lidera
        assertThrows(IllegalArgumentException.class, () -> squads.criar(aut.contexto("A"), Map.of("nome", "alfa")));
    }
    @Test void funcionarioPodeParticiparDeVariasSquads() {
        int f = func("A", empA, "m@a.test", "VENDEDOR");
        int s1 = ((Number) squads.criar(aut.contexto("A"), Map.of("nome", "S1")).get("id")).intValue(), s2 = ((Number) squads.criar(aut.contexto("A"), Map.of("nome", "S2")).get("id")).intValue();
        squads.adicionarIntegrantes(empA, s1, List.of(f)); squads.adicionarIntegrantes(empA, s2, List.of(f));
        assertEquals(2, ((List<?>) funcionarios.buscar(empA, f).get("squads")).size());
    }

    // ---------- OKRs ----------
    Map<String, Object> objetivoComResultado(Autorizacao.Contexto c, String inicial, String meta) {
        Map<String, Object> o = okrs.criarObjetivo(c, Map.of("titulo", "Crescer", "periodoInicio", LocalDate.now().minusDays(5).toString(), "periodoFim", LocalDate.now().plusDays(30).toString()));
        return okrs.criarResultado(c, ((Number) o.get("id")).intValue(), Map.of("titulo", "KR", "valorInicial", inicial, "meta", meta, "prazo", LocalDate.now().plusDays(20).toString(), "unidade", "un"));
    }
    @Test void progressoRespeitaDirecaoEGeraHistorico() {
        Map<String, Object> kr = objetivoComResultado(aut.contexto("A"), "0", "200");
        int id = ((Number) kr.get("id")).intValue();
        Map<String, Object> r = okrs.atualizarProgresso(aut.contexto("A"), id, Map.of("valorAtual", 100, "comentario", "metade"));
        assertEquals(0, new BigDecimal("50").compareTo((BigDecimal) r.get("progresso")));
        assertEquals(1, okrs.historico(empA, id).size());
        assertThrows(IllegalArgumentException.class, () -> okrs.atualizarProgresso(aut.contexto("A"), id, Map.of("valorAtual", 100)));
        // meta de redução: 100 -> 50, valor 75 = 50%
        Map<String, Object> kr2 = objetivoComResultado(aut.contexto("A"), "100", "50");
        Map<String, Object> r2 = okrs.atualizarProgresso(aut.contexto("A"), ((Number) kr2.get("id")).intValue(), Map.of("valorAtual", 75));
        assertEquals(0, new BigDecimal("50").compareTo((BigDecimal) r2.get("progresso")));
        // limite e conclusão automática
        Map<String, Object> r3 = okrs.atualizarProgresso(aut.contexto("A"), id, Map.of("valorAtual", 500));
        assertEquals(0, new BigDecimal("100").compareTo((BigDecimal) r3.get("progresso")));
        assertEquals("CONCLUIDO", r3.get("status"));
        assertEquals("CONCLUIDO", okrs.buscarObjetivo(empA, ((Number) r3.get("objetivoId")).intValue()).get("status"));
    }
    @Test void subMetasAtualizamAutomaticamentePercentualEStatusDoObjetivo() {
        Map<String, Object> o = okrs.criarObjetivo(aut.contexto("A"), Map.of("titulo", "Executar implantação",
                "periodoInicio", LocalDate.now().minusDays(1).toString(), "periodoFim", LocalDate.now().plusDays(20).toString()));
        int objetivoId = ((Number) o.get("id")).intValue();
        Map<String, Object> kr = okrs.criarResultado(aut.contexto("A"), objetivoId, Map.of("titulo", "Etapas de implantação",
                "modoAcompanhamento", "SUBMETAS", "prazo", LocalDate.now().plusDays(10).toString()));
        int resultadoId = ((Number) kr.get("id")).intValue();
        int a = ((Number) okrs.criarSubMeta(aut.contexto("A"), resultadoId, Map.of("titulo", "Preparar ambiente")).get("id")).intValue();
        int b = ((Number) okrs.criarSubMeta(aut.contexto("A"), resultadoId, Map.of("titulo", "Migrar dados")).get("id")).intValue();
        int c = ((Number) okrs.criarSubMeta(aut.contexto("A"), resultadoId, Map.of("titulo", "Validar operação")).get("id")).intValue();

        okrs.atualizarConclusaoSubMeta(aut.contexto("A"), a, Map.of("concluida", true));
        assertEquals(0, new BigDecimal("33.33").compareTo((BigDecimal) okrs.buscarResultado(empA, resultadoId).get("progresso")));
        okrs.atualizarConclusaoSubMeta(aut.contexto("A"), b, Map.of("concluida", true));
        okrs.atualizarConclusaoSubMeta(aut.contexto("A"), c, Map.of("concluida", true));
        assertEquals("CONCLUIDO", okrs.buscarResultado(empA, resultadoId).get("status"));
        assertEquals("CONCLUIDO", okrs.buscarObjetivo(empA, objetivoId).get("status"));

        okrs.atualizarConclusaoSubMeta(aut.contexto("A"), c, Map.of("concluida", false));
        assertEquals(0, new BigDecimal("66.67").compareTo((BigDecimal) okrs.buscarResultado(empA, resultadoId).get("progresso")));
        assertEquals("ATIVO", okrs.buscarObjetivo(empA, objetivoId).get("status"));
        var resultados = (List<?>) okrs.buscarObjetivo(empA, objetivoId).get("resultados");
        var etapas = (List<?>) ((Map<?, ?>) resultados.getFirst()).get("subMetas");
        assertEquals(List.of(a, b, c), etapas.stream().map(etapa -> ((Map<?, ?>) etapa).get("id")).toList());
        okrs.removerSubMeta(aut.contexto("A"), c);
        assertEquals("CONCLUIDO", okrs.buscarObjetivo(empA, objetivoId).get("status"));
    }

    @Test void responsavelComPermissaoDeAtualizacaoPodeGerenciarSubMetasDoSeuResultado() {
        int responsavel = func("A", empA, "submeta.responsavel@a.test", "VENDEDOR");
        mock("SM", responsavel, empA);
        Autorizacao.Contexto contextoResponsavel = new Autorizacao.Contexto(responsavel, empA, false, Set.of("okrs.update", "okrs.view"));
        Map<String, Object> objetivo = okrs.criarObjetivo(aut.contexto("A"), Map.of(
                "titulo", "Objetivo do responsável", "responsavelId", responsavel,
                "periodoInicio", LocalDate.now().minusDays(1).toString(), "periodoFim", LocalDate.now().plusDays(15).toString()));
        int objetivoId = ((Number) objetivo.get("id")).intValue();
        Map<String, Object> resultado = okrs.criarResultado(aut.contexto("A"), objetivoId, Map.of(
                "titulo", "Etapas do responsável", "responsavelId", responsavel,
                "modoAcompanhamento", "SUBMETAS", "prazo", LocalDate.now().plusDays(10).toString()));
        int resultadoId = ((Number) resultado.get("id")).intValue();

        int subMetaId = ((Number) okrs.criarSubMeta(contextoResponsavel, resultadoId,
                Map.of("titulo", "Executar primeira etapa")).get("id")).intValue();
        okrs.atualizarConclusaoSubMeta(contextoResponsavel, subMetaId, Map.of("concluida", true));
        assertEquals(0, new BigDecimal("100.00").compareTo((BigDecimal) okrs.buscarResultado(empA, resultadoId).get("progresso")));
        assertEquals("CONCLUIDO", okrs.buscarResultado(empA, resultadoId).get("status"));
    }

    @Test void percentualManualPermiteAcompanhamentoIntuitivoEValidaLimites() {
        int squadId = (Integer) squads.criar(aut.contexto("A"), Map.of("nome", "Atendimento")).get("id");
        Map<String, Object> o = okrs.criarObjetivo(aut.contexto("A"), Map.of("titulo", "Melhorar atendimento",
                "squadId", squadId,
                "periodoInicio", LocalDate.now().minusDays(1).toString(), "periodoFim", LocalDate.now().plusDays(20).toString()));
        okrs.criarObjetivo(aut.contexto("A"), Map.of("titulo", "Objetivo em planejamento", "squadId", squadId,
                "periodoInicio", LocalDate.now().toString(), "periodoFim", LocalDate.now().plusDays(20).toString()));
        int objetivoId = ((Number) o.get("id")).intValue();
        Map<String, Object> kr = okrs.criarResultado(aut.contexto("A"), objetivoId, Map.of("titulo", "Qualidade percebida",
                "modoAcompanhamento", "MANUAL", "progressoManual", "42.5", "prazo", LocalDate.now().plusDays(10).toString()));
        int resultadoId = ((Number) kr.get("id")).intValue();
        assertEquals(0, new BigDecimal("42.50").compareTo((BigDecimal) kr.get("progresso")));
        Map<String, Object> atualizado = okrs.atualizarProgresso(aut.contexto("A"), resultadoId, Map.of("percentual", "78.25", "comentario", "Avaliação do gestor"));
        assertEquals(0, new BigDecimal("78.25").compareTo((BigDecimal) atualizado.get("progresso")));
        okrs.atualizarProgresso(aut.contexto("A"), resultadoId, Map.of("percentual", "78.25"));
        assertEquals(1, okrs.historico(empA, resultadoId).size());
        var porSquad = (List<?>) okrs.resumo(empA).get("porSquad");
        assertEquals(0, new BigDecimal("78.25").compareTo((BigDecimal) ((Map<?, ?>) porSquad.getFirst()).get("progresso")));
        assertThrows(IllegalArgumentException.class, () -> okrs.atualizarProgresso(aut.contexto("A"), resultadoId, Map.of("percentual", 101)));
    }

    @Test void metaIgualAoInicialERecusadaSemDividirPorZero() {
        Map<String, Object> o = okrs.criarObjetivo(aut.contexto("A"), Map.of("titulo", "O", "periodoInicio", "2026-01-01", "periodoFim", "2026-12-31"));
        assertThrows(IllegalArgumentException.class, () -> okrs.criarResultado(aut.contexto("A"), ((Number) o.get("id")).intValue(),
                Map.of("titulo", "K", "valorInicial", 10, "meta", 10, "prazo", "2026-12-01")));
        assertEquals(0, new BigDecimal("0").compareTo(OkrService.progresso(BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ONE)));
    }

    @Test void atualizacoesSimultaneasConcluemObjetivoComDoisResultados() throws Exception {
        var gestor = aut.contexto("A");
        int objetivo = (Integer) okrs.criarObjetivo(gestor, Map.of("titulo", "Objetivo demonstrativo",
                "periodoInicio", "2026-11-01", "periodoFim", "2026-12-31")).get("id");
        int primeiro = (Integer) okrs.criarResultado(gestor, objetivo, Map.of("titulo", "Primeiro",
                "modoAcompanhamento", "MANUAL", "prazo", "2026-12-31")).get("id");
        int segundo = (Integer) okrs.criarResultado(gestor, objetivo, Map.of("titulo", "Segundo",
                "modoAcompanhamento", "MANUAL", "prazo", "2026-12-31")).get("id");

        var iniciar = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var resultados = List.of(primeiro, segundo).stream().map(id -> executor.submit(() -> {
                iniciar.await();
                return okrs.atualizarProgresso(gestor, id, Map.of("percentual", 100));
            })).toList();
            iniciar.countDown();
            for (var resultado : resultados) assertEquals("CONCLUIDO", resultado.get(10, TimeUnit.SECONDS).get("status"));
        }
        assertEquals("CONCLUIDO", okrs.buscarObjetivo(empA, objetivo).get("status"));
    }
    @Test void okrIsolaEmpresasEExigeResponsavelParaQuemSoAtualiza() {
        Map<String, Object> kr = objetivoComResultado(aut.contexto("A"), "0", "10");
        int id = ((Number) kr.get("id")).intValue();
        assertThrows(NaoEncontradoException.class, () -> okrs.buscarResultado(empB, id));
        assertThrows(NaoEncontradoException.class, () -> okrs.atualizarProgresso(aut.contexto("B"), id, Map.of("valorAtual", 5)));
        assertThrows(NaoEncontradoException.class, () -> okrs.historico(empB, id));
        int f = func("A", empA, "o@a.test", "VENDEDOR"); mock("O", f, empA);
        Autorizacao.Contexto comum = new Autorizacao.Contexto(f, empA, false, Set.of("okrs.update", "okrs.view"));
        assertThrows(AcessoNegadoException.class, () -> okrs.atualizarProgresso(comum, id, Map.of("valorAtual", 5)));
        okrs.atualizarResultado(empA, id, Map.of("responsavelId", f));
        assertNotNull(okrs.atualizarProgresso(comum, id, Map.of("valorAtual", 5)));
    }
    @Test void arquivarPreservaHistorico() {
        Map<String, Object> kr = objetivoComResultado(aut.contexto("A"), "0", "10");
        int id = ((Number) kr.get("id")).intValue(), obj = ((Number) kr.get("objetivoId")).intValue();
        okrs.atualizarProgresso(aut.contexto("A"), id, Map.of("valorAtual", 3));
        okrs.arquivarObjetivo(empA, obj);
        assertEquals(1, okrs.historico(empA, id).size());
        assertTrue(okrs.listarObjetivos(empA, null, null, null, null, null).isEmpty());
        assertEquals(1, okrs.listarObjetivos(empA, "ARQUIVADO", null, null, null, null).size());
        assertThrows(IllegalArgumentException.class, () -> okrs.atualizarProgresso(aut.contexto("A"), id, Map.of("valorAtual", 4)));
    }

    // ---------- financeiro ----------
    @Test void financeiroLiquidaUmaVezECancelaLogicamente() {
        Autorizacao.Contexto c = aut.contexto("A");
        int id = ((Number) financeiro.criar(c, FinanceiroService.Tipo.PAGAR, Map.of("descricao", "Aluguel", "valor", "1500.50", "vencimento", LocalDate.now().plusDays(3).toString())).get("id")).intValue();
        assertThrows(IllegalArgumentException.class, () -> financeiro.criar(c, FinanceiroService.Tipo.PAGAR, Map.of("descricao", "X", "valor", "1.005", "vencimento", "2026-12-01")));
        assertThrows(IllegalArgumentException.class, () -> financeiro.criar(c, FinanceiroService.Tipo.PAGAR, Map.of("descricao", "X", "valor", "-1", "vencimento", "2026-12-01")));
        Map<String, Object> pago = financeiro.liquidar(c, FinanceiroService.Tipo.PAGAR, id, Map.of());
        assertEquals("pago", pago.get("status"));
        assertThrows(IllegalArgumentException.class, () -> financeiro.liquidar(c, FinanceiroService.Tipo.PAGAR, id, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> financeiro.cancelar(c, FinanceiroService.Tipo.PAGAR, id, Map.of()));
        int outro = ((Number) financeiro.criar(c, FinanceiroService.Tipo.RECEBER, Map.of("descricao", "Serviço", "valor", 200, "vencimento", "2020-01-01")).get("id")).intValue();
        assertEquals("vencido", financeiro.buscar(empA, FinanceiroService.Tipo.RECEBER, outro).get("situacao"));
        assertEquals("cancelado", financeiro.cancelar(c, FinanceiroService.Tipo.RECEBER, outro, Map.of("motivo", "erro")).get("status"));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM contas_receber WHERE id=?", Integer.class, outro)); // não apagou
        assertEquals(3, ((List<?>) financeiro.buscar(empA, FinanceiroService.Tipo.PAGAR, id).get("historico")).size());
    }
    @Test void financeiroIsolaEmpresas() {
        int id = ((Number) financeiro.criar(aut.contexto("A"), FinanceiroService.Tipo.RECEBER, Map.of("descricao", "D", "valor", 10, "vencimento", "2026-12-01")).get("id")).intValue();
        assertThrows(NaoEncontradoException.class, () -> financeiro.buscar(empB, FinanceiroService.Tipo.RECEBER, id));
        assertThrows(NaoEncontradoException.class, () -> financeiro.liquidar(aut.contexto("B"), FinanceiroService.Tipo.RECEBER, id, Map.of()));
        assertThrows(NaoEncontradoException.class, () -> financeiro.cancelar(aut.contexto("B"), FinanceiroService.Tipo.RECEBER, id, Map.of()));
        assertTrue(financeiro.listar(empB, FinanceiroService.Tipo.RECEBER, null, null, null, null, null).isEmpty());
    }
    @Test void vendaECompraGeramUmUnicoLancamentoMesmoReprocessadas() {
        int cliente = db.queryForObject("INSERT INTO clientes(empresa_id,nome) VALUES (?,'C') RETURNING id", Integer.class, empA);
        int venda = db.queryForObject("INSERT INTO vendas(empresa_id,cliente_id,usuario_id,total) VALUES (?,?,?,50) RETURNING id", Integer.class, empA, cliente, donoA);
        financeiro.gerarParaVenda(empA, venda, new BigDecimal("50.00"), LocalDate.now(), false, donoA);
        financeiro.gerarParaVenda(empA, venda, new BigDecimal("50.00"), LocalDate.now(), false, donoA);
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM contas_receber WHERE venda_id=?", Integer.class, venda));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM financeiro_historico WHERE tipo='RECEBER' AND acao='GERADO'", Integer.class));
        assertThrows(IllegalArgumentException.class, () -> financeiro.atualizar(aut.contexto("A"), FinanceiroService.Tipo.RECEBER,
                db.queryForObject("SELECT id FROM contas_receber WHERE venda_id=?", Integer.class, venda), Map.of("valor", 99)));
    }
    @Test void migracaoERepetivelEPreservaDados() throws Exception {
        var script = new org.springframework.core.io.ClassPathResource("schema.sql");
        for (int i = 0; i < 2; i++) org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(db.getDataSource().getConnection(), script);
        assertTrue(db.queryForObject("SELECT proprietario FROM usuarios WHERE id=?", Boolean.class, donoA));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM usuario_perfis WHERE usuario_id=?", Integer.class, donoA));
    }
}
