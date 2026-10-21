package com.erp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.web.server.ResponseStatusException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class OllamaClientTest {
    HttpServer server;
    ObjectMapper mapper = new ObjectMapper();
    AtomicReference<String> resposta = new AtomicReference<>();
    AtomicReference<String> enviado = new AtomicReference<>();
    OllamaClient client;

    @BeforeEach void iniciar() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/", exchange -> {
            byte[] body = "Ollama is running".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,body.length);
            exchange.getResponseBody().write(body); exchange.close();
        });
        server.createContext("/api/chat", exchange -> {
            enviado.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            byte[] body = mapper.writeValueAsBytes(java.util.Map.of("message",java.util.Map.of("content",resposta.get())));
            exchange.getResponseHeaders().add("Content-Type","application/json");
            exchange.sendResponseHeaders(200,body.length);
            exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        client = new OllamaClient(mapper,"http://127.0.0.1:"+server.getAddress().getPort(),"gemma3:4b",true,10);
    }
    @AfterEach void parar() { server.stop(0); }

    @Test void finalStructuredAnswerIsParsedWithoutAnotherInference() throws Exception {
        resposta.set("{\"permitido\":true,\"resposta\":\"Faturamento: R$ 123,45.\"}");
        assertEquals("Faturamento: R$ 123,45.",client.gerar("Dados oficiais: total=123.45"));
        var json=mapper.readTree(enviado.get());
        assertEquals("gemma3:4b",json.path("model").asText());
        assertEquals("15m",json.path("keep_alive").asText());
        assertTrue(json.path("format").path("properties").has("resposta"));
        assertEquals(4096,json.path("options").path("num_ctx").asInt());
    }
    @Test void finalDenialNeverExposesItsText() {
        resposta.set("{\"permitido\":false,\"resposta\":\"Conteudo que nao deve aparecer\"}");
        var erro=assertThrows(ResponseStatusException.class,()->client.gerar("pedido fora do escopo"));
        assertEquals(422,erro.getStatusCode().value());
        assertFalse(erro.getReason().contains("Conteudo que nao deve aparecer"));
    }
    @Test void unstructuredOrMissingPermissionIsRejected() {
        resposta.set("{\"resposta\":\"Resposta sem permissao\"}");
        assertEquals(502,assertThrows(ResponseStatusException.class,()->client.gerar("dados")).getStatusCode().value());
    }
    @Test void plannerRequiresScopeAndExcludesPersonalTools() throws Exception {
        resposta.set("{\"permitido\":false,\"consultas\":[]}");
        client.gerarJson("dados",java.util.List.of());
        var formato=mapper.readTree(enviado.get()).path("format");
        assertTrue(formato.path("required").toString().contains("permitido"));
        assertFalse(formato.toString().contains("CLIENTES_RANKING"));
    }
}
