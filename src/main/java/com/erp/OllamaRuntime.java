package com.erp;

import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Só inicia uma instalação existente, local e no endereço padrão. Não baixa modelos. */
final class OllamaRuntime {
    private final URI base;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
    OllamaRuntime(String url) { this.base = URI.create(url.replaceAll("/+$", "")); }
    static final class Atividade implements AutoCloseable {
        private final Process processo;
        Atividade(Process processo) { this.processo=processo; }
        @Override public void close() {
            if (processo==null) return;
            try { processo.getOutputStream().close(); } catch (Exception ignored) { }
            // A solicitação de atividade também é liberada pelo Windows ao terminar o processo.
            processo.destroy();
        }
    }
    Atividade manterAtivo() {
        String host=base.getHost(), windows=System.getenv("SystemRoot");
        if(windows==null || base.getPort()!=11434 || !("localhost".equalsIgnoreCase(host)||"127.0.0.1".equals(host)))
            return new Atividade(null);
        try {
            String script="""
                    Add-Type -TypeDefinition 'using System.Runtime.InteropServices; public static class ErpIaPower { [DllImport("kernel32.dll")] public static extern uint SetThreadExecutionState(uint flags); }';
                    [ErpIaPower]::SetThreadExecutionState([uint32]2147483649) | Out-Null;
                    try { [Console]::In.ReadLine() | Out-Null } finally { [ErpIaPower]::SetThreadExecutionState([uint32]2147483648) | Out-Null }
                    """;
            var powershell=Path.of(windows,"System32","WindowsPowerShell","v1.0","powershell.exe");
            return new Atividade(new ProcessBuilder(powershell.toString(),"-NoProfile","-NonInteractive","-Command",script)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start());
        } catch (Exception e) { return new Atividade(null); }
    }
    private boolean ativo() {
        try { return http.send(HttpRequest.newBuilder(URI.create(base+"/")).timeout(Duration.ofSeconds(2)).GET().build(),
                HttpResponse.BodyHandlers.discarding()).statusCode()==200; }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
        catch (Exception e) { return false; }
    }
    synchronized void garantirDisponivel() throws Exception {
        if (ativo()) return;
        String host=base.getHost(), local=System.getenv("LOCALAPPDATA");
        boolean padrao=("localhost".equalsIgnoreCase(host)||"127.0.0.1".equals(host))
                && "http".equals(base.getScheme()) && base.getPort()==11434;
        if (padrao && local!=null && System.getProperty("os.name","").startsWith("Windows")) {
            Path exe=Path.of(local,"Programs","Ollama","ollama.exe");
            if(Files.isRegularFile(exe)) {
                // O serviço pode continuar ativo durante um mvn clean; não prenda arquivos em target/.
                Path log=Path.of(local,"ADM-ERP","logs","ollama-local.log");
                Files.createDirectories(log.getParent());
                ProcessBuilder processo = new ProcessBuilder(exe.toString(),"serve").redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()));
                // Limites somente para o processo iniciado pelo ERP, sem alterar o ambiente do Windows.
                processo.environment().putIfAbsent("OLLAMA_NUM_PARALLEL", "1");
                processo.environment().putIfAbsent("OLLAMA_MAX_LOADED_MODELS", "1");
                processo.environment().putIfAbsent("LLAMA_ARG_CACHE_RAM", "256");
                processo.start();
                for(int i=0;i<20;i++) { Thread.sleep(250); if(ativo()) return; }
            }
        }
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "O Ollama não está disponível no servidor do ERP. Inicie o Ollama e tente novamente.");
    }
}
