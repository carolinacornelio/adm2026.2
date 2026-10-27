package com.erp;

import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * Migra timestamps legados depois que o schema SQL criou todas as tabelas.
 * Horários antigos do POS e de auditoria estão em UTC; datas digitadas no ERP
 * representam o dia comercial de Fortaleza. Colunas já migradas são preservadas.
 */
@Component
@DependsOnDatabaseInitialization
public class FusoHorarioMigration implements InitializingBean {
    private static final List<ColunaUtc> COLUNAS_UTC = List.of(
            new ColunaUtc("empresas", "created_at", true),
            new ColunaUtc("usuarios", "created_at", true),
            new ColunaUtc("categorias", "created_at", true),
            new ColunaUtc("produtos", "created_at", true),
            new ColunaUtc("clientes", "created_at", true),
            new ColunaUtc("fornecedores", "created_at", true),
            new ColunaUtc("estoque", "updated_at", true),
            new ColunaUtc("movimentacoes_estoque", "data", true),
            new ColunaUtc("estoque_lotes", "criado_em", true),
            new ColunaUtc("inventarios_fisicos", "criado_em", true),
            new ColunaUtc("inventarios_fisicos", "concluido_em", false),
            new ColunaUtc("inventario_fisico_itens", "estoque_atualizado_em", false),
            new ColunaUtc("inventario_fisico_itens", "contado_em", false),
            new ColunaUtc("inventario_fisico_ajuste_lotes", "criado_em", true),
            new ColunaUtc("contratos_fornecedores", "created_at", true),
            new ColunaUtc("fornecedores_alteracoes_pendentes", "data_solicitacao", true),
            new ColunaUtc("fornecedores_alteracoes_pendentes", "data_decisao", false),
            new ColunaUtc("compliance_alertas", "data_hora", true),
            new ColunaUtc("compliance_alertas", "resolvido_em", false),
            new ColunaUtc("compras", "data_aprovacao_n1", false),
            new ColunaUtc("compras", "data_aprovacao_n2", false)
    );

    private final JdbcTemplate db;
    private final TransactionTemplate transacao;

    public FusoHorarioMigration(JdbcTemplate db, PlatformTransactionManager transactionManager) {
        this.db = db;
        this.transacao = new TransactionTemplate(transactionManager);
    }

    @Override
    public void afterPropertiesSet() {
        transacao.executeWithoutResult(status -> {
            // Colegas podem iniciar o ERP simultaneamente contra a mesma base.
            db.queryForList("SELECT pg_advisory_xact_lock(20261024, 1)");
            COLUNAS_UTC.forEach(coluna -> converter(coluna.tabela(), coluna.nome(), coluna.temDefault(),
                    coluna.nome() + " AT TIME ZONE 'UTC'"));
            converter("vendas", "data", true,
                    "CASE WHEN COALESCE(origem,'ERP')='POS' "
                            + "THEN data AT TIME ZONE 'UTC' "
                            + "ELSE data AT TIME ZONE 'America/Fortaleza' END");
            converter("compras", "data", true, "data AT TIME ZONE 'America/Fortaleza'");
        });
    }

    private void converter(String tabela, String coluna, boolean temDefault, String expressao) {
        if (!semFusoHorario(tabela, coluna)) return;

        if (temDefault) db.execute("ALTER TABLE " + tabela + " ALTER COLUMN " + coluna + " DROP DEFAULT");
        db.execute("ALTER TABLE " + tabela + " ALTER COLUMN " + coluna
                + " TYPE TIMESTAMPTZ USING " + expressao);
        if (temDefault) db.execute("ALTER TABLE " + tabela + " ALTER COLUMN " + coluna + " SET DEFAULT CURRENT_TIMESTAMP");
    }

    private boolean semFusoHorario(String tabela, String coluna) {
        return Boolean.TRUE.equals(db.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM information_schema.columns
                     WHERE table_schema=current_schema() AND table_name=?
                       AND column_name=? AND data_type='timestamp without time zone'
                )
                """, Boolean.class, tabela, coluna));
    }

    private record ColunaUtc(String tabela, String nome, boolean temDefault) {}
}
