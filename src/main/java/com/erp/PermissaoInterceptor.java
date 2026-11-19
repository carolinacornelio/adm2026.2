package com.erp;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.servlet.HandlerInterceptor;
import java.util.List;
import java.util.Set;

/**
 * Autorização centralizada de TODA rota /api/**. A tabela {@link #REGRAS} é a fonte de verdade:
 * a primeira regra que casar com método + caminho define a permissão exigida.
 *
 * <p>Padrão seguro: rota /api/** sem regra é NEGADA (403). Quem cria um módulo novo precisa
 * registrar sua regra aqui — ver docs/ARQUITETURA-PLATAFORMA.md.
 */
@Component
public class PermissaoInterceptor implements HandlerInterceptor {
    /** Marcadores especiais no lugar de um código de permissão. */
    static final String PUBLICA = "#publica", AUTENTICADA = "#autenticada";
    private static final Set<String> TODOS = Set.of("*");

    record Regra(Set<String> metodos, String padrao, String permissao) {
        boolean casa(String metodo, String caminho, AntPathMatcher m) {
            return (metodos.contains("*") || metodos.contains(metodo)) && m.match(padrao, caminho);
        }
    }
    private static Regra r(String metodos, String padrao, String permissao) {
        return new Regra("*".equals(metodos) ? TODOS : Set.of(metodos.split(",")), padrao, permissao);
    }

    static final List<Regra> REGRAS = List.of(
        r("GET", "/api/health", PUBLICA),
        r("*", "/api/auth/login", PUBLICA), r("*", "/api/auth/cadastro", PUBLICA),
        r("*", "/api/auth/segundo-fator", PUBLICA), r("*", "/api/auth/logout", PUBLICA),
        r("*", "/api/auth/**", AUTENTICADA),
        r("*", "/api/alertas/**", AUTENTICADA),
        r("GET", "/api/dashboard/estrategico", "dashboard.strategic"),
        r("GET", "/api/dashboard/**", "dashboard.view"),
        r("GET", "/api/categorias/**", "products.view"), r("POST", "/api/categorias", "products.create"),
        r("PUT,DELETE", "/api/categorias/**", "products.update"),
        r("POST", "/api/produtos/lote", "products.create"),
        r("GET", "/api/produtos/**", "products.view"), r("POST", "/api/produtos", "products.create"),
        r("PUT,DELETE", "/api/produtos/**", "products.update"),
        r("GET", "/api/pessoas/**", "people.view"), r("*", "/api/pessoas/**", "people.manage"),
        r("GET", "/api/vendas/**", "sales.view"), r("POST", "/api/vendas", "sales.create"),
        r("GET", "/api/compras/**", "purchases.view"), r("POST", "/api/compras", "purchases.create"),
        r("GET", "/api/estoque/**", "stock.view"),
        r("GET", "/api/inventario/**", "stock.view"), r("*", "/api/inventario/**", "stock.update"),
        r("GET", "/api/inventarios-fisicos/**", "stock.view"), r("*", "/api/inventarios-fisicos/**", "stock.update"),
        r("*", "/api/pos/**", "pos.use"),
        r("*", "/api/chamados/**", "support.use"),
        r("*", "/api/ia/**", "ai.use"),
        // --- plataforma ---
        r("GET", "/api/empresa", "company.view"), r("PUT", "/api/empresa", "company.update"),
        r("GET", "/api/funcionarios/**", "users.view"), r("*", "/api/funcionarios/**", "users.manage"),
        r("GET", "/api/perfis/**", "users.view"), r("*", "/api/perfis/**", "permissions.manage"),
        r("GET", "/api/permissoes", "users.view"),
        r("GET", "/api/squads/**", "squads.view"), r("*", "/api/squads/**", "squads.manage"),
        // As regras de negócio validam gestor ou responsável com permissão de atualização.
        // O interceptor exige sessão e delega a autorização granular ao OkrService.
        r("PUT,POST", "/api/okrs/resultados/*/progresso", AUTENTICADA),
        r("POST", "/api/okrs/resultados/*/submetas", AUTENTICADA),
        r("PUT", "/api/okrs/submetas/*/conclusao", AUTENTICADA),
        r("GET", "/api/okrs/**", "okrs.view"), r("*", "/api/okrs/**", "okrs.manage"),
        r("POST,PUT", "/api/financeiro/*/*/pagamento", "finance.update"),
        r("POST,PUT", "/api/financeiro/*/*/cancelamento", "finance.update"),
        r("GET", "/api/financeiro/**", "finance.view"),
        r("POST", "/api/financeiro/**", "finance.create"),
        r("PUT,DELETE", "/api/financeiro/**", "finance.update")
    );

    private final Autorizacao autorizacao;
    private final AntPathMatcher matcher = new AntPathMatcher();
    public PermissaoInterceptor(Autorizacao autorizacao) { this.autorizacao = autorizacao; }

    /** Permissão exigida para a rota; {@code null} quando não há regra (nega por padrão). */
    String exigida(String metodo, String caminho) {
        for (Regra regra : REGRAS) if (regra.casa(metodo, caminho, matcher)) return regra.permissao();
        return null;
    }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
        if ("OPTIONS".equals(req.getMethod())) return true; // pré-voo CORS
        String permissao = exigida(req.getMethod(), req.getRequestURI());
        if (PUBLICA.equals(permissao)) return true;
        if (permissao == null) throw new AcessoNegadoException("Rota sem regra de acesso registrada.");
        String cabecalho = req.getHeader("Authorization");
        if (AUTENTICADA.equals(permissao)) { autorizacao.contexto(cabecalho); return true; }
        autorizacao.exigir(cabecalho, permissao);
        return true;
    }
}
