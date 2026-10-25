-- Estrutura idempotente do ERP. Pode ser executada várias vezes sem apagar dados.
CREATE TABLE IF NOT EXISTS empresas (
    id SERIAL PRIMARY KEY,
    nome_fantasia VARCHAR(150) NOT NULL,
    documento VARCHAR(20) UNIQUE,
    plano_assinatura VARCHAR(20) DEFAULT 'BASE',
    created_at TIMESTAMP DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS usuarios (
    id SERIAL PRIMARY KEY,
    empresa_id INTEGER REFERENCES empresas(id) ON DELETE CASCADE,
    nome VARCHAR(150) NOT NULL,
    email VARCHAR(150) UNIQUE NOT NULL,
    senha_hash VARCHAR(255) NOT NULL,
    perfil VARCHAR(50) DEFAULT 'padrao',
    pergunta_seguranca_1 VARCHAR(50),
    resposta_seguranca_hash_1 VARCHAR(255),
    pergunta_seguranca_2 VARCHAR(50),
    resposta_seguranca_hash_2 VARCHAR(255),
    pergunta_seguranca_3 VARCHAR(50),
    resposta_seguranca_hash_3 VARCHAR(255),
    created_at TIMESTAMP DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS categorias (
    id SERIAL PRIMARY KEY,
    empresa_id INTEGER REFERENCES empresas(id) ON DELETE CASCADE,
    nome VARCHAR(100) NOT NULL,
    created_at TIMESTAMP DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS produtos (
    id SERIAL PRIMARY KEY,
    empresa_id INTEGER REFERENCES empresas(id) ON DELETE CASCADE,
    nome VARCHAR(150) NOT NULL,
    codigo VARCHAR(50),
    preco NUMERIC(10,2) NOT NULL DEFAULT 0,
    preco_compra NUMERIC(10,2) NOT NULL DEFAULT 0,
    preco_venda NUMERIC(10,2) NOT NULL DEFAULT 0,
    categoria_id INTEGER REFERENCES categorias(id) ON DELETE SET NULL,
    descricao TEXT,
    ativo BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT NOW(),
    UNIQUE (empresa_id, codigo)
);

CREATE TABLE IF NOT EXISTS clientes (
    id SERIAL PRIMARY KEY,
    empresa_id INTEGER REFERENCES empresas(id) ON DELETE CASCADE,
    nome VARCHAR(150) NOT NULL,
    documento VARCHAR(20), telefone VARCHAR(20), email VARCHAR(150), endereco VARCHAR(255),
    created_at TIMESTAMP DEFAULT NOW(),
    UNIQUE (empresa_id, documento)
);

CREATE TABLE IF NOT EXISTS fornecedores (
    id SERIAL PRIMARY KEY,
    empresa_id INTEGER REFERENCES empresas(id) ON DELETE CASCADE,
    nome VARCHAR(150) NOT NULL,
    documento VARCHAR(20), telefone VARCHAR(20), email VARCHAR(150),
    created_at TIMESTAMP DEFAULT NOW(),
    UNIQUE (empresa_id, documento)
);

CREATE TABLE IF NOT EXISTS estoque (
    id SERIAL PRIMARY KEY,
    empresa_id INTEGER REFERENCES empresas(id) ON DELETE CASCADE,
    produto_id INTEGER REFERENCES produtos(id),
    quantidade INTEGER NOT NULL DEFAULT 0,
    estoque_minimo INTEGER DEFAULT 0,
    local VARCHAR(100) DEFAULT 'padrao',
    updated_at TIMESTAMP DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS movimentacoes_estoque (
    id SERIAL PRIMARY KEY,
    empresa_id INTEGER REFERENCES empresas(id) ON DELETE CASCADE,
    produto_id INTEGER REFERENCES produtos(id),
    tipo VARCHAR(10) NOT NULL,
    quantidade INTEGER NOT NULL,
    motivo VARCHAR(255),
    data TIMESTAMP DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS vendas (
    id SERIAL PRIMARY KEY,
    empresa_id INTEGER REFERENCES empresas(id) ON DELETE CASCADE,
    cliente_id INTEGER REFERENCES clientes(id), usuario_id INTEGER REFERENCES usuarios(id),
    status VARCHAR(20) DEFAULT 'aberta', total NUMERIC(10,2) DEFAULT 0, data TIMESTAMP DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS itens_venda (
    id SERIAL PRIMARY KEY,
    venda_id INTEGER REFERENCES vendas(id) ON DELETE CASCADE,
    produto_id INTEGER REFERENCES produtos(id), quantidade INTEGER NOT NULL, preco_unitario NUMERIC(10,2) NOT NULL
);

CREATE TABLE IF NOT EXISTS compras (
    id SERIAL PRIMARY KEY,
    empresa_id INTEGER REFERENCES empresas(id) ON DELETE CASCADE,
    fornecedor_id INTEGER REFERENCES fornecedores(id), usuario_id INTEGER REFERENCES usuarios(id),
    status VARCHAR(20) DEFAULT 'aberta', total NUMERIC(10,2) DEFAULT 0, data TIMESTAMP DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS itens_compra (
    id SERIAL PRIMARY KEY,
    compra_id INTEGER REFERENCES compras(id) ON DELETE CASCADE,
    produto_id INTEGER REFERENCES produtos(id), quantidade INTEGER NOT NULL, preco_unitario NUMERIC(10,2) NOT NULL
);

CREATE TABLE IF NOT EXISTS contas_pagar (
    id SERIAL PRIMARY KEY,
    empresa_id INTEGER REFERENCES empresas(id) ON DELETE CASCADE,
    compra_id INTEGER REFERENCES compras(id), descricao VARCHAR(255), valor NUMERIC(10,2) NOT NULL,
    vencimento DATE NOT NULL, status VARCHAR(20) DEFAULT 'pendente', data_pagamento DATE
);

CREATE TABLE IF NOT EXISTS contas_receber (
    id SERIAL PRIMARY KEY,
    empresa_id INTEGER REFERENCES empresas(id) ON DELETE CASCADE,
    venda_id INTEGER REFERENCES vendas(id), descricao VARCHAR(255), valor NUMERIC(10,2) NOT NULL,
    vencimento DATE NOT NULL, status VARCHAR(20) DEFAULT 'pendente', data_recebimento DATE
);

-- Migração de bases criadas antes de planos, categorias e preços separados.
ALTER TABLE empresas ADD COLUMN IF NOT EXISTS plano_assinatura VARCHAR(20) DEFAULT 'BASE';
ALTER TABLE usuarios ADD COLUMN IF NOT EXISTS pergunta_seguranca_1 VARCHAR(50);
ALTER TABLE usuarios ADD COLUMN IF NOT EXISTS resposta_seguranca_hash_1 VARCHAR(255);
ALTER TABLE usuarios ADD COLUMN IF NOT EXISTS pergunta_seguranca_2 VARCHAR(50);
ALTER TABLE usuarios ADD COLUMN IF NOT EXISTS resposta_seguranca_hash_2 VARCHAR(255);
ALTER TABLE usuarios ADD COLUMN IF NOT EXISTS pergunta_seguranca_3 VARCHAR(50);
ALTER TABLE usuarios ADD COLUMN IF NOT EXISTS resposta_seguranca_hash_3 VARCHAR(255);
ALTER TABLE produtos ADD COLUMN IF NOT EXISTS categoria_id INTEGER REFERENCES categorias(id) ON DELETE SET NULL;
ALTER TABLE produtos ADD COLUMN IF NOT EXISTS preco_compra NUMERIC(10,2);
ALTER TABLE produtos ADD COLUMN IF NOT EXISTS preco_venda NUMERIC(10,2);
UPDATE produtos
   SET preco_compra = COALESCE(preco_compra, preco),
       preco_venda = COALESCE(preco_venda, preco)
 WHERE preco_compra IS NULL OR preco_venda IS NULL;

CREATE INDEX IF NOT EXISTS idx_produtos_empresa_categoria ON produtos(empresa_id, categoria_id);
CREATE INDEX IF NOT EXISTS idx_vendas_empresa_cliente ON vendas(empresa_id, cliente_id);
CREATE INDEX IF NOT EXISTS idx_itens_venda_venda ON itens_venda(venda_id);
CREATE INDEX IF NOT EXISTS idx_itens_compra_compra ON itens_compra(compra_id);
CREATE INDEX IF NOT EXISTS idx_estoque_empresa_produto ON estoque(empresa_id, produto_id);

-- ADEQUAÇÃO REGULAMENTAR: LEI ANTICORRUPÇÃO (LEI Nº 12.846/2013)


-- Dados Mestres de Fornecedores: Dados Bancários, Contrato Social e 4 Olhos
ALTER TABLE fornecedores ADD COLUMN IF NOT EXISTS contrato_social_url VARCHAR(500);
ALTER TABLE fornecedores ADD COLUMN IF NOT EXISTS banco VARCHAR(50);
ALTER TABLE fornecedores ADD COLUMN IF NOT EXISTS agencia VARCHAR(20);
ALTER TABLE fornecedores ADD COLUMN IF NOT EXISTS conta VARCHAR(30);
ALTER TABLE fornecedores ADD COLUMN IF NOT EXISTS chave_pix VARCHAR(100);
ALTER TABLE fornecedores ADD COLUMN IF NOT EXISTS dados_bancarios_bloqueados BOOLEAN DEFAULT FALSE;
ALTER TABLE fornecedores ADD COLUMN IF NOT EXISTS status_conformidade VARCHAR(30) DEFAULT 'APROVADO';

-- Contratos de Fornecedores (Obrigatório para serviços e compras > R$ 50k)
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
);

-- Dupla Validação / Princípio de 4 Olhos para Alteração de Dados Bancários Sensíveis
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
);

-- Alçadas e Workflow de Compras
ALTER TABLE compras ADD COLUMN IF NOT EXISTS aprovador_nivel_1 INTEGER REFERENCES usuarios(id);
ALTER TABLE compras ADD COLUMN IF NOT EXISTS aprovador_nivel_2 INTEGER REFERENCES usuarios(id);
ALTER TABLE compras ADD COLUMN IF NOT EXISTS data_aprovacao_n1 TIMESTAMP;
ALTER TABLE compras ADD COLUMN IF NOT EXISTS data_aprovacao_n2 TIMESTAMP;
ALTER TABLE compras ADD COLUMN IF NOT EXISTS contrato_id INTEGER REFERENCES contratos_fornecedores(id) ON DELETE SET NULL;
ALTER TABLE compras ADD COLUMN IF NOT EXISTS nota_fiscal VARCHAR(100);
ALTER TABLE compras ADD COLUMN IF NOT EXISTS tipo_documento VARCHAR(20) DEFAULT 'MERCADORIA';

-- Contas a pagar com identificação de quem realizou a liquidação (para SoD)
ALTER TABLE contas_pagar ADD COLUMN IF NOT EXISTS usuario_liquidacao_id INTEGER REFERENCES usuarios(id);

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
);

ALTER TABLE compliance_alertas ADD COLUMN IF NOT EXISTS documento_referencia VARCHAR(100);
ALTER TABLE compliance_alertas ADD COLUMN IF NOT EXISTS valor_transacao NUMERIC(12,2) DEFAULT 0;
ALTER TABLE compliance_alertas ADD COLUMN IF NOT EXISTS motivo_alerta VARCHAR(255);
ALTER TABLE compliance_alertas ADD COLUMN IF NOT EXISTS nivel_severidade VARCHAR(20) DEFAULT 'ALTA';
ALTER TABLE compliance_alertas ADD COLUMN IF NOT EXISTS data_hora TIMESTAMP DEFAULT NOW();
ALTER TABLE compliance_alertas ADD COLUMN IF NOT EXISTS detalhes TEXT;
ALTER TABLE compliance_alertas ADD COLUMN IF NOT EXISTS status VARCHAR(20) DEFAULT 'PENDENTE';
ALTER TABLE compliance_alertas ADD COLUMN IF NOT EXISTS tipo_regra VARCHAR(100);
ALTER TABLE compliance_alertas ALTER COLUMN tipo_regra DROP NOT NULL;
ALTER TABLE compliance_alertas ADD COLUMN IF NOT EXISTS motivo_risco VARCHAR(255);
ALTER TABLE compliance_alertas ALTER COLUMN motivo_risco DROP NOT NULL;

-- Matriz de Segregação de Funções (SoD) Proibidas
CREATE TABLE IF NOT EXISTS sod_conflitos (
    id SERIAL PRIMARY KEY,
    permissao_a VARCHAR(60) NOT NULL,
    permissao_b VARCHAR(60) NOT NULL,
    nivel_risco VARCHAR(20) DEFAULT 'CRITICO',
    descricao_risco TEXT NOT NULL
);

INSERT INTO sod_conflitos (permissao_a, permissao_b, nivel_risco, descricao_risco)
SELECT 'COMPRAS_APROVAR', 'FINANCEIRO_LIQUIDAR_PAGAMENTO', 'CRITICO', 
       'Regra de Ouro: Usuário que aprova compra não pode liquidar pagamento'
WHERE NOT EXISTS (
    SELECT 1 FROM sod_conflitos WHERE permissao_a = 'COMPRAS_APROVAR' AND permissao_b = 'FINANCEIRO_LIQUIDAR_PAGAMENTO'
);

