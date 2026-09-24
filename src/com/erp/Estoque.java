package com.erp;

import com.erp.repository.PessoaRepository;
import com.erp.repository.ProdutoRepository;
import com.erp.repository.TituloRepository;

import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Scanner;

public class Estoque {
    private final ProdutoRepository produtoRepository;
    private final PessoaRepository pessoaRepository;
    private final TituloRepository tituloRepository;

    public Estoque() {
        this.produtoRepository = new ProdutoRepository();
        this.pessoaRepository = new PessoaRepository();
        this.tituloRepository = new TituloRepository();
    }

    private String lerLinha(Scanner scanner) {
        return scanner.hasNextLine() ? scanner.nextLine().trim() : "";
    }

    private int lerInteiro(Scanner scanner, int padrao) {
        String s = lerLinha(scanner);
        if (s.isBlank()) return padrao;
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return padrao;
        }
    }

    private double lerDouble(Scanner scanner, double padrao) {
        String s = lerLinha(scanner);
        if (s.isBlank()) return padrao;
        try {
            return Double.parseDouble(s.replace(",", "."));
        } catch (Exception e) {
            return padrao;
        }
    }

    public void addPessoa(Scanner scanner, SessaoAtual sessao) {
        try {
            // Delegação de IDs: O usuário não adiciona o ID da pessoa de forma manual, o Banco gera com SERIAL
            System.out.println("\n--- Cadastrar Pessoa (Empresa ID: " + sessao.getEmpresaId() + ") ---");
            System.out.print("Nome da Pessoa: ");
            String nome = lerLinha(scanner);
            System.out.print("Tipo de Pessoa (1-Cliente, 2-Fornecedor, 3-Funcionario): ");
            int tipo = lerInteiro(scanner, 1);

            Pessoa pessoa = new Pessoa(tipo, nome);
            System.out.print("Documento (CPF/CNPJ): ");
            pessoa.setDocumento(lerLinha(scanner));
            System.out.print("Telefone: ");
            pessoa.setTelefone(lerLinha(scanner));
            System.out.print("E-mail: ");
            pessoa.setEmail(lerLinha(scanner));

            if (tipo == 1) {
                System.out.print("Endereço: ");
                pessoa.setEndereco(lerLinha(scanner));
            }

            pessoaRepository.salvar(pessoa, sessao.getEmpresaId());
            System.out.println("Pessoa adicionada com sucesso! ID gerado pelo banco: " + pessoa.getId());
        } catch (SQLException e) {
            System.err.println("Erro ao salvar pessoa no banco de dados: " + e.getMessage());
        }
    }

    public void addProduto(Scanner scanner, SessaoAtual sessao) {
        try {
            // Delegação de IDs: O usuário não adiciona o id do produto de forma manual, o banco gera com SERIAL
            System.out.println("\n--- Cadastrar Produto (Empresa ID: " + sessao.getEmpresaId() + ") ---");
            System.out.print("Nome do Produto: ");
            String nome = lerLinha(scanner);
            System.out.print("Código do Produto (ou deixe em branco para preencher automático): ");
            String codigo = lerLinha(scanner);
            System.out.print("Preço do Produto: ");
            double preco = lerDouble(scanner, 0.0);

            Produto produto = new Produto(nome, preco);
            if (!codigo.isBlank()) {
                produto.setCodigo(codigo);
            }
            System.out.print("Categoria: ");
            produto.setCategoria(lerLinha(scanner));
            System.out.print("Quantidade Inicial: ");
            produto.setQuantidade(lerInteiro(scanner, 0));
            System.out.print("Estoque Mínimo (para alertas Plus/Premium): ");
            produto.setEstoqueMinimo(lerInteiro(scanner, 0));

            produtoRepository.salvar(produto, sessao.getEmpresaId());
            System.out.println("Produto adicionado com sucesso! ID gerado pelo banco: " + produto.getId());
        } catch (SQLException e) {
            System.err.println("Erro ao salvar produto no banco de dados: " + e.getMessage());
        }
    }

    public void listaProdutos(SessaoAtual sessao, Scanner scanner) {
        try {
            String filtroCategoria = null;

            // Libera a pergunta de categoria apenas se for Plus ou Premium
            if (sessao.getPlano().getPeso() >= 2) {
                System.out.print("Deseja filtrar por categoria? (Digite a categoria ou deixe em branco para listar todos): ");
                filtroCategoria = lerLinha(scanner);
            }

            System.out.println("\n--- Produtos (Empresa ID: " + sessao.getEmpresaId() + ") ---");
            List<Produto> produtos = produtoRepository.listar(sessao.getEmpresaId(), filtroCategoria);

            if (produtos.isEmpty()) {
                System.out.println("Nenhum produto cadastrado para esta empresa.");
                return;
            }

            for (Produto produto : produtos) {
                System.out.println(produto.getId() + " - " + produto.getNome() + " - Cat: " + produto.getCategoria() + " - Qtd: " + produto.getQuantidade() + " - R$ " + String.format("%.2f", produto.getPreco()));
            }
        } catch (SQLException e) {
            System.err.println("Erro ao listar produtos do banco de dados: " + e.getMessage());
        }
    }

    public void listaPessoas(SessaoAtual sessao) {
        try {
            System.out.println("\n--- Pessoas / Cadastros (Empresa ID: " + sessao.getEmpresaId() + ") ---");
            List<Pessoa> pessoas = pessoaRepository.listar(sessao.getEmpresaId());

            if (pessoas.isEmpty()) {
                System.out.println("Nenhuma pessoa cadastrada para esta empresa.");
                return;
            }

            for (Pessoa pessoa : pessoas) {
                System.out.println(pessoa.getId() + " - " + pessoa.getNome() + " - Tipo: " + pessoa.getTipoDescricao()+ " - Doc: " + pessoa.getDocumento() + " - Tel: " + pessoa.getTelefone());
            }
        } catch (SQLException e) {
            System.err.println("Erro ao listar pessoas do banco de dados: " + e.getMessage());
        }
    }

    public void compraProduto(Scanner scanner, SessaoAtual sessao) {
        try {
            System.out.print("ID do Produto a comprar: ");
            int produtoId = lerInteiro(scanner, -1);

            // Busca isolada no banco por empresa_id (Aqui entra a parte do Multi-Tenant)
            Produto produto = produtoRepository.buscarPorId(produtoId, sessao.getEmpresaId());
            if (produto == null) {
                System.out.println("Produto não encontrado para esta empresa.");
                return;
            }

            System.out.print("ID do Fornecedor (tipo 2): ");
            int fornecedorId = lerInteiro(scanner, -1);

            Pessoa fornecedor = pessoaRepository.buscarPorIdETipo(fornecedorId, 2, sessao.getEmpresaId());
            if (fornecedor == null) {
                System.out.println("Fornecedor não encontrado para esta empresa.");
                return;
            }

            System.out.print("Quantidade a comprar: ");
            int quantidadeComprada = lerInteiro(scanner, 0);
            if (quantidadeComprada <= 0) {
                System.out.println("Quantidade deve ser maior que zero.");
                return;
            }

            // Gera o título financeiro (SEM UUID, o Banco gera o ID com SERIAL no INSERT)
            Titulo titulo = new Titulo(
                    produto.getPreco() * quantidadeComprada,
                    false,
                    fornecedor.getId(),
                    "a pagar",
                    LocalDate.now(),
                    "Compra de " + quantidadeComprada + "x " + produto.getNome()
            );
            tituloRepository.salvar(titulo, sessao.getEmpresaId());

            // Atualiza o estoque físico no banco
            int novaQtd = produto.getQuantidade() + quantidadeComprada;
            produtoRepository.atualizarQuantidadeEstoque(produto.getId(), novaQtd, sessao.getEmpresaId());

            System.out.println("Compra registrada com sucesso! Título a pagar gerado com ID: " + titulo.getId());
        } catch (SQLException e) {
            System.err.println("Erro ao registrar compra no banco de dados: " + e.getMessage());
        }
    }

    public void vendaProduto(Scanner scanner, SessaoAtual sessao) {
        try {
            System.out.print("ID do Produto a vender: ");
            int produtoId = lerInteiro(scanner, -1);

            // Busca isolada no banco por empresa_id (Continuando com a lógica do Multi-Tenant)
            Produto produto = produtoRepository.buscarPorId(produtoId, sessao.getEmpresaId());
            if (produto == null) {
                System.out.println("Produto não encontrado para esta empresa.");
                return;
            }

            System.out.print("ID do Cliente (tipo 1): ");
            int clienteId = lerInteiro(scanner, -1);

            Pessoa cliente = pessoaRepository.buscarPorIdETipo(clienteId, 1, sessao.getEmpresaId());
            if (cliente == null) {
                System.out.println("Cliente não encontrado para esta empresa.");
                return;
            }

            System.out.print("Quantidade a vender: ");
            int quantidadeVendida = lerInteiro(scanner, 0);
            if (quantidadeVendida <= 0) {
                System.out.println("Quantidade deve ser maior que zero.");
                return;
            }

            if (produto.getQuantidade() < quantidadeVendida) {
                System.out.println("Estoque insuficiente para esta venda. Disponível: " + produto.getQuantidade());
                return;
            }

            // Gera o título financeiro (SEM UUID, o banco gera o ID com SERIAL no INSERT)
            Titulo titulo = new Titulo(
                    produto.getPreco() * quantidadeVendida,
                    false,
                    cliente.getId(),
                    "a receber",
                    LocalDate.now(),
                    "Venda de " + quantidadeVendida + "x " + produto.getNome()
            );
            tituloRepository.salvar(titulo, sessao.getEmpresaId());

            // Atualiza o estoque físico no banco
            int novaQtd = produto.getQuantidade() - quantidadeVendida;
            produtoRepository.atualizarQuantidadeEstoque(produto.getId(), novaQtd, sessao.getEmpresaId());
            produto.setQuantidade(novaQtd);

            System.out.println("Venda registrada com sucesso! Título a receber gerado com ID: " + titulo.getId());

            // Trava do plano: Verifica estoque baixo apenas para Plus ou Premium
            if (sessao.getPlano().getPeso() >= 2) {
                verificarEstoqueBaixo(produto);
            }
        } catch (SQLException e) {
            System.err.println("Erro ao registrar venda no banco de dados: " + e.getMessage());
        }
    }

    public void fazPagamento(Scanner scanner, SessaoAtual sessao) {
        try {
            System.out.print("ID do Título a pagar: ");
            int tituloId = lerInteiro(scanner, -1);

            boolean quitado = tituloRepository.quitarTitulo(tituloId, sessao.getEmpresaId());
            if (quitado) {
                System.out.println("Título #" + tituloId + " pago com sucesso.");
            } else {
                System.out.println("Título #" + tituloId + " não encontrado para esta empresa.");
            }
        } catch (SQLException e) {
            System.err.println("Erro ao pagar título no banco de dados: " + e.getMessage());
        }
    }

    public void listarTitulosDeDestaque(SessaoAtual sessao) {
        try {
            System.out.println("\n--- Títulos em Aberto (Empresa ID: " + sessao.getEmpresaId() + ") ---");
            List<Titulo> abertos = tituloRepository.listarAbertos(sessao.getEmpresaId());

            if (abertos.isEmpty()) {
                System.out.println("Nenhum título em aberto para esta empresa.");
                return;
            }

            for (Titulo t : abertos) {
                System.out.println(t.getId() + " - R$ " + String.format("%.2f", t.getQuantidade()) + " - Tipo: " + t.getTipoTitulo() + " - Vencimento: " + t.getData()+ " - " + t.getDescricao());
            }
        } catch (SQLException e) {
            System.err.println("Erro ao listar títulos no banco de dados: " + e.getMessage());
        }
    }

    private void verificarEstoqueBaixo(Produto produto) {
        if (produto.getQuantidade() <= produto.getEstoqueMinimo()) {
            System.out.println("ALERTA PLUS/PREMIUM: O produto " + produto.getNome() + " está com estoque baixo! Restam apenas: " + produto.getQuantidade());
        }
    }

    public void relatorioFaturamento(SessaoAtual sessao, Scanner scanner) {
        if (sessao.getPlano().getPeso() < 2) {
            System.out.println("Acesso Negado: Funcionalidade exclusiva para planos PLUS ou PREMIUM.");
            return;
        }

        try {
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("dd/MM/yyyy");
            System.out.print("Data inicial (dd/MM/yyyy): ");
            String dIni = lerLinha(scanner);
            LocalDate inicio = LocalDate.parse(dIni, fmt);
            System.out.print("Data final (dd/MM/yyyy): ");
            String dFim = lerLinha(scanner);
            LocalDate fim = LocalDate.parse(dFim, fmt);

            // Query(Consulta) multi-tenant no banco: A Loja A nunca acessa o faturamento da Loja B
            double totalRecebido = tituloRepository.obterFaturamentoPeriodo(sessao.getEmpresaId(), inicio, fim);
            System.out.println("Faturamento (Empresa " + sessao.getEmpresaId() + ") entre " 
                    + inicio.format(fmt) + " e " + fim.format(fmt) + ": R$ " + String.format("%.2f", totalRecebido));
        } catch (Exception e) {
            System.err.println("Erro ao consultar faturamento no banco de dados: " + e.getMessage());
        }
    }

    public void comparacaoFaturamento(SessaoAtual sessao, Scanner scanner) {
        if (sessao.getPlano().getPeso() < 3) {
            System.out.println("Acesso Negado: Funcionalidade exclusiva para plano PREMIUM.");
            return;
        }

        try {
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("dd/MM/yyyy");
            System.out.println("--- Período 1 ---");
            System.out.print("Data inicial 1 (dd/MM/yyyy): ");
            LocalDate inicio1 = LocalDate.parse(lerLinha(scanner), fmt);
            System.out.print("Data final 1 (dd/MM/yyyy): ");
            LocalDate fim1 = LocalDate.parse(lerLinha(scanner), fmt);

            System.out.println("--- Período 2 ---");
            System.out.print("Data inicial 2 (dd/MM/yyyy): ");
            LocalDate inicio2 = LocalDate.parse(lerLinha(scanner), fmt);
            System.out.print("Data final 2 (dd/MM/yyyy): ");
            LocalDate fim2 = LocalDate.parse(lerLinha(scanner), fmt);

            // Consultas multi-tenant no banco
            double total1 = tituloRepository.obterFaturamentoPeriodo(sessao.getEmpresaId(), inicio1, fim1);
            double total2 = tituloRepository.obterFaturamentoPeriodo(sessao.getEmpresaId(), inicio2, fim2);

            System.out.println("\nFaturamento Empresa " + sessao.getEmpresaId() + ":");
            System.out.println("Total Período 1: R$ " + String.format("%.2f", total1));
            System.out.println("Total Período 2: R$ " + String.format("%.2f", total2));
            System.out.println("Diferença: R$ " + String.format("%.2f", Math.abs(total2 - total1)) 
                    + (total2 >= total1 ? " (Crescimento)" : " (Queda)"));
        } catch (Exception e) {
            System.err.println("Erro ao comparar faturamento no banco de dados: " + e.getMessage());
        }
    }
}
