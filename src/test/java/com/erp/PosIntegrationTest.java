package com.erp;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.io.IOException;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.*;
import java.time.Duration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

/** Testes reais em PostgreSQL temporário, sem ler ou alterar a base do ERP. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=optional:classpath:/pos-test-empty.properties", "erp.ia.ollama.enabled=false", "server.shutdown=immediate" })
class PosIntegrationTest {
    static final EmbeddedPostgres POSTGRES;
    static { try { POSTGRES = EmbeddedPostgres.start(); } catch (IOException e) { throw new ExceptionInInitializerError(e); } }
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
    }
    @org.springframework.test.context.bean.override.mockito.MockitoBean OllamaClient ollama;
    @Autowired JdbcTemplate db;
    @Autowired AuthService auth;
    @Autowired TestRestTemplate http;
    @LocalServerPort int serverPort;
    String token;
    int empresa;
    int produto;

    @BeforeEach void prepare() {
        db.execute("TRUNCATE empresas CASCADE");
        Map<String,Object> session = account("operador");
        token = (String) session.get("token");
        empresa = ((Number) ((Map<?,?>) session.get("usuario")).get("empresaId")).intValue();
        produto = db.queryForObject("INSERT INTO produtos (empresa_id,nome,codigo,preco,preco_venda,preco_compra) VALUES (?,'Café','CAFE',10.10,10.10,5) RETURNING id", Integer.class,empresa);
        db.update("INSERT INTO estoque (empresa_id,produto_id,quantidade) VALUES (?,?,10)",empresa,produto);
    }
    Map<String,Object> account(String suffix) {
        Map<String,Object> data = new HashMap<>();
        data.put("email",suffix+"@pos.test"); data.put("senha","senha-teste-123"); data.put("nome",suffix); data.put("empresa",suffix);
        data.put("perguntaSeguranca1","AMIGO_INFANCIA"); data.put("perguntaSeguranca2","EMPREGO_SONHOS"); data.put("perguntaSeguranca3","CIDADE_PAIS");
        data.put("respostaSeguranca1","resposta um"); data.put("respostaSeguranca2","resposta dois"); data.put("respostaSeguranca3","resposta tres");
        return auth.cadastrar(data);
    }
    Map<String,Object> sale(int qty) {
        Map<String,Object> body = new HashMap<>();
        body.put("chaveOperacao",UUID.randomUUID().toString());
        body.put("itens",List.of(Map.of("produtoId",produto,"quantidade",qty)));
        body.put("desconto",new BigDecimal("0.20"));
        body.put("totalEsperado",new BigDecimal("10.10").multiply(BigDecimal.valueOf(qty)).subtract(new BigDecimal("0.20")));
        body.put("formaPagamento","DINHEIRO"); body.put("valorRecebido",new BigDecimal("200.00")); body.put("pagamentoConfirmado",true);
        return body;
    }
    ResponseEntity<Map> post(Map<String,Object> body, String bearer) {
        HttpHeaders headers = new HttpHeaders(); headers.setBearerAuth(bearer);
        return http.exchange("/api/pos/vendas",HttpMethod.POST,new HttpEntity<>(body,headers),Map.class);
    }
    int stock() { return db.queryForObject("SELECT quantidade FROM estoque WHERE produto_id=?",Integer.class,produto); }
    int count(String table) { return db.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class); }

    @Test void salePersistsPaymentReceiptAndStockInErp() {
        var response = post(sale(2),token);
        assertEquals(HttpStatus.OK,response.getStatusCode(),String.valueOf(response.getBody()));
        assertEquals(8,stock()); assertEquals(1,count("vendas")); assertEquals(1,count("contas_receber"));
        assertEquals(new BigDecimal("20.00"),db.queryForObject("SELECT total FROM vendas",BigDecimal.class));
        assertEquals(new BigDecimal("180.00"),db.queryForObject("SELECT pos_troco FROM vendas",BigDecimal.class));
        assertEquals("concluida",db.queryForObject("SELECT status FROM vendas",String.class));
        assertEquals("recebido",db.queryForObject("SELECT status FROM contas_receber",String.class));
        assertEquals(1,((List<?>)response.getBody().get("itens")).size());
    }
    @Test void repeatedRequestDoesNotDuplicateSaleOrStock() {
        var request=sale(2);
        var first=post(request,token); var second=post(request,token);
        assertEquals(HttpStatus.OK,second.getStatusCode()); assertEquals(first.getBody().get("id"),second.getBody().get("id"));
        assertEquals(1,count("vendas")); assertEquals(8,stock()); assertEquals(1,count("movimentacoes_estoque"));
        request.put("desconto",0);
        assertEquals(HttpStatus.BAD_REQUEST,post(request,token).getStatusCode()); assertEquals(8,stock());
    }
    @Test void invalidLastItemRollsBackEverything() {
        var request=sale(2);
        request.put("itens",List.of(Map.of("produtoId",produto,"quantidade",2),Map.of("produtoId",2147483646,"quantidade",1)));
        assertEquals(HttpStatus.BAD_REQUEST,post(request,token).getStatusCode());
        assertEquals(0,count("vendas")); assertEquals(0,count("contas_receber")); assertEquals(10,stock());
    }
    @Test void rejectsForeignTenantProductAndReceipt() {
        var other = account("outra"); String otherToken=(String)other.get("token");
        assertEquals(HttpStatus.BAD_REQUEST,post(sale(1),otherToken).getStatusCode());
        var receipt=post(sale(1),token).getBody();
        HttpHeaders headers=new HttpHeaders();headers.setBearerAuth(otherToken);
        assertEquals(HttpStatus.BAD_REQUEST,http.exchange("/api/pos/vendas/"+receipt.get("id"),HttpMethod.GET,new HttpEntity<>(headers),Map.class).getStatusCode());
    }
    @Test void rejectsPriceChangesInvalidQuantitiesAndUnconfirmedPayment() {
        var changed=sale(1); changed.put("totalEsperado",1);
        assertEquals(HttpStatus.BAD_REQUEST,post(changed,token).getStatusCode());
        var fractional=sale(1); fractional.put("itens",List.of(Map.of("produtoId",produto,"quantidade",1.5)));
        assertEquals(HttpStatus.BAD_REQUEST,post(fractional,token).getStatusCode());
        var unpaid=sale(1); unpaid.put("pagamentoConfirmado",false);
        assertEquals(HttpStatus.BAD_REQUEST,post(unpaid,token).getStatusCode());
        assertEquals(0,count("vendas"));assertEquals(10,stock());
    }
    @Test void concurrentCashiersCannotSellSameLastUnits() throws Exception {
        var a=sale(8);var b=sale(8);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var futures=executor.invokeAll(List.<Callable<ResponseEntity<Map>>>of(()->post(a,token),()->post(b,token)));
            var statuses=List.of(futures.get(0).get().getStatusCode(),futures.get(1).get().getStatusCode());
            assertTrue(statuses.contains(HttpStatus.OK));assertTrue(statuses.contains(HttpStatus.BAD_REQUEST));
        }
        assertEquals(2,stock());assertEquals(1,count("vendas"));
    }
    @Test void concurrentRetriesReturnOneSale() throws Exception {
        var request=sale(2);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var futures=executor.invokeAll(List.<Callable<ResponseEntity<Map>>>of(()->post(request,token),()->post(request,token)));
            var a=futures.get(0).get();var b=futures.get(1).get();
            assertEquals(HttpStatus.OK,a.getStatusCode());assertEquals(HttpStatus.OK,b.getStatusCode());
            assertEquals(a.getBody().get("id"),b.getBody().get("id"));
        }
        assertEquals(8,stock());assertEquals(1,count("vendas"));
    }
    @Test void committedSaleReachesAnOpenPosInUnderTwoSeconds() throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+serverPort+"/api/pos/eventos"))
                .header("Authorization","Bearer "+token).header("Accept","text/event-stream").GET().build();
        var stream=HttpClient.newHttpClient().send(request,HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200,stream.statusCode());
        var received=CompletableFuture.supplyAsync(() -> {
            try(var reader=new BufferedReader(new InputStreamReader(stream.body(),StandardCharsets.UTF_8))) {
                String event=""; String line;
                while((line=reader.readLine())!=null) {
                    if(line.startsWith("event:")) event=line.substring(6).trim();
                    if(line.isEmpty() && event.equals("estoque-atualizado")) return event;
                }
                return "stream-closed";
            } catch(Exception e) { throw new CompletionException(e); }
        });
        long started=System.nanoTime();
        var response=post(sale(1),token);
        assertEquals(HttpStatus.OK,response.getStatusCode());
        assertEquals("estoque-atualizado",received.get(2,TimeUnit.SECONDS));
        assertTrue(Duration.ofNanos(System.nanoTime()-started).toMillis()<2000,"Stock event should arrive in less than two seconds after sale submission");
        stream.body().close();
        assertEquals(9,stock());
    }
    @Test void requiresAuthentication() {
        assertEquals(HttpStatus.UNAUTHORIZED,http.postForEntity("/api/pos/vendas",sale(1),Map.class).getStatusCode());
    }
    @Test void erpHistoryIncludesEverySaleAndFiltersDatesWithoutExposingOtherCompanies() {
        var posVenda = post(sale(1), token).getBody();
        int posId = ((Number) posVenda.get("id")).intValue();
        db.update("UPDATE vendas SET data=? WHERE id=?", Timestamp.valueOf("2025-06-01 18:30:00"), posId);

        int erpId = 0;
        for (int i = 0; i < 51; i++) {
            var dia = i == 0 ? "2025-06-03 10:00:00" : "2025-06-02 10:00:00";
            erpId = db.queryForObject("INSERT INTO vendas (empresa_id,total,data,origem) VALUES (?,?,?,'ERP') RETURNING id",
                    Integer.class, empresa, new BigDecimal("15.00"), Timestamp.valueOf(dia));
        }
        db.update("INSERT INTO itens_venda (venda_id,produto_id,quantidade,preco_unitario) VALUES (?,?,?,?)",
                erpId, produto, 1, new BigDecimal("15.00"));

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        var request = new HttpEntity<>(headers);
        assertEquals(52, http.exchange("/api/vendas", HttpMethod.GET, request, List.class).getBody().size());
        var dia = http.exchange("/api/vendas?data=2025-06-01", HttpMethod.GET, request, List.class).getBody();
        assertEquals(1, dia.size());
        assertEquals(posId, ((Number) ((Map<?,?>) dia.get(0)).get("id")).intValue());
        assertEquals(51, http.exchange("/api/vendas?inicio=2025-06-02&fim=2025-06-03", HttpMethod.GET, request, List.class).getBody().size());
        assertEquals(HttpStatus.BAD_REQUEST, http.exchange("/api/vendas?inicio=2025-06-03&fim=2025-06-02", HttpMethod.GET, request, Map.class).getStatusCode());
        var detalhes = http.exchange("/api/vendas/" + erpId, HttpMethod.GET, request, Map.class).getBody();
        assertEquals("ERP", detalhes.get("origem"));
        assertEquals(1, ((List<?>) detalhes.get("itens")).size());

        var outra = account("outra-historico");
        HttpHeaders outroHeader = new HttpHeaders();
        outroHeader.setBearerAuth((String) outra.get("token"));
        var outroRequest = new HttpEntity<>(outroHeader);
        assertTrue(http.exchange("/api/vendas", HttpMethod.GET, outroRequest, List.class).getBody().isEmpty());
        assertEquals(HttpStatus.BAD_REQUEST, http.exchange("/api/vendas/" + erpId, HttpMethod.GET, outroRequest, Map.class).getStatusCode());
    }

    HttpEntity<?> authorized(String bearer) { HttpHeaders h=new HttpHeaders();h.setBearerAuth(bearer);return new HttpEntity<>(h); }
    ResponseEntity<Map> erpPost(String url, Map<String,Object> body) { HttpHeaders h=new HttpHeaders();h.setBearerAuth(token);return http.exchange(url,HttpMethod.POST,new HttpEntity<>(body,h),Map.class); }
    String perguntarIA(String pergunta, String bearer) {
        HttpHeaders h=new HttpHeaders();h.setBearerAuth(bearer);
        var response=http.exchange("/api/ia/perguntar",HttpMethod.POST,new HttpEntity<>(Map.of("pergunta",pergunta),h),Map.class);
        assertEquals(HttpStatus.OK,response.getStatusCode(),String.valueOf(response.getBody()));
        return String.valueOf(response.getBody().get("resposta"));
    }
    void lot(String code, int qty, java.time.LocalDate expiry) {
        db.update("INSERT INTO estoque_lotes(empresa_id,produto_id,estoque_id,codigo,validade,quantidade) SELECT empresa_id,produto_id,id,?,?,? FROM estoque WHERE produto_id=?",code,expiry,qty,produto);
    }
    @Test void zeroStockCannotCreateSaleOrFinancialEntry() {
        db.update("UPDATE estoque SET quantidade=0 WHERE produto_id=?",produto);
        assertEquals(HttpStatus.BAD_REQUEST,post(sale(1),token).getStatusCode());
        assertEquals(0,count("vendas"));assertEquals(0,count("contas_receber"));assertEquals(0,stock());
    }
    @Test void posUsesFefoAndNeverSellsExpiredLots() {
        var today=InventarioService.hoje();
        db.update("UPDATE produtos SET controla_validade=true WHERE id=?",produto);
        lot("VENCIDO",2,today.minusDays(1));lot("PRIMEIRO",3,today);lot("DEPOIS",5,today.plusDays(20));
        var response=post(sale(4),token);
        assertEquals(HttpStatus.OK,response.getStatusCode(),String.valueOf(response.getBody()));
        assertEquals(6,stock());
        assertEquals(2,db.queryForObject("SELECT quantidade FROM estoque_lotes WHERE codigo='VENCIDO'",Integer.class));
        assertEquals(0,db.queryForObject("SELECT quantidade FROM estoque_lotes WHERE codigo='PRIMEIRO'",Integer.class));
        assertEquals(4,db.queryForObject("SELECT quantidade FROM estoque_lotes WHERE codigo='DEPOIS'",Integer.class));
        assertEquals(4,db.queryForObject("SELECT SUM(quantidade) FROM venda_lotes",Integer.class));
        var catalog=http.exchange("/api/produtos",HttpMethod.GET,authorized(token),List.class).getBody();
        assertEquals(4,((Number)((Map<?,?>)catalog.get(0)).get("quantidadeEstoque")).intValue());
        assertEquals(HttpStatus.BAD_REQUEST,post(sale(5),token).getStatusCode());
        assertEquals(1,count("vendas"));assertEquals(6,stock());
    }
    @Test void purchasesRequireExpiryAndCreateTraceableLotsAtomically() {
        db.update("UPDATE produtos SET controla_validade=true WHERE id=?",produto);
        int supplier=db.queryForObject("INSERT INTO fornecedores(empresa_id,nome) VALUES (?,'Fornecedor') RETURNING id",Integer.class,empresa);
        var item=new HashMap<String,Object>(Map.of("produtoId",produto,"quantidade",3));
        var purchase=new HashMap<String,Object>(Map.of("fornecedorId",supplier,"itens",List.of(item)));
        assertEquals(HttpStatus.BAD_REQUEST,erpPost("/api/compras",purchase).getStatusCode());
        assertEquals(0,count("compras"));assertEquals(10,stock());
        item.put("lote","NOVO");item.put("validade",InventarioService.hoje().minusDays(1).toString());
        assertEquals(HttpStatus.BAD_REQUEST,erpPost("/api/compras",purchase).getStatusCode());
        item.put("validade",InventarioService.hoje().plusDays(10).toString());
        assertEquals(HttpStatus.OK,erpPost("/api/compras",purchase).getStatusCode());
        assertEquals(13,stock());assertEquals(3,db.queryForObject("SELECT quantidade FROM estoque_lotes WHERE codigo='NOVO'",Integer.class));
        assertEquals(13,db.queryForObject("SELECT SUM(quantidade) FROM estoque_lotes",Integer.class));
        var catalog=http.exchange("/api/produtos",HttpMethod.GET,authorized(token),List.class).getBody();
        assertEquals(3,((Number)((Map<?,?>)catalog.get(0)).get("quantidadeEstoque")).intValue());
    }
    @Test void erpSalesAlsoUseLotsAndExposePrintableReceiptData() {
        db.update("UPDATE produtos SET controla_validade=true WHERE id=?",produto);
        lot("ATUAL",10,InventarioService.hoje().plusDays(5));
        int client=db.queryForObject("INSERT INTO clientes(empresa_id,nome) VALUES (?,'Cliente ERP') RETURNING id",Integer.class,empresa);
        var response=erpPost("/api/vendas",Map.of("clienteId",client,"itens",List.of(Map.of("produtoId",produto,"quantidade",2))));
        assertEquals(HttpStatus.OK,response.getStatusCode(),String.valueOf(response.getBody()));
        var receiptResponse=http.exchange("/api/vendas/"+((Number)response.getBody().get("id")).intValue(),HttpMethod.GET,authorized(token),Map.class);
        assertEquals(HttpStatus.OK,receiptResponse.getStatusCode(),String.valueOf(receiptResponse.getBody()));
        var receipt=receiptResponse.getBody();
        assertEquals("ERP",receipt.get("origem"));assertEquals("Cliente ERP",receipt.get("cliente"));assertNotNull(receipt.get("empresa"));
        assertEquals(1,((List<?>)receipt.get("lotes")).size());assertEquals(8,stock());
        assertEquals("Café",((Map<?,?>)((List<?>)receipt.get("itens")).get(0)).get("nome"));
    }
    @Test void legacyExpiryCanBeRegularizedOnlyByItsCompanyAndOnlyOnce() {
        db.update("UPDATE produtos SET controla_validade=true WHERE id=?",produto);
        lot("LEGADO",10,null);
        int id=db.queryForObject("SELECT id FROM estoque_lotes WHERE produto_id=?",Integer.class,produto);
        assertEquals(HttpStatus.BAD_REQUEST,post(sale(1),token).getStatusCode());
        var other=account("intruso-lote");
        HttpHeaders h=new HttpHeaders();h.setBearerAuth((String)other.get("token"));
        var body=Map.of("lote","REAL","validade",InventarioService.hoje().plusDays(10).toString());
        assertEquals(HttpStatus.BAD_REQUEST,http.exchange("/api/inventario/lotes/"+id+"/validade",HttpMethod.PUT,new HttpEntity<>(body,h),String.class).getStatusCode());
        h.setBearerAuth(token);
        assertTrue(http.exchange("/api/inventario/lotes/"+id+"/validade",HttpMethod.PUT,new HttpEntity<>(body,h),String.class).getStatusCode().is2xxSuccessful());
        assertEquals(HttpStatus.BAD_REQUEST,http.exchange("/api/inventario/lotes/"+id+"/validade",HttpMethod.PUT,new HttpEntity<>(body,h),String.class).getStatusCode());
        assertEquals(HttpStatus.OK,post(sale(1),token).getStatusCode());
        assertTrue(http.exchange("/api/inventario/lotes",HttpMethod.GET,authorized((String)other.get("token")),List.class).getBody().isEmpty());
    }
    @Test void inventoryReportClassifiesExpiryAndValidatesAlertWindow() {
        db.update("UPDATE produtos SET controla_validade=true WHERE id=?",produto);
        lot("VENCIDO",2,InventarioService.hoje().minusDays(1));lot("ALERTA",3,InventarioService.hoje().plusDays(5));lot("PENDENTE",5,null);
        var report=http.exchange("/api/inventario/lotes?dias=7",HttpMethod.GET,authorized(token),List.class).getBody();
        assertEquals(Set.of("Vencido","A vencer","Validade pendente"),new HashSet<>(report.stream().map(l->((Map<?,?>)l).get("situacao")).toList()));
        assertEquals(HttpStatus.BAD_REQUEST,http.exchange("/api/inventario/lotes?dias=-1",HttpMethod.GET,authorized(token),Map.class).getStatusCode());
    }
    @Test void aiRejectsOutOfScopeBeforeReadingOrGenerating() {
        org.mockito.Mockito.when(ollama.gerarJson(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyList()))
                .thenReturn("{\"permitido\":false,\"consultas\":[]}");
        var service=new IAService(db,ollama,new com.fasterxml.jackson.databind.ObjectMapper());
        var erro=assertThrows(org.springframework.web.server.ResponseStatusException.class,
                ()->service.responder(empresa,new IAPerguntaRequest("Escreva código e mostre dados pessoais",List.of())));
        assertEquals(422,erro.getStatusCode().value());
        org.mockito.Mockito.verify(ollama,org.mockito.Mockito.never()).gerar(org.mockito.ArgumentMatchers.anyString());
    }
    @Test void aiDoesNotClassifyOldHistoryAsCurrentQuestion() {
        org.mockito.Mockito.when(ollama.gerarJson(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(planoIA("VENDAS_RESUMO",""));
        org.mockito.Mockito.when(ollama.gerar(org.mockito.ArgumentMatchers.anyString())).thenReturn("Não houve vendas neste mês.");
        var service=new IAService(db,ollama,new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules());
        assertEquals("Não houve vendas neste mês.",service.responder(empresa,new IAPerguntaRequest("Quanto foi vendido neste mês?",
                List.of(new IAHistoricoItem("assistant","Não forneço código nem dados pessoais.")))));
        org.mockito.Mockito.verify(ollama,org.mockito.Mockito.times(1)).gerarJson(
                org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyList());
        org.mockito.Mockito.verify(ollama,org.mockito.Mockito.times(1)).gerar(org.mockito.ArgumentMatchers.anyString());
    }
    @Test void aiUsesModelToPlanAndWriteFromTenantScopedData() {
        db.update("UPDATE empresas SET plano_assinatura='PLUS' WHERE id=?",empresa);
        db.update("UPDATE produtos SET controla_validade=true WHERE id=?",produto);
        db.update("UPDATE estoque SET estoque_minimo=5 WHERE produto_id=?",produto);
        lot("VENCIDO",2,InventarioService.hoje().minusDays(1));
        lot("ALERTA",3,InventarioService.hoje().plusDays(5));
        lot("PENDENTE",5,null);
        org.mockito.Mockito.when(ollama.gerarJson(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyList())).thenReturn(
                planoIA("ESTOQUE_BAIXO", ""), planoIA("PRODUTOS_VALIDADE", ""),
                planoIA("INVENTARIO_LOTES", ",\"dias\":7,\"situacao\":\"VENCIDOS_E_A_VENCER\""),
                planoIA("INVENTARIO_LOTES", ",\"situacao\":\"VENCIDO\""));
        var contexto=new java.util.concurrent.atomic.AtomicReference<String>();
        org.mockito.Mockito.when(ollama.gerar(org.mockito.ArgumentMatchers.anyString())).thenAnswer(invocation -> {
            contexto.set(invocation.getArgument(0)); return "Resposta redigida pelo modelo a partir da consulta.";
        });
        assertEquals("Resposta redigida pelo modelo a partir da consulta.", perguntarIA("Quais produtos estão com estoque baixo?",token));
        assertTrue(contexto.get().contains("\"quantidadeEstoque\":3"),contexto.get());
        assertTrue(contexto.get().contains("\"quantidadeTotal\":10"),contexto.get());
        assertTrue(contexto.get().contains("\"quantidade_bloqueada\":7"),contexto.get());
        perguntarIA("Quais produtos exigem validade?",token);
        assertTrue(contexto.get().contains("Café"),contexto.get());
        assertTrue(contexto.get().contains("\"quantidadeEstoque\":3"),contexto.get());
        perguntarIA("Quais lotes estão vencidos ou a vencer nos próximos 7 dias?",token);
        assertTrue(contexto.get().contains("\"lote\":\"VENCIDO\""),contexto.get());
        assertTrue(contexto.get().contains("\"lote\":\"ALERTA\""),contexto.get());
        assertTrue(contexto.get().contains("lotes_liberados_para_venda"),contexto.get());
        assertTrue(contexto.get().contains("\"vendavel\":true"),contexto.get());
        assertTrue(contexto.get().contains("\"status_validade\":\"Vence em 5 dia(s)\""),contexto.get());
        assertFalse(contexto.get().contains("\"lote\":\"PENDENTE\""),contexto.get());
        assertFalse(contexto.get().contains("\"situacao\":\"PENDENTE\""),contexto.get());
        var outra=account("outra-ia");
        int outraEmpresa=((Number)((Map<?,?>)outra.get("usuario")).get("empresaId")).intValue();
        db.update("UPDATE empresas SET plano_assinatura='PLUS' WHERE id=?",outraEmpresa);
        perguntarIA("Quais lotes estão vencidos?",String.valueOf(outra.get("token")));
        assertFalse(contexto.get().contains("\"lote\":\"VENCIDO\""),contexto.get());
        org.mockito.Mockito.verify(ollama,org.mockito.Mockito.times(4)).gerarJson(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyList());
        org.mockito.Mockito.verify(ollama,org.mockito.Mockito.times(4)).gerar(org.mockito.ArgumentMatchers.anyString());
    }
    String planoIA(String ferramenta,String campos) {
        return "{\"permitido\":true,\"consultas\":[{\"ferramenta\":\""+ferramenta+"\""+campos+"}]}";
    }
    @Test void aiReceiptLookupChecksCompanyBeforeProvidingDataToModel() {
        db.update("UPDATE empresas SET plano_assinatura='PLUS' WHERE id=?",empresa);
        int venda=((Number)post(sale(1),token).getBody().get("id")).intValue();
        org.mockito.Mockito.when(ollama.gerarJson(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(planoIA("VENDA_COMPROVANTE",",\"vendaId\":"+venda));
        var contexto=new java.util.concurrent.atomic.AtomicReference<String>();
        org.mockito.Mockito.when(ollama.gerar(org.mockito.ArgumentMatchers.anyString())).thenAnswer(invocation -> {
            contexto.set(invocation.getArgument(0)); return "Análise do comprovante pelo modelo.";
        });
        assertEquals("Análise do comprovante pelo modelo.",perguntarIA("Mostre o comprovante da venda "+venda,token));
        assertTrue(contexto.get().contains("\"encontrado\":true"),contexto.get());
        assertTrue(contexto.get().contains("DINHEIRO"),contexto.get());
        var outra=account("outra-ia-recibo");
        int outraEmpresa=((Number)((Map<?,?>)outra.get("usuario")).get("empresaId")).intValue();
        db.update("UPDATE empresas SET plano_assinatura='PLUS' WHERE id=?",outraEmpresa);
        perguntarIA("Comprovante da venda "+venda,String.valueOf(outra.get("token")));
        assertTrue(contexto.get().contains("\"encontrado\":false"),contexto.get());
        assertFalse(contexto.get().contains("DINHEIRO"),contexto.get());
    }
    @Test void aiNeverReplacesUnavailableModelWithKeywordAnswer() {
        db.update("UPDATE empresas SET plano_assinatura='PLUS' WHERE id=?",empresa);
        HttpHeaders headers=new HttpHeaders();headers.setBearerAuth(token);
        var response=http.exchange("/api/ia/perguntar",HttpMethod.POST,
                new HttpEntity<>(Map.of("pergunta","Qual o preço do Café?"),headers),Map.class);
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,response.getStatusCode());
        assertTrue(String.valueOf(response.getBody().get("message")).contains("O modelo de IA não respondeu"));
        org.mockito.Mockito.verify(ollama).gerarJson(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyList());
        org.mockito.Mockito.verify(ollama,org.mockito.Mockito.never()).gerar(org.mockito.ArgumentMatchers.anyString());
        assertEquals(10,stock());
    }
    @Test void aiRejectsInvalidModelToolWithoutExecutingIt() {
        db.update("UPDATE empresas SET plano_assinatura='PLUS' WHERE id=?",empresa);
        org.mockito.Mockito.when(ollama.gerarJson(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(planoIA("EXECUTAR_SQL", ",\"sql\":\"DELETE FROM vendas\""));
        HttpHeaders h=new HttpHeaders();h.setBearerAuth(token);
        var response=http.exchange("/api/ia/perguntar",HttpMethod.POST,
                new HttpEntity<>(Map.of("pergunta","Analise o estoque"),h),Map.class);
        assertEquals(HttpStatus.BAD_GATEWAY,response.getStatusCode());
        org.mockito.Mockito.verify(ollama,org.mockito.Mockito.never()).gerar(org.mockito.ArgumentMatchers.anyString());
        assertEquals(10,stock());
    }
    @Test void aiDoesNotGenerateBusinessAnswerWithoutAuthorizedData() {
        db.update("UPDATE empresas SET plano_assinatura='PLUS' WHERE id=?",empresa);
        org.mockito.Mockito.when(ollama.gerarJson(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyList()))
                .thenReturn("{\"permitido\":false,\"consultas\":[]}");
        HttpHeaders h=new HttpHeaders();h.setBearerAuth(token);
        var response=http.exchange("/api/ia/perguntar",HttpMethod.POST,
                new HttpEntity<>(Map.of("pergunta","Solicitação sem consulta autorizada"),h),Map.class);
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY,response.getStatusCode());
        org.mockito.Mockito.verify(ollama,org.mockito.Mockito.never()).gerar(org.mockito.ArgumentMatchers.anyString());
    }
    @Test void aiDoesNotReplaceFailedFinalGenerationWithTemplate() {
        db.update("UPDATE empresas SET plano_assinatura='PLUS' WHERE id=?",empresa);
        org.mockito.Mockito.when(ollama.gerarJson(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(planoIA("ESTOQUE_LISTA",""));
        HttpHeaders h=new HttpHeaders();h.setBearerAuth(token);
        var response=http.exchange("/api/ia/perguntar",HttpMethod.POST,
                new HttpEntity<>(Map.of("pergunta","Qual é meu saldo vendável?"),h),Map.class);
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,response.getStatusCode());
        org.mockito.Mockito.verify(ollama).gerar(org.mockito.ArgumentMatchers.anyString());
    }
    @Test void aiReportsWhenDataSampleExceedsModelContext() {
        db.update("UPDATE empresas SET plano_assinatura='PLUS' WHERE id=?",empresa);
        for(int i=0;i<200;i++) {
            int id=db.queryForObject("INSERT INTO produtos(empresa_id,nome,codigo,preco) VALUES (?,?,?,1) RETURNING id",Integer.class,
                    empresa,"Produto de teste com nome longo "+i+" para validar capacidade do contexto","CTX"+i);
            db.update("INSERT INTO estoque(empresa_id,produto_id,quantidade) VALUES (?,?,1)",empresa,id);
        }
        org.mockito.Mockito.when(ollama.gerarJson(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyList())).thenReturn(planoIA("ESTOQUE_LISTA",""));
        var contexto=new java.util.concurrent.atomic.AtomicReference<String>();
        org.mockito.Mockito.when(ollama.gerar(org.mockito.ArgumentMatchers.anyString())).thenAnswer(invocation -> {
            contexto.set(invocation.getArgument(0));return "Análise com indicação de amostra limitada.";
        });
        assertEquals("Análise com indicação de amostra limitada.",perguntarIA("Analise meu estoque",token));
        assertTrue(contexto.get().contains("\"limitado_pelo_contexto\":true"));
        assertTrue(contexto.get().length()<16000,"O contexto excedeu o limite esperado");
    }
    @Test void logoutRevogaTokenNoServidor() {
        assertEquals(HttpStatus.OK,erpPost("/api/auth/logout",Map.of()).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED,http.exchange("/api/produtos",HttpMethod.GET,authorized(token),Map.class).getStatusCode());
    }
    Map<String,Object> respostas(String desafio,String primeira) {
        return Map.of("desafio",desafio,"respostaSeguranca1",primeira,"respostaSeguranca2","resposta dois","respostaSeguranca3","resposta tres");
    }
    @Test void segundoFatorPermiteCorrigirErroESoPodeSerUsadoUmaVez() {
        var desafio=auth.login(Map.of("email","operador@pos.test","senha","senha-teste-123"));
        String codigo=(String)desafio.get("desafio");
        assertThrows(SecurityException.class,()->auth.verificarSegundoFator(respostas(codigo,"errada")));
        assertNotNull(auth.verificarSegundoFator(respostas(codigo,"resposta um")).get("token"));
        assertThrows(SecurityException.class,()->auth.verificarSegundoFator(respostas(codigo,"resposta um")));
    }
    @Test void segundoFatorExpiraAposTresErros() {
        String codigo=(String)auth.login(Map.of("email","operador@pos.test","senha","senha-teste-123")).get("desafio");
        for(int n=0;n<3;n++)assertThrows(SecurityException.class,()->auth.verificarSegundoFator(respostas(codigo,"errada")));
        assertThrows(SecurityException.class,()->auth.verificarSegundoFator(respostas(codigo,"resposta um")));
    }
    @Test void atualizarFornecedorNaoDevolveClienteComMesmoId() {
        int comum=900001;
        db.update("INSERT INTO clientes(id,empresa_id,nome) VALUES (?,?,'Cliente preservado')",comum,empresa);
        db.update("INSERT INTO fornecedores(id,empresa_id,nome) VALUES (?,?,'Fornecedor antigo')",comum,empresa);
        HttpHeaders headers=new HttpHeaders();headers.setBearerAuth(token);
        var response=http.exchange("/api/pessoas/"+comum,HttpMethod.PUT,
                new HttpEntity<>(Map.of("nome","Fornecedor atualizado","tipo","FORNECEDOR"),headers),Map.class);
        assertEquals(HttpStatus.OK,response.getStatusCode(),String.valueOf(response.getBody()));
        assertEquals("FORNECEDOR",response.getBody().get("tipo"));
        assertEquals("Fornecedor atualizado",response.getBody().get("nome"));
        assertEquals("Cliente preservado",db.queryForObject("SELECT nome FROM clientes WHERE id=?",String.class,comum));
        assertEquals("FORNECEDOR",http.exchange("/api/pessoas/"+comum+"?tipo=FORNECEDOR",HttpMethod.GET,authorized(token),Map.class).getBody().get("tipo"));
        assertEquals(HttpStatus.BAD_REQUEST,http.exchange("/api/pessoas/"+comum+"?tipo=INVALIDO",HttpMethod.GET,authorized(token),Map.class).getStatusCode());
    }
    @Test void pessoasSemDocumentoNaoConflitamEAtualizacaoAceitaNulos() {
        int pessoa=((Number)erpPost("/api/pessoas",Map.of("nome","Pessoa A","tipo","CLIENTE")).getBody().get("id")).intValue();
        assertEquals(HttpStatus.OK,erpPost("/api/pessoas",Map.of("nome","Pessoa B","tipo","CLIENTE")).getStatusCode());
        var dados=new HashMap<String,Object>();dados.put("nome","Pessoa alterada");dados.put("tipo","CLIENTE");dados.put("documento",null);dados.put("telefone",null);dados.put("email",null);
        HttpHeaders headers=new HttpHeaders();headers.setBearerAuth(token);
        var atualizado=http.exchange("/api/pessoas/"+pessoa,HttpMethod.PUT,new HttpEntity<>(dados,headers),Map.class);
        assertEquals(HttpStatus.OK,atualizado.getStatusCode(),String.valueOf(atualizado.getBody()));
        dados.put("nome","x".repeat(151));
        assertEquals(HttpStatus.BAD_REQUEST,http.exchange("/api/pessoas/"+pessoa,HttpMethod.PUT,new HttpEntity<>(dados,headers),Map.class).getStatusCode());
    }
    @Test void produtoRecusaPrecoFracionarioDemaisECategoriaTruncada() {
        assertEquals(HttpStatus.BAD_REQUEST,erpPost("/api/produtos",Map.of("nome","Produto","precoVenda","10.123")).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,erpPost("/api/produtos",Map.of("nome","Produto","controlaValidade","qualquer texto")).getStatusCode());
        db.update("UPDATE empresas SET plano_assinatura='PLUS' WHERE id=?",empresa);
        int categoria=db.queryForObject("INSERT INTO categorias(empresa_id,nome) VALUES (?,'Categoria') RETURNING id",Integer.class,empresa);
        assertEquals(HttpStatus.BAD_REQUEST,erpPost("/api/produtos",Map.of("nome","Produto","categoriaId",new BigDecimal(categoria+".5"))).getStatusCode());
        assertEquals(1,count("produtos"));
    }
    @Test void entradaETotalForaDoLimiteRevertemMovimentacao() {
        int fornecedor=db.queryForObject("INSERT INTO fornecedores(empresa_id,nome) VALUES (?,'Fornecedor') RETURNING id",Integer.class,empresa);
        db.update("UPDATE produtos SET preco_compra=1000000 WHERE id=?",produto);
        var compra=Map.<String,Object>of("fornecedorId",fornecedor,"itens",List.of(Map.of("produtoId",produto,"quantidade",1000000)));
        assertEquals(HttpStatus.BAD_REQUEST,erpPost("/api/compras",compra).getStatusCode());
        assertEquals(0,count("compras"));assertEquals(10,stock());
        db.update("UPDATE estoque SET quantidade=? WHERE produto_id=?",Integer.MAX_VALUE,produto);
        compra=Map.of("fornecedorId",fornecedor,"itens",List.of(Map.of("produtoId",produto,"quantidade",1)));
        assertEquals(HttpStatus.BAD_REQUEST,erpPost("/api/compras",compra).getStatusCode());
        assertEquals(Integer.MAX_VALUE,stock());assertEquals(0,count("compras"));
    }
    @Test void saldoSemLoteNaoHerdaValidadeDoLegado() {
        db.update("UPDATE produtos SET controla_validade=true WHERE id=?",produto);
        db.update("INSERT INTO estoque_lotes(empresa_id,produto_id,estoque_id,codigo,quantidade,legado,validade) SELECT empresa_id,produto_id,id,'LEGADO',5,true,CURRENT_DATE+30 FROM estoque WHERE produto_id=?",produto);
        assertEquals(HttpStatus.BAD_REQUEST,post(sale(6),token).getStatusCode());
        assertEquals(HttpStatus.OK,post(sale(5),token).getStatusCode());
        assertEquals(5,stock());
        assertEquals(5,db.queryForObject("SELECT SUM(quantidade)::integer FROM estoque_lotes WHERE validade IS NULL",Integer.class));
    }
    @Test void parametrosMalformadosRetornamErroDeValidacao() {
        assertEquals(HttpStatus.BAD_REQUEST,http.exchange("/api/produtos/abc",HttpMethod.GET,authorized(token),Map.class).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,http.exchange("/api/vendas?inicio=ontem",HttpMethod.GET,authorized(token),Map.class).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,http.exchange("/api/inventario/lotes?dias=abc",HttpMethod.GET,authorized(token),Map.class).getStatusCode());
    }
    @Test void categoriasSimultaneasNaoDuplicamNomeNaEmpresa() throws Exception {
        db.update("UPDATE empresas SET plano_assinatura='PLUS' WHERE id=?",empresa);
        var iniciar=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var primeira=executor.submit(()->{iniciar.await();return erpPost("/api/categorias",Map.of("nome","Bebidas")).getStatusCode();});
            var segunda=executor.submit(()->{iniciar.await();return erpPost("/api/categorias",Map.of("nome","bebidas")).getStatusCode();});
            iniciar.countDown();
            assertEquals(Set.of(HttpStatus.OK,HttpStatus.BAD_REQUEST),Set.of(primeira.get(10,TimeUnit.SECONDS),segunda.get(10,TimeUnit.SECONDS)));
            assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM categorias WHERE empresa_id=? AND lower(nome)='bebidas'",Integer.class,empresa));
        }
    }
    @Test void migrationPreservesLegacyStockAndCanRunAgainWithoutDuplicatingLots() {
        var script=new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(new org.springframework.core.io.ClassPathResource("schema.sql"));
        script.execute(db.getDataSource());
        script.execute(db.getDataSource());
        assertEquals(10,stock());
        assertEquals(1,count("estoque_lotes"));
        assertEquals(10,db.queryForObject("SELECT SUM(quantidade) FROM estoque_lotes",Integer.class));
        assertEquals(true,db.queryForObject("SELECT legado FROM estoque_lotes",Boolean.class));
    }
}
