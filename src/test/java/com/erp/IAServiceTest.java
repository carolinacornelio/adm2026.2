package com.erp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class IAServiceTest {
    @Test void correctFinancialAmountWithSentencePunctuationIsAccepted() {
        var db=mock(JdbcTemplate.class); var modelo=mock(OllamaClient.class);
        when(modelo.gerarJson(anyString(),anyList())).thenReturn("{\"permitido\":true,\"consultas\":[{\"ferramenta\":\"VENDAS_RESUMO\"}]}");
        when(db.queryForMap(anyString(),anyInt())).thenReturn(Map.of("total",new BigDecimal("123.45"),"quantidade_vendas",1));
        when(modelo.gerar(anyString())).thenReturn("Faturamento: R$ 123,45, no periodo.");
        var ia=new IAService(db,modelo,new ObjectMapper().findAndRegisterModules());
        assertEquals("Faturamento: R$ 123,45, no periodo.",ia.responder(7,new IAPerguntaRequest("Quanto foi faturado?",List.of())));
    }
    @Test void inventedFinancialAmountIsNeverReturned() {
        var db=mock(JdbcTemplate.class); var modelo=mock(OllamaClient.class);
        when(modelo.gerarJson(anyString(),anyList())).thenReturn("{\"permitido\":true,\"consultas\":[{\"ferramenta\":\"VENDAS_RESUMO\"}]}");
        when(db.queryForMap(anyString(),anyInt())).thenReturn(Map.of("total",new BigDecimal("123.45"),"quantidade_vendas",1));
        when(modelo.gerar(anyString())).thenReturn("Faturamento: R$ 999,99.");
        var ia=new IAService(db,modelo,new ObjectMapper().findAndRegisterModules());
        assertEquals(502,assertThrows(ResponseStatusException.class,
                ()->ia.responder(7,new IAPerguntaRequest("Quanto foi faturado?",List.of()))).getStatusCode().value());
    }
    @Test void onlyPlanIsReusedAndDatabaseIsReadAgain() {
        var db=mock(JdbcTemplate.class);
        var modelo=mock(OllamaClient.class);
        when(modelo.gerarJson(anyString(),anyList())).thenReturn("{\"permitido\":true,\"consultas\":[{\"ferramenta\":\"VENDAS_RESUMO\"}]}");
        when(db.queryForMap(anyString(),anyInt())).thenReturn(
                Map.of("total",new BigDecimal("100.00"),"quantidade_vendas",1),
                Map.of("total",new BigDecimal("250.00"),"quantidade_vendas",2),
                Map.of("total",new BigDecimal("7.00"),"quantidade_vendas",1));
        List<String> contextos=new ArrayList<>();
        when(modelo.gerar(anyString())).thenAnswer(invocation->{ contextos.add(invocation.getArgument(0)); return "Resposta do modelo"; });
        var ia=new IAService(db,modelo,new ObjectMapper().findAndRegisterModules());
        var pergunta=new IAPerguntaRequest("Quanto foi faturado neste mes?",List.of());
        ia.responder(7,pergunta); ia.responder(7,pergunta); ia.responder(8,pergunta);
        assertTrue(contextos.get(0).contains("\"total\":100.00"));
        assertTrue(contextos.get(1).contains("\"total\":250.00"));
        assertTrue(contextos.get(2).contains("\"total\":7.00"));
        verify(modelo,times(2)).gerarJson(anyString(),anyList());
        verify(modelo,times(3)).gerar(anyString());
        verify(db,times(2)).queryForMap(anyString(),eq(7));
        verify(db,times(1)).queryForMap(anyString(),eq(8));
    }
    @Test void concurrentRequestIsRejectedWithoutQueueing() throws Exception {
        var db=mock(JdbcTemplate.class); var modelo=mock(OllamaClient.class);
        var iniciou=new CountDownLatch(1); var liberar=new CountDownLatch(1);
        when(modelo.gerarJson(anyString(),anyList())).thenAnswer(invocation->{ iniciou.countDown(); liberar.await(5,TimeUnit.SECONDS); return "{\"permitido\":false,\"consultas\":[]}"; });
        var ia=new IAService(db,modelo,new ObjectMapper());
        var pergunta=new IAPerguntaRequest("pergunta",List.of());
        try(var executor=Executors.newSingleThreadExecutor()) {
            var primeira=executor.submit(()->assertThrows(ResponseStatusException.class,()->ia.responder(7,pergunta)));
            assertTrue(iniciou.await(3,TimeUnit.SECONDS));
            try { assertEquals(429,assertThrows(ResponseStatusException.class,()->ia.responder(7,pergunta)).getStatusCode().value()); }
            finally { liberar.countDown(); }
            assertEquals(422,primeira.get(3,TimeUnit.SECONDS).getStatusCode().value());
        }
        verifyNoInteractions(db);
    }
}
