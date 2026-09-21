-- CONTAS E ACESSOS (MULTI-TENANT)

-- Nova tabela: Representa o negócio que contratou o seu sistema
CREATE TABLE empresas (
    id SERIAL PRIMARY KEY,
    nome_fantasia VARCHAR(150) NOT NULL,
    documento VARCHAR(20) UNIQUE, -- CNPJ da empresa que assina o ERP
    plano_assinatura VARCHAR(20) DEFAULT 'BASE', -- 'BASE', 'PLUS', 'PREMIUM'
    created_at TIMESTAMP DEFAULT NOW()
);

CREATE TABLE usuarios (
    id SERIAL PRIMARY KEY,
    empresa_id INTEGER REFERENCES empresas(id) ON DELETE CASCADE, -- Vincula o usuário à empresa
    nome VARCHAR(150) NOT NULL,
    email VARCHAR(150) UNIQUE NOT NULL,
    senha_hash VARCHAR(255) NOT NULL, 
    perfil VARCHAR(50) DEFAULT 'padrao', 
    created_at TIMESTAMP DEFAULT NOW()
);

-- CADASTROS (Agora todos vinculados à empresa_id)

CREATE TABLE categorias (
    id SERIAL PRIMARY KEY,
    empresa_id INTEGER REFERENCES empresas(id) ON DELETE CASCADE,
    nome VARCHAR(100) NOT NULL,
    created_at TIMESTAMP DEFAULT NOW()
);

CREATE TABLE produtos (
    id SERIAL PRIMARY KEY,
    empresa_id INTEGER REFERENCES empresas(id) ON DELETE CASCADE,
    nome VARCHAR(150) NOT NULL,
    codigo VARCHAR(50), 
    -- "preco" permanece por compatibilidade com a primeira versão do ERP.
    -- As operações atuais usam preco_compra e preco_venda.
    preco NUMERIC(10,2) NOT NULL,
    preco_compra NUMERIC(10,2) NOT NULL,
    preco_venda NUMERIC(10,2) NOT NULL,
    categoria_id INTEGER REFERENCES categorias(id) ON DELETE SET NULL,
    descricao TEXT,
    ativo BOOLEAN DEFAULT TRUE, -- Substitui o DELETE. Marca como FALSE para ocultar, mantendo o histórico de vendas.
    created_at TIMESTAMP DEFAULT NOW(),
    UNIQUE (empresa_id, codigo) -- O código do produto só precisa ser único dentro daquela empresa
);

CREATE TABLE clientes (
    id SERIAL PRIMARY KEY,
    empresa_id INTEGER REFERENCES empresas(id) ON DELETE CASCADE,
    nome VARCHAR(150) NOT NULL,
    documento VARCHAR(20), 
    telefone VARCHAR(20),
    email VARCHAR(150),
    endereco VARCHAR(255),
    created_at TIMESTAMP DEFAULT NOW(),
    UNIQUE (empresa_id, documento)
);

CREATE TABLE fornecedores (
    id SERIAL PRIMARY KEY,
    empresa_id INTEGER REFERENCES empresas(id) ON DELETE CASCADE,
    nome VARCHAR(150) NOT NULL,
    documento VARCHAR(20),
    telefone VARCHAR(20),
    email VARCHAR(150),
    created_at TIMESTAMP DEFAULT NOW(),
    UNIQUE (empresa_id, documento)
);

-- ESTOQUE

CREATE TABLE estoque (
    id SERIAL PRIMARY KEY,
    empresa_id INTEGER REFERENCES empresas(id) ON DELETE CASCADE,
    produto_id INTEGER REFERENCES produtos(id), -- Removido o ON DELETE CASCADE para não perder o registro
    quantidade INTEGER NOT NULL DEFAULT 0,
    estoque_minimo INTEGER DEFAULT 0, 
    local VARCHAR(100) DEFAULT 'padrao',
    updated_at TIMESTAMP DEFAULT NOW()
);

CREATE TABLE movimentacoes_estoque (
    id SERIAL PRIMARY KEY,
    empresa_id INTEGER REFERENCES empresas(id) ON DELETE CASCADE,
    produto_id INTEGER REFERENCES produtos(id),
    tipo VARCHAR(10) NOT NULL, 
    quantidade INTEGER NOT NULL,
    motivo VARCHAR(255),
    data TIMESTAMP DEFAULT NOW()
);

-- VENDAS E COMPRAS

CREATE TABLE vendas (
    id SERIAL PRIMARY KEY,
    empresa_id INTEGER REFERENCES empresas(id) ON DELETE CASCADE,
    cliente_id INTEGER REFERENCES clientes(id),
    usuario_id INTEGER REFERENCES usuarios(id),
    status VARCHAR(20) DEFAULT 'aberta', 
    total NUMERIC(10,2) DEFAULT 0,
    data TIMESTAMP DEFAULT NOW()
);

CREATE TABLE itens_venda (
    id SERIAL PRIMARY KEY,
    venda_id INTEGER REFERENCES vendas(id) ON DELETE CASCADE,
    produto_id INTEGER REFERENCES produtos(id),
    quantidade INTEGER NOT NULL,
    preco_unitario NUMERIC(10,2) NOT NULL
);

CREATE TABLE compras (
    id SERIAL PRIMARY KEY,
    empresa_id INTEGER REFERENCES empresas(id) ON DELETE CASCADE,
    fornecedor_id INTEGER REFERENCES fornecedores(id),
    usuario_id INTEGER REFERENCES usuarios(id),
    status VARCHAR(20) DEFAULT 'aberta',
    total NUMERIC(10,2) DEFAULT 0,
    data TIMESTAMP DEFAULT NOW()
);

CREATE TABLE itens_compra (
    id SERIAL PRIMARY KEY,
    compra_id INTEGER REFERENCES compras(id) ON DELETE CASCADE,
    produto_id INTEGER REFERENCES produtos(id),
    quantidade INTEGER NOT NULL,
    preco_unitario NUMERIC(10,2) NOT NULL
);

-- FINANCEIRO

CREATE TABLE contas_pagar (
    id SERIAL PRIMARY KEY,
    empresa_id INTEGER REFERENCES empresas(id) ON DELETE CASCADE,
    compra_id INTEGER REFERENCES compras(id),
    descricao VARCHAR(255),
    valor NUMERIC(10,2) NOT NULL,
    vencimento DATE NOT NULL,
    status VARCHAR(20) DEFAULT 'pendente', 
    data_pagamento DATE
);

CREATE TABLE contas_receber (
    id SERIAL PRIMARY KEY,
    empresa_id INTEGER REFERENCES empresas(id) ON DELETE CASCADE,
    venda_id INTEGER REFERENCES vendas(id),
    descricao VARCHAR(255),
    valor NUMERIC(10,2) NOT NULL,
    vencimento DATE NOT NULL,
    status VARCHAR(20) DEFAULT 'pendente',
    data_recebimento DATE
);

-- ÍNDICES (Otimizados para buscas multi-tenant)

CREATE INDEX idx_produtos_empresa_categoria ON produtos(empresa_id, categoria_id);
CREATE INDEX idx_vendas_empresa_cliente ON vendas(empresa_id, cliente_id);
CREATE INDEX idx_itens_venda_venda ON itens_venda(venda_id);
CREATE INDEX idx_itens_compra_compra ON itens_compra(compra_id);
