-- CADASTROS 

CREATE TABLE categorias (
    id SERIAL PRIMARY KEY,
    nome VARCHAR(100) NOT NULL,
    created_at TIMESTAMP DEFAULT NOW()
);

CREATE TABLE produtos (
    id SERIAL PRIMARY KEY,
    nome VARCHAR(150) NOT NULL,
    codigo VARCHAR(50) UNIQUE,
    preco NUMERIC(10,2) NOT NULL,
    categoria_id INTEGER REFERENCES categorias(id) ON DELETE SET NULL,
    descricao TEXT,
    created_at TIMESTAMP DEFAULT NOW()
);

CREATE TABLE clientes (
    id SERIAL PRIMARY KEY,
    nome VARCHAR(150) NOT NULL,
    documento VARCHAR(20) UNIQUE, -- CPF ou CNPJ
    telefone VARCHAR(20),
    email VARCHAR(150),
    endereco VARCHAR(255),
    created_at TIMESTAMP DEFAULT NOW()
);

CREATE TABLE fornecedores (
    id SERIAL PRIMARY KEY,
    nome VARCHAR(150) NOT NULL,
    documento VARCHAR(20) UNIQUE,
    telefone VARCHAR(20),
    email VARCHAR(150),
    created_at TIMESTAMP DEFAULT NOW()
);

CREATE TABLE usuarios (
    id SERIAL PRIMARY KEY,
    nome VARCHAR(150) NOT NULL,
    email VARCHAR(150) UNIQUE NOT NULL,
    senha_hash VARCHAR(255) NOT NULL, -- nunca guardar senha em texto puro
    perfil VARCHAR(50) DEFAULT 'padrao', -- ex: admin, vendedor, estoquista
    created_at TIMESTAMP DEFAULT NOW()
);

-- ESTOQUE

CREATE TABLE estoque (
    id SERIAL PRIMARY KEY,
    produto_id INTEGER REFERENCES produtos(id) ON DELETE CASCADE,
    quantidade INTEGER NOT NULL DEFAULT 0,
    estoque_minimo INTEGER DEFAULT 0, -- usado pelo alerta de estoque baixo (Plus/Premium)
    local VARCHAR(100) DEFAULT 'padrao',
    updated_at TIMESTAMP DEFAULT NOW()
);

CREATE TABLE movimentacoes_estoque (
    id SERIAL PRIMARY KEY,
    produto_id INTEGER REFERENCES produtos(id),
    tipo VARCHAR(10) NOT NULL, -- 'entrada' ou 'saida'
    quantidade INTEGER NOT NULL,
    motivo VARCHAR(255),
    data TIMESTAMP DEFAULT NOW()
);

-- VENDAS

CREATE TABLE vendas (
    id SERIAL PRIMARY KEY,
    cliente_id INTEGER REFERENCES clientes(id),
    usuario_id INTEGER REFERENCES usuarios(id),
    status VARCHAR(20) DEFAULT 'aberta', -- aberta, concluida, cancelada
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

-- COMPRAS
CREATE TABLE compras (
    id SERIAL PRIMARY KEY,
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
    compra_id INTEGER REFERENCES compras(id),
    descricao VARCHAR(255),
    valor NUMERIC(10,2) NOT NULL,
    vencimento DATE NOT NULL,
    status VARCHAR(20) DEFAULT 'pendente', -- pendente, pago
    data_pagamento DATE
);

CREATE TABLE contas_receber (
    id SERIAL PRIMARY KEY,
    venda_id INTEGER REFERENCES vendas(id),
    descricao VARCHAR(255),
    valor NUMERIC(10,2) NOT NULL,
    vencimento DATE NOT NULL,
    status VARCHAR(20) DEFAULT 'pendente',
    data_recebimento DATE
);

-- ÍNDICES

CREATE INDEX idx_produtos_categoria ON produtos(categoria_id);
CREATE INDEX idx_vendas_cliente ON vendas(cliente_id);
CREATE INDEX idx_itens_venda_venda ON itens_venda(venda_id);
CREATE INDEX idx_itens_compra_compra ON itens_compra(compra_id);