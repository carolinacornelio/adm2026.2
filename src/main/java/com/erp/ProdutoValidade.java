package com.erp;

import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;

/** Resumo dos lotes por produto, sem confundir saldo físico com saldo liberado para venda. */
public final class ProdutoValidade {
    private ProdutoValidade() {}
    public static List<Map<String,Object>> aplicar(JdbcTemplate db, int empresa, List<Map<String,Object>> produtos) {
        EstoqueStatus.aplicar(produtos);
        if (produtos.isEmpty()) return produtos;
        var hoje = InventarioService.hoje();
        var linhas = db.queryForList("""
            SELECT produto_id, COALESCE(SUM(quantidade),0) AS rastreado,
                   COALESCE(SUM(quantidade) FILTER (WHERE validade<?),0) AS vencidas,
                   COALESCE(SUM(quantidade) FILTER (WHERE validade IS NULL),0) AS sem_data,
                   MIN(validade) FILTER (WHERE validade>=?) AS proxima
              FROM estoque_lotes WHERE empresa_id=? AND quantidade>0 GROUP BY produto_id
            """, hoje, hoje, empresa);
        Map<Long,Map<String,Object>> porProduto = new HashMap<>();
        for(var linha:linhas) porProduto.put(((Number)linha.get("produto_id")).longValue(),linha);
        for(var p:produtos) {
            var linha=porProduto.getOrDefault(((Number)p.get("id")).longValue(),Map.of());
            long total=numero(p,"quantidadeTotal"), vencidas=numero(linha,"vencidas");
            long pendentes=Boolean.TRUE.equals(p.get("controlaValidade"))
                    ? numero(linha,"sem_data")+Math.max(0,total-numero(linha,"rastreado")) : 0;
            var resumo=new LinkedHashMap<String,Object>();
            resumo.put("validade",linha.get("proxima"));
            resumo.put("controlaValidade",p.get("controlaValidade"));
            ValidadeStatus.aplicar(resumo,hoje,30);
            String situacao=total<=0 ? "Sem lotes com saldo" : vencidas>0 ? "Há unidades vencidas"
                    : pendentes>0 ? "Validade pendente" : linha.get("proxima")==null ? "Validade não exigida"
                    : String.valueOf(resumo.get("situacao"));
            p.put("situacaoValidade",situacao);
            p.put("nivelValidade",vencidas>0 || pendentes>0 ? "BLOQUEADO" : total<=0 ? "REGULAR" : resumo.get("nivelValidade"));
            p.put("unidadesVencidas",vencidas);
            p.put("unidadesValidadePendente",pendentes);
            p.put("proximaValidade",linha.get("proxima")==null ? null : linha.get("proxima").toString());
        }
        return produtos;
    }
    private static long numero(Map<String,Object> mapa,String chave) {
        return mapa.get(chave) instanceof Number n ? n.longValue() : 0;
    }
}
