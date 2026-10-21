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
CREATE INDEX IF NOT EXISTS idx_vendas_empresa_data ON vendas(empresa_id, data DESC, id DESC);
CREATE INDEX IF NOT EXISTS idx_itens_venda_venda ON itens_venda(venda_id);
CREATE INDEX IF NOT EXISTS idx_itens_compra_compra ON itens_compra(compra_id);
CREATE INDEX IF NOT EXISTS idx_estoque_empresa_produto ON estoque(empresa_id, produto_id);

-- POS: adições compatíveis com vendas existentes; sem recriar ou remover dados.
ALTER TABLE vendas ADD COLUMN IF NOT EXISTS origem VARCHAR(20) DEFAULT 'ERP';
ALTER TABLE vendas ADD COLUMN IF NOT EXISTS pos_subtotal NUMERIC(10,2);
ALTER TABLE vendas ADD COLUMN IF NOT EXISTS pos_desconto NUMERIC(10,2);
ALTER TABLE vendas ADD COLUMN IF NOT EXISTS pos_forma_pagamento VARCHAR(20);
ALTER TABLE vendas ADD COLUMN IF NOT EXISTS pos_valor_recebido NUMERIC(10,2);
ALTER TABLE vendas ADD COLUMN IF NOT EXISTS pos_troco NUMERIC(10,2);
ALTER TABLE vendas ADD COLUMN IF NOT EXISTS pos_chave_operacao UUID;
ALTER TABLE vendas ADD COLUMN IF NOT EXISTS pos_requisicao_hash VARCHAR(64);
ALTER TABLE itens_venda ADD COLUMN IF NOT EXISTS pos_nome_produto VARCHAR(150);
ALTER TABLE itens_venda ADD COLUMN IF NOT EXISTS pos_codigo_produto VARCHAR(50);
CREATE UNIQUE INDEX IF NOT EXISTS idx_vendas_pos_operacao ON vendas(empresa_id,pos_chave_operacao) WHERE pos_chave_operacao IS NOT NULL;

-- Inventário: preserva o saldo anterior como lote legado, sem inventar validade.
ALTER TABLE produtos ADD COLUMN IF NOT EXISTS controla_validade BOOLEAN NOT NULL DEFAULT false;
CREATE TABLE IF NOT EXISTS estoque_lotes (
    id SERIAL PRIMARY KEY,
    empresa_id INTEGER NOT NULL REFERENCES empresas(id) ON DELETE CASCADE,
    produto_id INTEGER NOT NULL REFERENCES produtos(id),
    estoque_id INTEGER NOT NULL REFERENCES estoque(id) ON DELETE CASCADE,
    compra_id INTEGER REFERENCES compras(id),
    codigo VARCHAR(100) NOT NULL,
    validade DATE,
    quantidade INTEGER NOT NULL CHECK (quantidade >= 0),
    legado BOOLEAN NOT NULL DEFAULT false,
    criado_em TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_lote_legado ON estoque_lotes(estoque_id) WHERE legado=true;
CREATE INDEX IF NOT EXISTS idx_lotes_empresa_produto_validade ON estoque_lotes(empresa_id,produto_id,validade,id);
CREATE TABLE IF NOT EXISTS venda_lotes (
    id SERIAL PRIMARY KEY,
    venda_id INTEGER NOT NULL REFERENCES vendas(id) ON DELETE CASCADE,
    lote_id INTEGER NOT NULL REFERENCES estoque_lotes(id),
    quantidade INTEGER NOT NULL CHECK (quantidade > 0)
);
INSERT INTO estoque_lotes(empresa_id,produto_id,estoque_id,codigo,quantidade,legado)
SELECT e.empresa_id,e.produto_id,e.id,'LEGADO-'||e.id,e.quantidade,true FROM estoque e
WHERE e.quantidade>0 AND NOT EXISTS (SELECT 1 FROM estoque_lotes l WHERE l.estoque_id=e.id);

-- Histórico persistente de alertas operacionais; leitura individual por usuário.
CREATE TABLE IF NOT EXISTS alertas_operacionais (
 id BIGSERIAL PRIMARY KEY, empresa_id INTEGER NOT NULL REFERENCES empresas(id),
 chave VARCHAR(180) NOT NULL, tipo VARCHAR(30) NOT NULL, nivel VARCHAR(20) NOT NULL,
 mensagem TEXT NOT NULL, ativo BOOLEAN NOT NULL DEFAULT true,
 criado_em TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 atualizado_em TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS alertas_operacionais_ativos ON alertas_operacionais(empresa_id,chave) WHERE ativo=true;
CREATE INDEX IF NOT EXISTS alertas_operacionais_historico ON alertas_operacionais(empresa_id,id DESC);
CREATE TABLE IF NOT EXISTS alertas_operacionais_leituras (
 alerta_id BIGINT NOT NULL REFERENCES alertas_operacionais(id) ON DELETE CASCADE,
 usuario_id INTEGER NOT NULL REFERENCES usuarios(id),
 lido_em TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(alerta_id,usuario_id)
);
