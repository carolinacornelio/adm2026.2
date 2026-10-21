package com.erp;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Distribui avisos privados por empresa somente depois do commit do banco. */
@Component
public class PosRealtimeService {
    private final Map<Integer, Set<SseEmitter>> clientesPorEmpresa = new ConcurrentHashMap<>();

    public SseEmitter assinar(int empresa) {
        var emitter = new SseEmitter(0L);
        var clientes = clientesPorEmpresa.computeIfAbsent(empresa, ignored -> ConcurrentHashMap.newKeySet());
        clientes.add(emitter);
        Runnable remover = () -> {
            clientes.remove(emitter);
            if (clientes.isEmpty()) clientesPorEmpresa.remove(empresa, clientes);
        };
        emitter.onCompletion(remover);
        emitter.onTimeout(remover);
        emitter.onError(ignored -> remover.run());
        try { emitter.send(SseEmitter.event().name("conectado").data(Map.of("empresaId", empresa))); }
        catch (IOException erro) { remover.run(); emitter.completeWithError(erro); }
        return emitter;
    }

    public void publicarDepoisDoCommit(int empresa, String evento) {
        Runnable publicar = () -> publicar(empresa, evento);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { publicar.run(); }
            });
        } else publicar.run();
    }

    private void publicar(int empresa, String evento) {
        var clientes = clientesPorEmpresa.get(empresa);
        if (clientes == null) return;
        var dados = Map.of("atualizadoEm", Instant.now().toString());
        for (var emitter : clientes) {
            try { emitter.send(SseEmitter.event().name(evento).data(dados)); }
            catch (IOException | IllegalStateException erro) {
                clientes.remove(emitter);
                emitter.completeWithError(erro);
            }
        }
    }
}
