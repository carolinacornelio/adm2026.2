package com.erp;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.springframework.boot.SpringApplication;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/** Servidor exclusivo para testes de navegador, com banco descartável. Não integra o JAR de produção. */
public class PosBrowserServer {
    public static void main(String[] args) throws Exception {
        var postgres = EmbeddedPostgres.start();
        var context = SpringApplication.run(ErpApplication.class,
                "--server.port=18765", "--server.address=127.0.0.1",
                "--spring.config.import=optional:classpath:/pos-test-empty.properties",
                "--spring.datasource.url=" + postgres.getJdbcUrl("postgres", "postgres"),
                "--spring.datasource.username=postgres", "--spring.datasource.password=",
                "--erp.ia.ollama.enabled=false");
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            context.close();
            try { postgres.close(); } catch (Exception ignored) {}
        }));
        Map<String,Object> data = new HashMap<>();
        data.put("email","caixa@teste.local"); data.put("senha","senha-teste-123");
        data.put("nome","Operador de teste"); data.put("empresa","Loja de testes");
        data.put("perguntaSeguranca1","AMIGO_INFANCIA"); data.put("perguntaSeguranca2","EMPREGO_SONHOS"); data.put("perguntaSeguranca3","CIDADE_PAIS");
        data.put("respostaSeguranca1","resposta um"); data.put("respostaSeguranca2","resposta dois"); data.put("respostaSeguranca3","resposta tres");
        var session = context.getBean(AuthService.class).cadastrar(data);
        int empresa = ((Number)((Map<?,?>)session.get("usuario")).get("empresaId")).intValue();
        var db = context.getBean(JdbcTemplate.class);
        for (var entry : new String[][]{{"Café especial 250 g","CAFE","18.90","24"},{"Leite integral 1 L","LEITE","5.49","38"},{"Arroz branco 1 kg","ARROZ","6.99","42"}}) {
            int id = db.queryForObject("INSERT INTO produtos (empresa_id,nome,codigo,preco,preco_venda,preco_compra) VALUES (?,?,?,?::numeric,?::numeric,1) RETURNING id",Integer.class,empresa,entry[0],entry[1],entry[2],entry[2]);
            db.update("INSERT INTO estoque (empresa_id,produto_id,quantidade) VALUES (?,?,?::int)",empresa,id,entry[3]);
        }
        db.update("INSERT INTO clientes (empresa_id,nome) VALUES (?,'Cliente de teste')",empresa);
        System.out.println("POS_BROWSER_READY http://127.0.0.1:18765/login.html");
        new CountDownLatch(1).await();
    }
}
