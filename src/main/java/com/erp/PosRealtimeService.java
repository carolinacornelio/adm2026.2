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
        clientesPorEmpresa.compute(empresa,(chave,clientes)->{
            if(clientes==null)clientes=ConcurrentHashMap.newKeySet();
            clientes.add(emitter);return clientes;
        });
        Runnable remover = () -> remover(empresa,emitter);
        emitter.onCompletion(remover);
        emitter.onTimeout(remover);
        emitter.onError(ignored -> remover.run());
        try { emitter.send(SseEmitter.event().name("conectado").data(Map.of("empresaId", empresa))); }
        catch (IOException | IllegalStateException erro) { encerrarComErro(empresa, emitter, erro); }
        return emitter;
    }

    private void remover(int empresa,SseEmitter emitter) {
        clientesPorEmpresa.computeIfPresent(empresa,(chave,clientes)->{
            clientes.remove(emitter);return clientes.isEmpty()?null:clientes;
        });
    }

    @org.springframework.scheduling.annotation.Scheduled(fixedDelay=30000)
    public void manterConexoes() {
        clientesPorEmpresa.forEach((empresa,clientes)->clientes.forEach(emitter->{
            try { emitter.send(SseEmitter.event().comment("manter-conexao")); }
            catch(IOException | IllegalStateException erro) { encerrarComErro(empresa,emitter,erro); }
        }));
    }

    public void publicarDepoisDoCommit(int empresa, String evento) {
        Runnable publicar = () -> publicar(empresa, evento);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { publicar.run(); }
            });
        } else publicar.run();
    }

    private void encerrarComErro(int empresa, SseEmitter emitter, Exception erro) {
        remover(empresa, emitter);
        try { emitter.completeWithError(erro); }
        catch (IllegalStateException ignored) {
            // O Tomcat pode já ter liberado o contexto depois de a aba desconectar.
        }
    }

    private void publicar(int empresa, String evento) {
        var clientes = clientesPorEmpresa.get(empresa);
        if (clientes == null) return;
        var dados = Map.of("atualizadoEm", Instant.now().toString());
        for (var emitter : clientes) {
            try { emitter.send(SseEmitter.event().name(evento).data(dados)); }
            catch (IOException | IllegalStateException erro) {
                encerrarComErro(empresa,emitter,erro);
            }
        }
    }
}
