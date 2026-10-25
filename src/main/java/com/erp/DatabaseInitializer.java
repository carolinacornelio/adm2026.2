package com.erp;

import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Aplica automaticamente as migrações idempotentes no PostgreSQL ao inicializar a aplicação.
 * Isso garante que todas as colunas da Lei Anticorrupção (dados bancários, alçadas, compliance)
 * existam no banco de dados sem exigir que o desenvolvedor execute scripts manuais.
 */
@Component
public class DatabaseInitializer implements CommandLineRunner {

    private final JdbcTemplate db;

    public DatabaseInitializer(JdbcTemplate db) {
        this.db = db;
    }

    @Override
    public void run(String... args) {
        System.out.println(">>> [COMPLIANCE] Verificando e aplicando migrações de banco de dados...");

        executarSilencioso("ALTER TABLE fornecedores ADD COLUMN IF NOT EXISTS contrato_social_url VARCHAR(500)");
        executarSilencioso("ALTER TABLE fornecedores ADD COLUMN IF NOT EXISTS banco VARCHAR(50)");
        executarSilencioso("ALTER TABLE fornecedores ADD COLUMN IF NOT EXISTS agencia VARCHAR(20)");
        executarSilencioso("ALTER TABLE fornecedores ADD COLUMN IF NOT EXISTS conta VARCHAR(30)");
        executarSilencioso("ALTER TABLE fornecedores ADD COLUMN IF NOT EXISTS chave_pix VARCHAR(100)");
        executarSilencioso("ALTER TABLE fornecedores ADD COLUMN IF NOT EXISTS dados_bancarios_bloqueados BOOLEAN DEFAULT FALSE");
        executarSilencioso("ALTER TABLE fornecedores ADD COLUMN IF NOT EXISTS status_conformidade VARCHAR(30) DEFAULT 'APROVADO'");

        executarSilencioso("""
            CREATE TABLE IF NOT EXISTS contratos_fornecedores (
                id SERIAL PRIMARY KEY,
                empresa_id INTEGER REFERENCES empresas(id) ON DELETE CASCADE,
                fornecedor_id INTEGER REFERENCES fornecedores(id) ON DELETE CASCADE,
                numero_contrato VARCHAR(50) NOT NULL,
                objeto VARCHAR(255),
                valor_total NUMERIC(12,2) NOT NULL,
                status VARCHAR(20) DEFAULT 'APROVADO',
                arquivo_url VARCHAR(500),
                data_inicio DATE,
                data_fim DATE,
                aprovador_id INTEGER REFERENCES usuarios(id),
                created_at TIMESTAMP DEFAULT NOW()
            )
        """);

        executarSilencioso("""
            CREATE TABLE IF NOT EXISTS fornecedores_alteracoes_pendentes (
                id SERIAL PRIMARY KEY,
                empresa_id INTEGER REFERENCES empresas(id) ON DELETE CASCADE,
                fornecedor_id INTEGER REFERENCES fornecedores(id) ON DELETE CASCADE,
                solicitante_id INTEGER REFERENCES usuarios(id),
                banco VARCHAR(50),
                agencia VARCHAR(20),
                conta VARCHAR(30),
                chave_pix VARCHAR(100),
                arquivo_comprobatorio_url VARCHAR(500),
                status VARCHAR(20) DEFAULT 'PENDENTE',
                aprovador_id INTEGER REFERENCES usuarios(id),
                data_solicitacao TIMESTAMP DEFAULT NOW(),
                data_decisao TIMESTAMP
            )
        """);

        executarSilencioso("ALTER TABLE compras ADD COLUMN IF NOT EXISTS aprovador_nivel_1 INTEGER REFERENCES usuarios(id)");
        executarSilencioso("ALTER TABLE compras ADD COLUMN IF NOT EXISTS aprovador_nivel_2 INTEGER REFERENCES usuarios(id)");
        executarSilencioso("ALTER TABLE compras ADD COLUMN IF NOT EXISTS data_aprovacao_n1 TIMESTAMP");
        executarSilencioso("ALTER TABLE compras ADD COLUMN IF NOT EXISTS data_aprovacao_n2 TIMESTAMP");
        executarSilencioso("ALTER TABLE compras ADD COLUMN IF NOT EXISTS contrato_id INTEGER REFERENCES contratos_fornecedores(id) ON DELETE SET NULL");
        executarSilencioso("ALTER TABLE compras ADD COLUMN IF NOT EXISTS nota_fiscal VARCHAR(100)");
        executarSilencioso("ALTER TABLE compras ADD COLUMN IF NOT EXISTS tipo_documento VARCHAR(20) DEFAULT 'MERCADORIA'");

        executarSilencioso("ALTER TABLE contas_pagar ADD COLUMN IF NOT EXISTS usuario_liquidacao_id INTEGER REFERENCES usuarios(id)");

        executarSilencioso("""
            CREATE TABLE IF NOT EXISTS compliance_alertas (
                id SERIAL PRIMARY KEY,
                empresa_id INTEGER REFERENCES empresas(id) ON DELETE CASCADE,
                usuario_id INTEGER REFERENCES usuarios(id),
                documento_referencia VARCHAR(100),
                valor_transacao NUMERIC(12,2) DEFAULT 0,
                motivo_alerta VARCHAR(255) NOT NULL,
                nivel_severidade VARCHAR(20) DEFAULT 'ALTA',
                data_hora TIMESTAMP DEFAULT NOW(),
                detalhes TEXT,
                status VARCHAR(20) DEFAULT 'PENDENTE'
            )
        """);

        executarSilencioso("ALTER TABLE compliance_alertas ADD COLUMN IF NOT EXISTS documento_referencia VARCHAR(100)");
        executarSilencioso("ALTER TABLE compliance_alertas ADD COLUMN IF NOT EXISTS valor_transacao NUMERIC(12,2) DEFAULT 0");
        executarSilencioso("ALTER TABLE compliance_alertas ADD COLUMN IF NOT EXISTS motivo_alerta VARCHAR(255)");
        executarSilencioso("ALTER TABLE compliance_alertas ADD COLUMN IF NOT EXISTS nivel_severidade VARCHAR(20) DEFAULT 'ALTA'");
        executarSilencioso("ALTER TABLE compliance_alertas ADD COLUMN IF NOT EXISTS data_hora TIMESTAMP DEFAULT NOW()");
        executarSilencioso("ALTER TABLE compliance_alertas ADD COLUMN IF NOT EXISTS detalhes TEXT");
        executarSilencioso("ALTER TABLE compliance_alertas ADD COLUMN IF NOT EXISTS status VARCHAR(20) DEFAULT 'PENDENTE'");
        executarSilencioso("ALTER TABLE compliance_alertas ADD COLUMN IF NOT EXISTS tipo_regra VARCHAR(100)");
        executarSilencioso("ALTER TABLE compliance_alertas ALTER COLUMN tipo_regra DROP NOT NULL");
        executarSilencioso("ALTER TABLE compliance_alertas ADD COLUMN IF NOT EXISTS motivo_risco VARCHAR(255)");
        executarSilencioso("ALTER TABLE compliance_alertas ALTER COLUMN motivo_risco DROP NOT NULL");

        executarSilencioso("""
            CREATE TABLE IF NOT EXISTS sod_conflitos (
                id SERIAL PRIMARY KEY,
                permissao_a VARCHAR(60) NOT NULL,
                permissao_b VARCHAR(60) NOT NULL,
                nivel_risco VARCHAR(20) DEFAULT 'CRITICO',
                descricao_risco TEXT NOT NULL
            )
        """);

        System.out.println(">>> [COMPLIANCE] Migrações verificadas com sucesso!");
    }

    private void executarSilencioso(String sql) {
        try {
            db.execute(sql);
        } catch (Exception e) {
            // Log apenas se necessário, prevenindo interrupção da inicialização
            System.err.println("Aviso migração: " + e.getMessage());
        }
    }
}
