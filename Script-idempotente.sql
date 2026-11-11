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

-- Integração ERP x fintech (Desafio 8). 
CREATE TABLE IF NOT EXISTS fintech_cobrancas (
    id                  SERIAL PRIMARY KEY,
    empresa_id          INTEGER      NOT NULL REFERENCES empresas(id) ON DELETE CASCADE,
    conta_receber_id    INTEGER      NOT NULL REFERENCES contas_receber(id) ON DELETE CASCADE,
    codigo_externo      VARCHAR(40)  NOT NULL UNIQUE,      -- código da cobrança na fintech
    valor               NUMERIC(10,2) NOT NULL,
    status              VARCHAR(20)  NOT NULL DEFAULT 'pendente',   -- pendente | paga | cancelada
    evento_pagamento_id VARCHAR(80),
    criado_em           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    pago_em             TIMESTAMPTZ
);

-- No máximo uma cobrança pendente por conta (evita cobrança duplicada)
CREATE UNIQUE INDEX IF NOT EXISTS ux_fintech_cobranca_aberta
    ON fintech_cobrancas (conta_receber_id) WHERE status = 'pendente';

CREATE TABLE IF NOT EXISTS fintech_auditoria (
    id               SERIAL PRIMARY KEY,
    empresa_id       INTEGER REFERENCES empresas(id) ON DELETE SET NULL,
    evento_id        VARCHAR(80),
    cobranca_codigo  VARCHAR(40),
    resultado        VARCHAR(20) NOT NULL,   -- criada | aceito | duplicado | rejeitado
    motivo           VARCHAR(255),
    recebido_em      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_fintech_auditoria_empresa ON fintech_auditoria (empresa_id, id DESC);