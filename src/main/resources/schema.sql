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

-- Inventário físico: sessão de contagem, fotografia do saldo e trilha de aprovação.
CREATE TABLE IF NOT EXISTS inventarios_fisicos (
    id SERIAL PRIMARY KEY,
    empresa_id INTEGER NOT NULL REFERENCES empresas(id) ON DELETE CASCADE,
    usuario_id INTEGER NOT NULL REFERENCES usuarios(id),
    aprovado_por INTEGER REFERENCES usuarios(id),
    status VARCHAR(20) NOT NULL DEFAULT 'EM_CONTAGEM' CHECK (status IN ('EM_CONTAGEM','CONCLUIDO')),
    observacao VARCHAR(500) NOT NULL DEFAULT '',
    criado_em TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    concluido_em TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_inventarios_fisicos_empresa_data ON inventarios_fisicos(empresa_id, criado_em DESC);
CREATE TABLE IF NOT EXISTS inventario_fisico_itens (
    id SERIAL PRIMARY KEY,
    inventario_id INTEGER NOT NULL REFERENCES inventarios_fisicos(id) ON DELETE CASCADE,
    empresa_id INTEGER NOT NULL REFERENCES empresas(id) ON DELETE CASCADE,
    produto_id INTEGER NOT NULL REFERENCES produtos(id),
    quantidade_sistema INTEGER NOT NULL CHECK (quantidade_sistema >= 0),
    quantidade_contada INTEGER CHECK (quantidade_contada >= 0),
    estoque_atualizado_em TIMESTAMP,
    preco_unitario NUMERIC(12,2) NOT NULL DEFAULT 0,
    observacao VARCHAR(500) NOT NULL DEFAULT '',
    contado_em TIMESTAMP,
    contado_por INTEGER REFERENCES usuarios(id),
    UNIQUE(inventario_id, produto_id)
);
CREATE INDEX IF NOT EXISTS idx_inventario_fisico_itens_empresa ON inventario_fisico_itens(empresa_id, inventario_id);

CREATE TABLE IF NOT EXISTS inventario_fisico_ajuste_lotes (
    id SERIAL PRIMARY KEY,
    inventario_id INTEGER NOT NULL REFERENCES inventarios_fisicos(id) ON DELETE CASCADE,
    item_id INTEGER NOT NULL REFERENCES inventario_fisico_itens(id) ON DELETE CASCADE,
    empresa_id INTEGER NOT NULL REFERENCES empresas(id) ON DELETE CASCADE,
    lote_id INTEGER NOT NULL REFERENCES estoque_lotes(id),
    quantidade INTEGER NOT NULL CHECK (quantidade > 0),
    tipo VARCHAR(10) NOT NULL CHECK (tipo IN ('SOBRA','FALTA')),
    criado_em TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_inventario_fisico_ajustes_lotes ON inventario_fisico_ajuste_lotes(empresa_id,inventario_id);

-- Atendimento: complemento independente, sem substituir tabelas do ERP atual.
CREATE TABLE IF NOT EXISTS chamados (
    id SERIAL PRIMARY KEY,
    empresa_id INTEGER NOT NULL REFERENCES empresas(id) ON DELETE CASCADE,
    cliente VARCHAR(120) NOT NULL,
    titulo VARCHAR(160) NOT NULL,
    descricao TEXT NOT NULL,
    severidade VARCHAR(10) NOT NULL,
    situacao VARCHAR(20) NOT NULL DEFAULT 'Aberto',
    criado_em TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_chamados_empresa ON chamados(empresa_id);

-- =====================================================================
-- PLATAFORMA MULTIEMPRESA: proprietário, funcionários, perfis e permissões.
-- Idempotente e aditiva. Chaves compostas (id, empresa_id) fazem o próprio
-- banco recusar vínculos entre empresas diferentes.
-- =====================================================================
ALTER TABLE empresas ADD COLUMN IF NOT EXISTS status VARCHAR(20) NOT NULL DEFAULT 'ATIVA';
ALTER TABLE empresas ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP;
ALTER TABLE usuarios ADD COLUMN IF NOT EXISTS ativo BOOLEAN NOT NULL DEFAULT true;
ALTER TABLE usuarios ADD COLUMN IF NOT EXISTS proprietario BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE usuarios ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP;

-- Bases antigas: o usuário mais antigo de cada empresa é o proprietário.
UPDATE usuarios u SET proprietario = true
 WHERE u.empresa_id IS NOT NULL
   AND u.id = (SELECT MIN(x.id) FROM usuarios x WHERE x.empresa_id = u.empresa_id)
   AND NOT EXISTS (SELECT 1 FROM usuarios y WHERE y.empresa_id = u.empresa_id AND y.proprietario);

CREATE UNIQUE INDEX IF NOT EXISTS ux_usuarios_id_empresa ON usuarios(id, empresa_id);
CREATE UNIQUE INDEX IF NOT EXISTS ux_usuarios_proprietario_empresa ON usuarios(empresa_id) WHERE proprietario;
CREATE INDEX IF NOT EXISTS idx_usuarios_empresa_ativo ON usuarios(empresa_id, ativo);

CREATE TABLE IF NOT EXISTS permissoes (
    id SERIAL PRIMARY KEY,
    codigo VARCHAR(60) NOT NULL UNIQUE,
    modulo VARCHAR(40) NOT NULL,
    descricao VARCHAR(200) NOT NULL
);
INSERT INTO permissoes(codigo, modulo, descricao) VALUES
 ('dashboard.view','Dashboard','Visualizar o dashboard operacional'),
 ('dashboard.strategic','Dashboard','Visualizar indicadores estratégicos'),
 ('company.view','Empresa','Visualizar dados da empresa'),
 ('company.update','Empresa','Editar dados da empresa'),
 ('users.view','Administração','Consultar funcionários'),
 ('users.manage','Administração','Criar, editar, ativar e desativar funcionários'),
 ('permissions.manage','Administração','Criar e editar perfis de acesso'),
 ('products.view','Produtos','Consultar produtos e categorias'),
 ('products.create','Produtos','Cadastrar produtos e categorias'),
 ('products.update','Produtos','Editar e inativar produtos e categorias'),
 ('people.view','Pessoas','Consultar clientes e fornecedores'),
 ('people.manage','Pessoas','Cadastrar, editar e remover clientes e fornecedores'),
 ('sales.view','Vendas','Consultar vendas'),
 ('sales.create','Vendas','Registrar vendas'),
 ('purchases.view','Compras','Consultar compras'),
 ('purchases.create','Compras','Registrar compras'),
 ('pos.use','PDV','Operar o ponto de venda'),
 ('stock.view','Estoque','Consultar estoque, lotes e inventários'),
 ('stock.update','Estoque','Alterar estoque, validade e inventários'),
 ('finance.view','Financeiro','Consultar contas a pagar e a receber'),
 ('finance.create','Financeiro','Criar lançamentos financeiros'),
 ('finance.update','Financeiro','Editar, pagar, receber e cancelar lançamentos'),
 ('squads.view','Squads','Consultar Squads'),
 ('squads.manage','Squads','Criar e administrar Squads'),
 ('okrs.view','OKRs','Consultar objetivos e resultados-chave'),
 ('okrs.manage','OKRs','Criar, editar e arquivar objetivos e resultados-chave'),
 ('okrs.update','OKRs','Atualizar o progresso de resultados-chave'),
 ('support.use','Atendimento','Usar a central de atendimento'),
 ('ai.use','Assistente','Usar o assistente de IA')
ON CONFLICT (codigo) DO UPDATE SET modulo = EXCLUDED.modulo, descricao = EXCLUDED.descricao;

CREATE TABLE IF NOT EXISTS perfis_acesso (
    id SERIAL PRIMARY KEY,
    empresa_id INTEGER NOT NULL REFERENCES empresas(id) ON DELETE CASCADE,
    nome VARCHAR(60) NOT NULL,
    descricao VARCHAR(200) NOT NULL DEFAULT '',
    sistema BOOLEAN NOT NULL DEFAULT false,
    chave_sistema VARCHAR(30),
    ativo BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_perfis_id_empresa ON perfis_acesso(id, empresa_id);
CREATE UNIQUE INDEX IF NOT EXISTS ux_perfis_empresa_nome ON perfis_acesso(empresa_id, LOWER(nome));
CREATE UNIQUE INDEX IF NOT EXISTS ux_perfis_empresa_chave ON perfis_acesso(empresa_id, chave_sistema) WHERE chave_sistema IS NOT NULL;

CREATE TABLE IF NOT EXISTS perfil_permissoes (
    perfil_id INTEGER NOT NULL REFERENCES perfis_acesso(id) ON DELETE CASCADE,
    permissao_id INTEGER NOT NULL REFERENCES permissoes(id) ON DELETE CASCADE,
    PRIMARY KEY (perfil_id, permissao_id)
);

CREATE TABLE IF NOT EXISTS usuario_perfis (
    usuario_id INTEGER NOT NULL,
    perfil_id INTEGER NOT NULL,
    empresa_id INTEGER NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    PRIMARY KEY (usuario_id, perfil_id),
    FOREIGN KEY (usuario_id, empresa_id) REFERENCES usuarios(id, empresa_id) ON DELETE CASCADE,
    FOREIGN KEY (perfil_id, empresa_id) REFERENCES perfis_acesso(id, empresa_id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_usuario_perfis_perfil ON usuario_perfis(perfil_id);

-- Modelo dos perfis iniciais, copiado para cada empresa nova (ver Autorizacao.criarPerfisIniciais).
CREATE TABLE IF NOT EXISTS perfis_modelo_permissoes (
    chave VARCHAR(30) NOT NULL,
    permissao_codigo VARCHAR(60) NOT NULL REFERENCES permissoes(codigo) ON DELETE CASCADE,
    PRIMARY KEY (chave, permissao_codigo)
);
INSERT INTO perfis_modelo_permissoes(chave, permissao_codigo) SELECT 'PROPRIETARIO', codigo FROM permissoes ON CONFLICT DO NOTHING;
INSERT INTO perfis_modelo_permissoes(chave, permissao_codigo)
SELECT 'GERENTE', codigo FROM permissoes WHERE codigo NOT IN ('users.manage','permissions.manage','company.update') ON CONFLICT DO NOTHING;
INSERT INTO perfis_modelo_permissoes(chave, permissao_codigo) VALUES
 ('VENDEDOR','dashboard.view'),('VENDEDOR','products.view'),('VENDEDOR','people.view'),('VENDEDOR','people.manage'),
 ('VENDEDOR','sales.view'),('VENDEDOR','sales.create'),('VENDEDOR','pos.use'),('VENDEDOR','stock.view'),
 ('VENDEDOR','squads.view'),('VENDEDOR','okrs.view'),('VENDEDOR','support.use'),
 ('ESTOQUISTA','dashboard.view'),('ESTOQUISTA','products.view'),('ESTOQUISTA','products.create'),('ESTOQUISTA','products.update'),
 ('ESTOQUISTA','stock.view'),('ESTOQUISTA','stock.update'),('ESTOQUISTA','purchases.view'),('ESTOQUISTA','purchases.create'),
 ('ESTOQUISTA','squads.view'),('ESTOQUISTA','okrs.view'),('ESTOQUISTA','support.use'),
 ('FINANCEIRO','dashboard.view'),('FINANCEIRO','finance.view'),('FINANCEIRO','finance.create'),('FINANCEIRO','finance.update'),
 ('FINANCEIRO','sales.view'),('FINANCEIRO','purchases.view'),('FINANCEIRO','people.view'),
 ('FINANCEIRO','squads.view'),('FINANCEIRO','okrs.view'),('FINANCEIRO','support.use')
ON CONFLICT DO NOTHING;

-- Perfis iniciais para empresas existentes; permissões copiadas só na criação,
-- para não desfazer edições futuras do proprietário.
WITH novos AS (
    INSERT INTO perfis_acesso(empresa_id, nome, descricao, sistema, chave_sistema)
    SELECT e.id, p.nome, p.descricao, true, p.chave
      FROM empresas e
     CROSS JOIN (VALUES
        ('PROPRIETARIO','Proprietário','Acesso administrativo completo.'),
        ('GERENTE','Gerente','Opera todos os módulos, exceto administração de usuários e perfis.'),
        ('VENDEDOR','Vendedor','Vendas, PDV e consulta de produtos e clientes.'),
        ('ESTOQUISTA','Estoquista','Produtos, compras e estoque.'),
        ('FINANCEIRO','Financeiro','Contas a pagar e a receber.')
     ) AS p(chave, nome, descricao)
     WHERE NOT EXISTS (SELECT 1 FROM perfis_acesso x WHERE x.empresa_id = e.id AND (x.chave_sistema = p.chave OR LOWER(x.nome) = LOWER(p.nome)))
    RETURNING id, chave_sistema
)
INSERT INTO perfil_permissoes(perfil_id, permissao_id)
SELECT n.id, pe.id FROM novos n
  JOIN perfis_modelo_permissoes m ON m.chave = n.chave_sistema
  JOIN permissoes pe ON pe.codigo = m.permissao_codigo
ON CONFLICT DO NOTHING;

-- O perfil Proprietário é imutável: recebe automaticamente toda permissão nova do catálogo.
INSERT INTO perfil_permissoes(perfil_id, permissao_id)
SELECT pa.id, pe.id FROM perfis_acesso pa CROSS JOIN permissoes pe WHERE pa.chave_sistema = 'PROPRIETARIO'
ON CONFLICT DO NOTHING;

-- Usuários existentes sem perfil: proprietário recebe Proprietário; demais mantêm acesso operacional (Gerente).
INSERT INTO usuario_perfis(usuario_id, perfil_id, empresa_id)
SELECT u.id, p.id, u.empresa_id FROM usuarios u
  JOIN perfis_acesso p ON p.empresa_id = u.empresa_id
   AND p.chave_sistema = CASE WHEN u.proprietario THEN 'PROPRIETARIO' ELSE 'GERENTE' END
 WHERE u.empresa_id IS NOT NULL
   AND NOT EXISTS (SELECT 1 FROM usuario_perfis up WHERE up.usuario_id = u.id)
ON CONFLICT DO NOTHING;

-- =====================================================================
-- SQUADS
-- =====================================================================
CREATE TABLE IF NOT EXISTS squads (
    id SERIAL PRIMARY KEY,
    empresa_id INTEGER NOT NULL REFERENCES empresas(id) ON DELETE CASCADE,
    nome VARCHAR(100) NOT NULL,
    descricao VARCHAR(500) NOT NULL DEFAULT '',
    lider_id INTEGER,
    status VARCHAR(20) NOT NULL DEFAULT 'ATIVA' CHECK (status IN ('ATIVA','INATIVA')),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP,
    FOREIGN KEY (lider_id, empresa_id) REFERENCES usuarios(id, empresa_id)
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_squads_id_empresa ON squads(id, empresa_id);
CREATE UNIQUE INDEX IF NOT EXISTS ux_squads_empresa_nome ON squads(empresa_id, LOWER(nome));

CREATE TABLE IF NOT EXISTS squad_membros (
    squad_id INTEGER NOT NULL,
    usuario_id INTEGER NOT NULL,
    empresa_id INTEGER NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    PRIMARY KEY (squad_id, usuario_id),
    FOREIGN KEY (squad_id, empresa_id) REFERENCES squads(id, empresa_id) ON DELETE CASCADE,
    FOREIGN KEY (usuario_id, empresa_id) REFERENCES usuarios(id, empresa_id)
);
CREATE INDEX IF NOT EXISTS idx_squad_membros_usuario ON squad_membros(empresa_id, usuario_id);

-- =====================================================================
-- OKRs. Objetivos são arquivados (nunca apagados) para preservar o histórico.
-- =====================================================================
CREATE TABLE IF NOT EXISTS objetivos (
    id SERIAL PRIMARY KEY,
    empresa_id INTEGER NOT NULL REFERENCES empresas(id) ON DELETE CASCADE,
    titulo VARCHAR(150) NOT NULL,
    descricao VARCHAR(1000) NOT NULL DEFAULT '',
    squad_id INTEGER,
    responsavel_id INTEGER,
    periodo_inicio DATE NOT NULL,
    periodo_fim DATE NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ATIVO' CHECK (status IN ('ATIVO','CONCLUIDO','ARQUIVADO')),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP,
    CHECK (periodo_fim >= periodo_inicio),
    FOREIGN KEY (squad_id, empresa_id) REFERENCES squads(id, empresa_id),
    FOREIGN KEY (responsavel_id, empresa_id) REFERENCES usuarios(id, empresa_id)
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_objetivos_id_empresa ON objetivos(id, empresa_id);
CREATE INDEX IF NOT EXISTS idx_objetivos_empresa_status ON objetivos(empresa_id, status, periodo_fim);
CREATE INDEX IF NOT EXISTS idx_objetivos_squad ON objetivos(empresa_id, squad_id);

CREATE TABLE IF NOT EXISTS resultados_chave (
    id SERIAL PRIMARY KEY,
    empresa_id INTEGER NOT NULL REFERENCES empresas(id) ON DELETE CASCADE,
    objetivo_id INTEGER NOT NULL,
    titulo VARCHAR(150) NOT NULL,
    descricao VARCHAR(1000) NOT NULL DEFAULT '',
    valor_inicial NUMERIC(18,4) NOT NULL DEFAULT 0,
    meta NUMERIC(18,4) NOT NULL,
    valor_atual NUMERIC(18,4) NOT NULL DEFAULT 0,
    unidade VARCHAR(30) NOT NULL DEFAULT '',
    responsavel_id INTEGER,
    prazo DATE NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'EM_ANDAMENTO' CHECK (status IN ('EM_ANDAMENTO','CONCLUIDO','CANCELADO')),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP,
    CHECK (meta <> valor_inicial),
    FOREIGN KEY (objetivo_id, empresa_id) REFERENCES objetivos(id, empresa_id),
    FOREIGN KEY (responsavel_id, empresa_id) REFERENCES usuarios(id, empresa_id)
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_resultados_id_empresa ON resultados_chave(id, empresa_id);
CREATE INDEX IF NOT EXISTS idx_resultados_objetivo ON resultados_chave(empresa_id, objetivo_id);

-- Modos adicionais de acompanhamento: métrica numérica (legado), checklist de sub-metas ou percentual manual.
ALTER TABLE resultados_chave ADD COLUMN IF NOT EXISTS modo_acompanhamento VARCHAR(20) NOT NULL DEFAULT 'METRICA';
ALTER TABLE resultados_chave ADD COLUMN IF NOT EXISTS progresso_manual NUMERIC(5,2) NOT NULL DEFAULT 0;

CREATE TABLE IF NOT EXISTS submetas_resultados_chave (
    id SERIAL PRIMARY KEY,
    empresa_id INTEGER NOT NULL REFERENCES empresas(id) ON DELETE CASCADE,
    resultado_id INTEGER NOT NULL,
    titulo VARCHAR(150) NOT NULL,
    descricao VARCHAR(500) NOT NULL DEFAULT '',
    concluida BOOLEAN NOT NULL DEFAULT FALSE,
    ordem INTEGER NOT NULL DEFAULT 0,
    concluida_em TIMESTAMPTZ,
    concluida_por INTEGER,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ,
    FOREIGN KEY (resultado_id, empresa_id) REFERENCES resultados_chave(id, empresa_id) ON DELETE CASCADE,
    FOREIGN KEY (concluida_por, empresa_id) REFERENCES usuarios(id, empresa_id)
);
CREATE INDEX IF NOT EXISTS idx_submetas_resultado ON submetas_resultados_chave(empresa_id, resultado_id, ordem, id);

CREATE TABLE IF NOT EXISTS historico_resultados_chave (
    id BIGSERIAL PRIMARY KEY,
    empresa_id INTEGER NOT NULL REFERENCES empresas(id) ON DELETE CASCADE,
    resultado_id INTEGER NOT NULL,
    valor_anterior NUMERIC(18,4) NOT NULL,
    valor_novo NUMERIC(18,4) NOT NULL,
    comentario VARCHAR(500) NOT NULL DEFAULT '',
    usuario_id INTEGER,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    FOREIGN KEY (resultado_id, empresa_id) REFERENCES resultados_chave(id, empresa_id),
    FOREIGN KEY (usuario_id, empresa_id) REFERENCES usuarios(id, empresa_id)
);
CREATE INDEX IF NOT EXISTS idx_historico_resultado ON historico_resultados_chave(empresa_id, resultado_id, id DESC);

-- =====================================================================
-- FINANCEIRO: amplia as tabelas existentes sem alterar dados históricos.
-- Vocabulário de status preservado: pendente | pago/recebido | cancelado.
-- =====================================================================
CREATE TABLE IF NOT EXISTS categorias_financeiras (
    id SERIAL PRIMARY KEY,
    empresa_id INTEGER NOT NULL REFERENCES empresas(id) ON DELETE CASCADE,
    nome VARCHAR(80) NOT NULL,
    tipo VARCHAR(10) NOT NULL CHECK (tipo IN ('PAGAR','RECEBER')),
    ativo BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_categorias_fin_nome ON categorias_financeiras(empresa_id, tipo, LOWER(nome));

ALTER TABLE contas_pagar ADD COLUMN IF NOT EXISTS categoria_id INTEGER REFERENCES categorias_financeiras(id);
ALTER TABLE contas_pagar ADD COLUMN IF NOT EXISTS origem VARCHAR(20) NOT NULL DEFAULT 'MANUAL';
ALTER TABLE contas_pagar ADD COLUMN IF NOT EXISTS observacao VARCHAR(500);
ALTER TABLE contas_pagar ADD COLUMN IF NOT EXISTS created_at TIMESTAMP NOT NULL DEFAULT NOW();
ALTER TABLE contas_pagar ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP;
ALTER TABLE contas_pagar ADD COLUMN IF NOT EXISTS cancelado_em TIMESTAMP;
ALTER TABLE contas_pagar ADD COLUMN IF NOT EXISTS valor_pago NUMERIC(12,2);
ALTER TABLE contas_receber ADD COLUMN IF NOT EXISTS categoria_id INTEGER REFERENCES categorias_financeiras(id);
ALTER TABLE contas_receber ADD COLUMN IF NOT EXISTS origem VARCHAR(20) NOT NULL DEFAULT 'MANUAL';
ALTER TABLE contas_receber ADD COLUMN IF NOT EXISTS observacao VARCHAR(500);
ALTER TABLE contas_receber ADD COLUMN IF NOT EXISTS created_at TIMESTAMP NOT NULL DEFAULT NOW();
ALTER TABLE contas_receber ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP;
ALTER TABLE contas_receber ADD COLUMN IF NOT EXISTS cancelado_em TIMESTAMP;
ALTER TABLE contas_receber ADD COLUMN IF NOT EXISTS valor_recebido NUMERIC(12,2);

-- Lançamentos que já nasceram de compras e vendas passam a ser identificados pela origem.
UPDATE contas_pagar SET origem = 'COMPRA' WHERE compra_id IS NOT NULL AND origem = 'MANUAL';
UPDATE contas_receber SET origem = 'VENDA' WHERE venda_id IS NOT NULL AND origem = 'MANUAL';
UPDATE contas_receber c SET origem = 'POS' FROM vendas v WHERE c.venda_id = v.id AND v.origem = 'POS' AND c.origem = 'VENDA';

-- Uma compra gera no máximo um lançamento; uma venda também (impede duplicidade ao reprocessar).
CREATE UNIQUE INDEX IF NOT EXISTS ux_contas_pagar_compra ON contas_pagar(empresa_id, compra_id) WHERE compra_id IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS ux_contas_receber_venda ON contas_receber(empresa_id, venda_id) WHERE venda_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_contas_pagar_empresa_venc ON contas_pagar(empresa_id, status, vencimento);
CREATE INDEX IF NOT EXISTS idx_contas_receber_empresa_venc ON contas_receber(empresa_id, status, vencimento);

CREATE TABLE IF NOT EXISTS financeiro_historico (
    id BIGSERIAL PRIMARY KEY,
    empresa_id INTEGER NOT NULL REFERENCES empresas(id) ON DELETE CASCADE,
    tipo VARCHAR(10) NOT NULL CHECK (tipo IN ('PAGAR','RECEBER')),
    conta_id INTEGER NOT NULL,
    acao VARCHAR(20) NOT NULL,
    valor NUMERIC(12,2),
    detalhe VARCHAR(300) NOT NULL DEFAULT '',
    usuario_id INTEGER,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_financeiro_historico_conta ON financeiro_historico(empresa_id, tipo, conta_id, id DESC);
