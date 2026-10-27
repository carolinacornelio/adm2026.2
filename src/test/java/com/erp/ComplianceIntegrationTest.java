package com.erp;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"spring.config.import=optional:classpath:/pos-test-empty.properties","erp.ia.ollama.enabled=false","server.shutdown=immediate"})
class ComplianceIntegrationTest {
    static final EmbeddedPostgres PG;
    static {try{PG=EmbeddedPostgres.start();}catch(Exception e){throw new ExceptionInInitializerError(e);}}
    @DynamicPropertySource static void db(DynamicPropertyRegistry r){r.add("spring.datasource.url",()->PG.getJdbcUrl("postgres","postgres"));r.add("spring.datasource.username",()->"postgres");r.add("spring.datasource.password",()->"");}
    @Autowired JdbcTemplate db;
    @Autowired AuthService auth;
    @Autowired TestRestTemplate http;
    int empresa,usuario,produto,fornecedor;
    String token;
    @BeforeEach void preparar(){
        db.execute("TRUNCATE empresas CASCADE");
        var conta=auth.cadastrar(Map.of("email","dono@compliance.test","senha","teste-compliance-123","nome","Administrador","empresa","Empresa","perguntaSeguranca1","AMIGO_INFANCIA","perguntaSeguranca2","EMPREGO_SONHOS","perguntaSeguranca3","CIDADE_PAIS","respostaSeguranca1","um","respostaSeguranca2","dois","respostaSeguranca3","tres"));
        token=(String)conta.get("token");var u=(Map<?,?>)conta.get("usuario");empresa=((Number)u.get("empresaId")).intValue();usuario=((Number)u.get("id")).intValue();
        fornecedor=db.queryForObject("INSERT INTO fornecedores(empresa_id,nome) VALUES (?,'Fornecedor') RETURNING id",Integer.class,empresa);
        produto=db.queryForObject("INSERT INTO produtos(empresa_id,nome,preco,preco_compra,preco_venda,controla_validade) VALUES (?,'Perecível',5001,5000,5001,true) RETURNING id",Integer.class,empresa);
        db.update("INSERT INTO estoque(empresa_id,produto_id,quantidade) VALUES (?,?,0)",empresa,produto);
    }
    record Ator(int id,String token){}
    Ator ator(String perfil){
        String email=UUID.randomUUID()+"@compliance.test";
        int id=db.queryForObject("INSERT INTO usuarios(empresa_id,nome,email,senha_hash,perfil,pergunta_seguranca_1,resposta_seguranca_hash_1,pergunta_seguranca_2,resposta_seguranca_hash_2,pergunta_seguranca_3,resposta_seguranca_hash_3) SELECT empresa_id,?, ?,senha_hash,?,pergunta_seguranca_1,resposta_seguranca_hash_1,pergunta_seguranca_2,resposta_seguranca_hash_2,pergunta_seguranca_3,resposta_seguranca_hash_3 FROM usuarios WHERE id=? RETURNING id",Integer.class,perfil,email,perfil,usuario);
        var desafio=auth.login(Map.of("email",email,"senha","teste-compliance-123"));
        var sessao=auth.verificarSegundoFator(Map.of("desafio",desafio.get("desafio"),"respostaSeguranca1","um","respostaSeguranca2","dois","respostaSeguranca3","tres"));
        return new Ator(id,(String)sessao.get("token"));
    }
    ResponseEntity<Map> chamar(String quem,HttpMethod metodo,String caminho,Object dados){var h=new HttpHeaders();h.setBearerAuth(quem);return http.exchange(caminho,metodo,new HttpEntity<>(dados,h),Map.class);}
    ResponseEntity<Map> post(String caminho,Object dados){return chamar(token,HttpMethod.POST,caminho,dados);}
    Map<String,Object> compra(int q){return Map.of("fornecedorId",fornecedor,"itens",List.of(Map.of("produtoId",produto,"quantidade",q,"lote","COMP","validade",InventarioService.hoje().plusDays(30).toString())));}
    int id(ResponseEntity<Map> resposta){assertEquals(HttpStatus.OK,resposta.getStatusCode(),String.valueOf(resposta.getBody()));return ((Number)resposta.getBody().get("id")).intValue();}
    int saldo(){return db.queryForObject("SELECT quantidade FROM estoque WHERE produto_id=?",Integer.class,produto);}
    int contar(String tabela){return db.queryForObject("SELECT COUNT(*) FROM "+tabela,Integer.class);}
    int contrato(BigDecimal valor){return id(post("/api/compliance/contratos",Map.of("fornecedorId",fornecedor,"numeroContrato",UUID.randomUUID().toString(),"objeto","Serviço","valorTotal",valor,"arquivoUrl","https://example.com/contrato.pdf")));}
    Map<String,Object> banco(){return Map.of("banco","Banco","agencia","1","conta","123","chavePix","fornecedor@example.com","arquivoComprobatorioUrl","https://example.com/comprovante.pdf");}
    Map<String,Object> servico(int contrato,BigDecimal valor){return Map.of("fornecedorId",fornecedor,"tipoDocumento","SERVICO","contratoId",contrato,"valorServico",valor,"descricaoServico","Manutenção","notaFiscal","NF-01");}

    @Test void compraPequenaPreservaLotesECriaTituloComValorCorreto(){
        int compra=id(post("/api/compras",compra(1)));
        assertEquals(1,saldo());assertEquals(1,contar("estoque_lotes"));
        assertEquals(new BigDecimal("5000.00"),db.queryForObject("SELECT valor FROM contas_pagar WHERE compra_id=?",BigDecimal.class,compra));
        id(post("/api/compras/"+compra+"/aprovar",Map.of()));id(post("/api/compras/"+compra+"/aprovar",Map.of()));
        assertEquals(1,saldo());assertEquals(1,contar("contas_pagar"));
    }
    @Test void compraDeDezMilExigeDuasPessoasEAuditaBloqueioMesmoComRollback(){
        int compra=id(post("/api/compras",compra(2)));assertEquals(0,saldo());assertEquals(0,contar("contas_pagar"));
        assertEquals(HttpStatus.FORBIDDEN,post("/api/compras/"+compra+"/aprovar",Map.of()).getStatusCode());
        assertEquals(1,contar("compliance_alertas"));
        Ator gerente=ator("gerente_compras"),diretor=ator("diretor"),financeiro=ator("financeiro");
        assertEquals("AGUARDANDO_NIVEL_2",chamar(gerente.token,HttpMethod.POST,"/api/compras/"+compra+"/aprovar",Map.of()).getBody().get("status"));
        assertEquals(0,saldo());
        id(chamar(diretor.token,HttpMethod.POST,"/api/compras/"+compra+"/aprovar",Map.of()));assertEquals(2,saldo());
        db.update("UPDATE usuarios SET perfil='financeiro' WHERE id=?",gerente.id);
        assertEquals(HttpStatus.FORBIDDEN,chamar(gerente.token,HttpMethod.POST,"/api/compras/"+compra+"/pagar",Map.of()).getStatusCode());
        id(chamar(financeiro.token,HttpMethod.POST,"/api/compras/"+compra+"/pagar",Map.of()));id(chamar(financeiro.token,HttpMethod.POST,"/api/compras/"+compra+"/pagar",Map.of()));
        assertEquals("pago",db.queryForObject("SELECT status FROM contas_pagar",String.class));assertEquals(1,contar("contas_pagar"));assertEquals(2,saldo());
    }
    @Test void aprovacoesConcorrentesNaoDuplicamEntrada() throws Exception {
        int compra=id(post("/api/compras",compra(2)));Ator primeiro=ator("admin"),segundo=ator("diretor"),terceiro=ator("diretor");
        id(chamar(primeiro.token,HttpMethod.POST,"/api/compras/"+compra+"/aprovar",Map.of()));
        assertEquals(HttpStatus.FORBIDDEN,chamar(primeiro.token,HttpMethod.POST,"/api/compras/"+compra+"/aprovar",Map.of()).getStatusCode());
        try(var pool=Executors.newFixedThreadPool(2)){
            var resultados=pool.invokeAll(List.<Callable<ResponseEntity<Map>>>of(()->chamar(segundo.token,HttpMethod.POST,"/api/compras/"+compra+"/aprovar",Map.of()),()->chamar(terceiro.token,HttpMethod.POST,"/api/compras/"+compra+"/aprovar",Map.of())));
            for(var resultado:resultados)assertEquals(HttpStatus.OK,resultado.get(15,TimeUnit.SECONDS).getStatusCode());
        }
        assertEquals(2,saldo());assertEquals(1,contar("estoque_lotes"));assertEquals(1,contar("contas_pagar"));
    }
    @Test void cancelarCompraPendenteNaoMovimentaEstoque(){
        int compra=id(post("/api/compras",compra(2)));id(post("/api/compras/"+compra+"/cancelar",Map.of()));
        assertEquals(HttpStatus.BAD_REQUEST,post("/api/compras/"+compra+"/aprovar",Map.of()).getStatusCode());assertEquals(0,saldo());assertEquals(0,contar("contas_pagar"));
    }
    @Test void dadosBancariosExigemOutraPessoaInclusiveParaAdministrador(){
        db.update("UPDATE fornecedores SET contrato_social_url='https://example.com/contrato-social.pdf' WHERE id=?",fornecedor);
        int pedido=id(post("/api/compliance/fornecedores/"+fornecedor+"/dados-bancarios",banco()));
        assertEquals(HttpStatus.FORBIDDEN,post("/api/compliance/alteracoes-pendentes/"+pedido+"/aprovar",Map.of()).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN,post("/api/compliance/alteracoes-pendentes/"+pedido+"/rejeitar",Map.of()).getStatusCode());
        assertNull(db.queryForObject("SELECT banco FROM fornecedores WHERE id=?",String.class,fornecedor));
        assertEquals(HttpStatus.CONFLICT,post("/api/compras",compra(1)).getStatusCode());assertEquals(0,contar("compras"));
        var revisor=ator("compliance");id(chamar(revisor.token,HttpMethod.POST,"/api/compliance/alteracoes-pendentes/"+pedido+"/aprovar",Map.of()));
        assertEquals("Banco",db.queryForObject("SELECT banco FROM fornecedores WHERE id=?",String.class,fornecedor));
        assertEquals("https://example.com/contrato-social.pdf",db.queryForObject("SELECT contrato_social_url FROM fornecedores WHERE id=?",String.class,fornecedor));
        assertFalse(db.queryForObject("SELECT dados_bancarios_bloqueados FROM fornecedores WHERE id=?",Boolean.class,fornecedor));
        assertEquals(HttpStatus.BAD_REQUEST,chamar(revisor.token,HttpMethod.POST,"/api/compliance/alteracoes-pendentes/"+pedido+"/aprovar",Map.of()).getStatusCode());
        var novos=new HashMap<>(banco());novos.put("conta","456");int rejeitar=id(post("/api/compliance/fornecedores/"+fornecedor+"/dados-bancarios",novos));
        id(chamar(revisor.token,HttpMethod.POST,"/api/compliance/alteracoes-pendentes/"+rejeitar+"/rejeitar",Map.of()));
        assertEquals("123",db.queryForObject("SELECT conta FROM fornecedores WHERE id=?",String.class,fornecedor));
    }
    @Test void pagamentoTambemRespeitaBloqueioBancario(){
        int compra=id(post("/api/compras",compra(1)));id(post("/api/compliance/fornecedores/"+fornecedor+"/dados-bancarios",banco()));
        var financeiro=ator("financeiro");assertEquals(HttpStatus.CONFLICT,chamar(financeiro.token,HttpMethod.POST,"/api/compras/"+compra+"/pagar",Map.of()).getStatusCode());
        assertEquals("pendente",db.queryForObject("SELECT status FROM contas_pagar",String.class));
    }
    @Test void servicoExigeContratoENaoGeraEstoque(){
        assertEquals(HttpStatus.BAD_REQUEST,post("/api/compras",Map.of("fornecedorId",fornecedor,"tipoDocumento","SERVICO","notaFiscal","NF","descricaoServico","Serviço","valorServico",100)).getStatusCode());
        assertEquals(0,contar("compras"));assertEquals(1,contar("compliance_alertas"));
        int contrato=contrato(new BigDecimal("10000.00"));id(post("/api/compras",servico(contrato,new BigDecimal("5000.00"))));
        assertEquals(0,saldo());assertEquals(0,contar("estoque_lotes"));assertEquals(0,contar("itens_compra"));
        assertEquals(new BigDecimal("5000.00"),db.queryForObject("SELECT valor FROM contas_pagar",BigDecimal.class));
        assertEquals(HttpStatus.BAD_REQUEST,post("/api/compras",servico(contrato,new BigDecimal("6000.00"))).getStatusCode());
        assertEquals(1,contar("compras"));
    }
    @Test void contratoAltoValorExigeHomologacaoDeOutroRevisor(){
        int contrato=contrato(new BigDecimal("60000.00"));assertEquals(1,contar("compliance_alertas"));
        assertEquals(HttpStatus.FORBIDDEN,chamar(token,HttpMethod.PUT,"/api/compliance/contratos/"+contrato+"/homologar",Map.of()).getStatusCode());
        assertEquals(HttpStatus.CONFLICT,post("/api/compras",servico(contrato,new BigDecimal("100.00"))).getStatusCode());
        var revisor=ator("compliance");id(chamar(revisor.token,HttpMethod.PUT,"/api/compliance/contratos/"+contrato+"/homologar",Map.of()));
        id(post("/api/compras",servico(contrato,new BigDecimal("51000.00"))));assertEquals(0,saldo());
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM compliance_alertas WHERE motivo_alerta='VALOR_SUPERIOR_50000' AND documento_referencia LIKE 'COMPRA_%'",Integer.class));
    }
    @Test void contratosDeOutroFornecedorOuForaDaVigenciaSaoRecusados(){
        int contrato=contrato(new BigDecimal("1000.00"));int outro=db.queryForObject("INSERT INTO fornecedores(empresa_id,nome) VALUES (?,'Outro') RETURNING id",Integer.class,empresa);
        var pedido=new HashMap<>(servico(contrato,new BigDecimal("100.00")));pedido.put("fornecedorId",outro);
        assertEquals(HttpStatus.BAD_REQUEST,post("/api/compras",pedido).getStatusCode());
        db.update("UPDATE contratos_fornecedores SET data_fim=? WHERE id=?",InventarioService.hoje().minusDays(1),contrato);
        assertEquals(HttpStatus.BAD_REQUEST,post("/api/compras",servico(contrato,new BigDecimal("100.00"))).getStatusCode());
    }
    @Test void perfilEhControladoNoServidorERevogacaoValeNaSessaoAberta(){
        var padrao=ator("padrao");assertEquals(HttpStatus.FORBIDDEN,chamar(padrao.token,HttpMethod.POST,"/api/compliance/usuarios",Map.of("perfil","admin")).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,chamar(token,HttpMethod.PUT,"/api/compliance/usuarios/"+usuario+"/perfil",Map.of("perfil","padrao")).getStatusCode());
        int criado=id(post("/api/compliance/usuarios",Map.of("nome","Novo","email","novo@compliance.test","senha","teste-compliance-123","perfil","admin")));
        assertNotEquals(usuario,criado);
        id(chamar(token,HttpMethod.PUT,"/api/compliance/usuarios/"+usuario+"/perfil",Map.of("perfil","diretor")));
        assertEquals("diretor",chamar(token,HttpMethod.GET,"/api/compliance/contexto",null).getBody().get("perfil"));
        assertEquals(HttpStatus.FORBIDDEN,chamar(token,HttpMethod.PUT,"/api/compliance/usuarios/"+padrao.id+"/perfil",Map.of("perfil","admin")).getStatusCode());
    }
    @Test void compradorNaoCadastraFornecedorENaoHaAtalhoParaDadosBancarios(){
        var comprador=ator("comprador");assertEquals(HttpStatus.FORBIDDEN,chamar(comprador.token,HttpMethod.POST,"/api/pessoas",Map.of("nome","Novo","tipo","CLIENTE_FORNECEDOR")).getStatusCode());
        assertEquals(0,contar("clientes"));assertEquals(1,contar("compliance_alertas"));
        id(chamar(comprador.token,HttpMethod.POST,"/api/pessoas",Map.of("nome","Cliente","tipo","CLIENTE")));
        assertEquals(HttpStatus.BAD_REQUEST,chamar(token,HttpMethod.PUT,"/api/pessoas/"+fornecedor,Map.of("tipo","FORNECEDOR","nome","Fornecedor","banco","ATALHO")).getStatusCode());
        var invalido=new HashMap<>(banco());invalido.put("arquivoComprobatorioUrl","javascript:alert(1)");
        assertEquals(HttpStatus.BAD_REQUEST,post("/api/compliance/fornecedores/"+fornecedor+"/dados-bancarios",invalido).getStatusCode());assertEquals(0,contar("fornecedores_alteracoes_pendentes"));
    }
    @Test void empresasNaoAcessamNemDecidemRegistrosAlheios(){
        int contrato=contrato(new BigDecimal("100.00")),pedido=id(post("/api/compliance/fornecedores/"+fornecedor+"/dados-bancarios",banco()));
        var outra=auth.cadastrar(Map.of("email","outra@compliance.test","senha","teste-compliance-123","nome","Outra","empresa","Outra","perguntaSeguranca1","AMIGO_INFANCIA","perguntaSeguranca2","EMPREGO_SONHOS","perguntaSeguranca3","CIDADE_PAIS","respostaSeguranca1","um","respostaSeguranca2","dois","respostaSeguranca3","tres"));
        String outro=(String)outra.get("token");
        assertEquals(HttpStatus.BAD_REQUEST,chamar(outro,HttpMethod.PUT,"/api/compliance/contratos/"+contrato+"/homologar",Map.of()).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,chamar(outro,HttpMethod.POST,"/api/compliance/alteracoes-pendentes/"+pedido+"/aprovar",Map.of()).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,chamar(outro,HttpMethod.PUT,"/api/compliance/usuarios/"+usuario+"/perfil",Map.of("perfil","padrao")).getStatusCode());
        var h=new HttpHeaders();h.setBearerAuth(outro);
        assertTrue(http.exchange("/api/compliance/contratos",HttpMethod.GET,new HttpEntity<>(h),List.class).getBody().isEmpty());
    }
    @Test void migracaoRepetidaPreservaComprasPendentesEDadosLegados(){
        int compra=id(post("/api/compras",compra(2)));int contrato=contrato(new BigDecimal("100.00"));
        db.execute("ALTER TABLE contratos_fornecedores DROP COLUMN criado_por");
        db.execute("ALTER TABLE compliance_alertas ALTER COLUMN motivo_alerta DROP NOT NULL");
        db.update("INSERT INTO compliance_alertas(empresa_id,usuario_id,documento_referencia,motivo_alerta,nivel_severidade,detalhes,tipo_regra,motivo_risco) VALUES (?,?,'LEGADO',NULL,'ALTA','Legado','REGRA_ANTIGA','Motivo antigo')",empresa,usuario);
        var migracao=new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(new org.springframework.core.io.ClassPathResource("schema.sql"));migracao.execute(db.getDataSource());migracao.execute(db.getDataSource());
        assertEquals(usuario,db.queryForObject("SELECT criado_por FROM contratos_fornecedores WHERE id=?",Integer.class,contrato));assertEquals(0,saldo());
        assertFalse(db.queryForObject("SELECT entrada_efetivada FROM compras WHERE id=?",Boolean.class,compra));
        assertEquals("REGRA_ANTIGA",db.queryForObject("SELECT motivo_alerta FROM compliance_alertas WHERE documento_referencia='LEGADO'",String.class));
    }
}
