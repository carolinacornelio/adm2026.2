package com.erp;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PosRealtimeServiceTest {
    @Test void heartbeatContinuesWhenTomcatAlreadyReleasedADisconnectedClient() throws Exception {
        verificarDesconexao(true);
    }

    @Test void stockNotificationContinuesWhenTomcatAlreadyReleasedADisconnectedClient() throws Exception {
        verificarDesconexao(false);
    }

    @SuppressWarnings("unchecked")
    private void verificarDesconexao(boolean heartbeat) throws Exception {
        var service = new PosRealtimeService();
        var fechado = mock(SseEmitter.class);
        var aberto = mock(SseEmitter.class);
        doThrow(new IllegalStateException("AsyncContext já encerrado"))
                .when(fechado).send(any(SseEmitter.SseEventBuilder.class));
        doThrow(new IllegalStateException("onError já retornou"))
                .when(fechado).completeWithError(any(Throwable.class));
        var clientes = (Map<Integer, Set<SseEmitter>>) ReflectionTestUtils.getField(service, "clientesPorEmpresa");
        Set<SseEmitter> emissores = ConcurrentHashMap.newKeySet();
        emissores.add(fechado);
        emissores.add(aberto);
        clientes.put(1, emissores);

        assertDoesNotThrow(() -> {
            if (heartbeat) service.manterConexoes();
            else service.publicarDepoisDoCommit(1, "estoque-atualizado");
        });
        verify(aberto).send(any(SseEmitter.SseEventBuilder.class));
        assertEquals(Set.of(aberto), clientes.get(1));
    }
}
