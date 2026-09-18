-- 1. Criando empresas contratantes (ID 1 e ID 2 para multi-tenant)
INSERT INTO empresas (nome_fantasia, documento, plano_assinatura) 
VALUES ('Minha Empresa Teste (Loja A)', '99.999.999/0001-99', 'PREMIUM'),
       ('Segunda Empresa (Loja B)', '88.888.888/0001-88', 'BASE');

-- 2. Categorias (Agora vinculadas à empresa 1)
INSERT INTO categorias (empresa_id, nome) VALUES 
(1, 'Eletrônicos'), 
(1, 'Papelaria'), 
(1, 'Alimentos');

-- 3. Distribuindo a antiga tabela 'pessoas'
-- Cliente
INSERT INTO clientes (empresa_id, nome, documento, telefone, email) VALUES
(1, 'Cliente Teste', '000.000.000-00', '11999999999', 'cliente@teste.com');

-- Fornecedor
INSERT INTO fornecedores (empresa_id, nome, documento, telefone, email) VALUES
(1, 'Fornecedor Teste', '11.111.111/0001-11', '11888888888', 'fornecedor@teste.com');

-- Usuários (O antigo 'cargo' agora é mapeado para 'perfil')
INSERT INTO usuarios (empresa_id, nome, email, senha_hash, perfil) VALUES
(1, 'Admin Chefe', 'chefe@erp.com', 'hash_temporario', 'chefe'),
(1, 'Funcionario Teste', 'funcionario@erp.com', 'hash_temporario', 'trabalhador');

-- 4. Produtos (Vinculados à empresa 1)
INSERT INTO produtos (empresa_id, nome, codigo, preco, categoria_id) VALUES
(1, 'Mouse sem fio', 'ELE001', 45.90, 1),
(1, 'Caderno universitário', 'PAP001', 18.50, 2),
(1, 'Café em pó 500g', 'ALI001', 22.00, 3);

-- 5. Estoque (Vinculados à empresa 1)
INSERT INTO estoque (empresa_id, produto_id, quantidade, estoque_minimo) VALUES
(1, 1, 50, 10), 
(1, 2, 120, 20), 
(1, 3, 80, 15);