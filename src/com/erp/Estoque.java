package com.erp;

import java.io.*;
import java.util.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

public class Estoque {
	private List<Produto> produtos;
	private List<Titulo> titulos;
	private List<Pessoa> pessoas;

	private static final String PRODUTOS_ARQUIVO = "produtos.txt";
	private static final String TITULOS_ARQUIVO = "titulos.txt";
	private static final String PESSOAS_ARQUIVO = "pessoas.txt";

	public Estoque() throws IOException {
		produtos = new ArrayList<>();
		titulos = new ArrayList<>();
		pessoas = new ArrayList<>();
		carregaProduto();
		carregaTitulos();
		carregaPessoas();
	}

	public void addPessoa(Scanner scanner) throws IOException {
		System.out.print("ID da Pessoa: ");
		String id = scanner.nextLine();
		System.out.print("Nome da Pessoa: ");
		String nome = scanner.nextLine();
		System.out.print("Tipo de Pessoa (1-Cliente, 2-Fornecedor, 3-Funcionário): ");
		int tipo = scanner.nextInt();
		scanner.nextLine();

		Pessoa pessoa = new Pessoa(id, tipo, nome);
		System.out.print("Documento (CPF/CNPJ): ");
		pessoa.setDocumento(scanner.nextLine());
		System.out.print("Telefone: ");
		pessoa.setTelefone(scanner.nextLine());
		System.out.print("E-mail: ");
		pessoa.setEmail(scanner.nextLine());

		pessoas.add(pessoa);
		savePessoas();
		System.out.println("Pessoa adicionada com sucesso.");
	}

	public void addProduto(Scanner scanner) throws IOException {
		System.out.print("ID do Produto: ");
		String id = scanner.nextLine();
		System.out.print("Nome do Produto: ");
		String nome = scanner.nextLine();
		System.out.print("Preço do Produto: ");
		double preco = scanner.nextDouble();
		scanner.nextLine();

		Produto produto = new Produto(id, nome, preco);
		System.out.print("Categoria: ");
		produto.setCategoria(scanner.nextLine());
		System.out.print("Quantidade Inicial: ");
		produto.setQuantidade(scanner.nextInt());
		System.out.print("Estoque Mínimo (para alertas Plus/Premium): ");
		produto.setEstoqueMinimo(scanner.nextInt());
		scanner.nextLine(); 
		produtos.add(produto);
		saveProdutos();
		System.out.println("Produto adicionado com sucesso.");
	}

	public void listaProdutos(SessaoAtual sessao, Scanner scanner) {
    String filtroCategoria = null;
    
    // Libera a pergunta de categoria apenas se for Plus ou Premium
    if (sessao.getPlano().getPeso() >= 2) {
        System.out.print("Deseja filtrar por categoria? (Digite a categoria ou deixe em branco para listar todos): ");
        filtroCategoria = scanner.nextLine();
    }

    System.out.println("Produtos:");
    for (Produto produto : produtos) {
        boolean mostrar = true;
        
        // Aplica o filtro se ele existir e não for vazio
        if (filtroCategoria != null && !filtroCategoria.trim().isEmpty()) {
            if (!produto.getCategoria().equalsIgnoreCase(filtroCategoria)) {
                mostrar = false;
            }
        }
        
        if (mostrar) {
            System.out.println(produto.getId() + " - " + produto.getNome() + " - Qtd: " + produto.getQuantidade() + " - R$ " + produto.getPreco());
        }
    }
}

	public void listaPessoas() {
		System.out.println("Produtos:");
		for (Pessoa pessoa : pessoas) {
			System.out.println(pessoa.getId() + " - " + pessoa.getNome() + " - Tipo: " + pessoa.getTipo());
		}
	}

	public void compraProduto(Scanner scanner) throws IOException {
		System.out.print("ID do Produto a comprar: ");
		String produtoId = scanner.nextLine();

		Produto produto = null;
		for (Produto p : produtos) {
			if (p.getId().equals(produtoId)) {
				produto = p;
				break;
			}
		}

		if (produto != null) {
			Pessoa fornecedor = buscarPessoaPorTipo(scanner, 2);
			if (fornecedor == null) {
				System.out.println("Fornecedor não encontrado.");
				return;
			}

			System.out.print("Quantidade a comprar: ");
			int quantidadeComprada = scanner.nextInt();
			scanner.nextLine(); // Consumir nova linha

			// 1. Gera o título multiplicando o preço pela quantidade
			Titulo titulo = new Titulo(UUID.randomUUID().toString(), produto.getPreco() * quantidadeComprada, false, fornecedor.getId(), "a pagar", LocalDate.now());
			titulos.add(titulo);
			saveTitulos();

			// 2. Adiciona ao estoque físico e salva
			produto.setQuantidade(produto.getQuantidade() + quantidadeComprada);
			saveProdutos();

			System.out.println("Compra registrada. Título a pagar gerado: " + titulo.getId());
		} else {
			System.out.println("Produto não encontrado.");
		}
	}

	public void vendaProduto(Scanner scanner, SessaoAtual sessao) throws IOException {
		System.out.print("ID do Produto a vender: ");
		String produtoId = scanner.nextLine();

		Produto produto = null;
		for (Produto p : produtos) {
			if (p.getId().equals(produtoId)) {
				produto = p;
				break;
			}
		}

		if (produto != null) {
			Pessoa cliente = buscarPessoaPorTipo(scanner, 1);
			if (cliente == null) {
				System.out.println("Cliente não encontrado.");
				return;
			}

			System.out.print("Quantidade a vender: ");
			int quantidadeVendida = scanner.nextInt();
			scanner.nextLine(); // Consumir nova linha

			if (produto.getQuantidade() < quantidadeVendida) {
				System.out.println("Estoque insuficiente para esta venda.");
				return;
			}

			// 1. Gera o título multiplicando o preço pela quantidade
			Titulo titulo = new Titulo(UUID.randomUUID().toString(), produto.getPreco() * quantidadeVendida, false, cliente.getId(), "a receber", LocalDate.now());
			titulos.add(titulo);
			saveTitulos();

			// 2. Subtrai do estoque físico e salva
			produto.setQuantidade(produto.getQuantidade() - quantidadeVendida);
			saveProdutos();

			System.out.println("Venda registrada. Título a receber gerado: " + titulo.getId());

			// 3. Trava do plano: Verifica estoque baixo apenas para Plus ou Premium
			if (sessao.getPlano().getPeso() >= 2) {
				verificarEstoqueBaixo(produto);
			}
		} else {
			System.out.println("Produto não encontrado.");
}

	}

	public void fazPagamento(Scanner scanner) throws IOException {
		System.out.print("ID do Título a pagar: ");
		String tituloId = scanner.nextLine();

		Titulo titulo = null;
		for (Titulo t : titulos) {
			if (t.getId().equals(tituloId)) {
				titulo = t;
				break;
			}
		}

		if (titulo != null) {
			titulo.setPaga(true);
			saveTitulos();
			System.out.println("Título pago com sucesso.");
		} else {
			System.out.println("Título não encontrado.");
		}
	}

	public void listarTitulosDeDestaque() {
		System.out.println("Títulos em Aberto:");
		for (Titulo title : titulos) {
			if (!title.isPago()) {
				System.out.println(title.getId() + " - R$ " + title.getQuantidade() + " - Pessoa: "
						+ title.getPessoaId() + " - Tipo: " + title.getTipoTitulo());
			}
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

		DateTimeFormatter fmt = DateTimeFormatter.ofPattern("dd/MM/yyyy");
		System.out.print("Data inicial (dd/MM/yyyy): ");
		LocalDate inicio = LocalDate.parse(scanner.nextLine(), fmt);
		System.out.print("Data final (dd/MM/yyyy): ");
		LocalDate fim = LocalDate.parse(scanner.nextLine(), fmt);

		double totalRecebido = 0;
		for (Titulo t : titulos) {
			if (t.getTipoTitulo().equals("a receber") && t.isPago()) {
				if (!t.getData().isBefore(inicio) && !t.getData().isAfter(fim)) {
					totalRecebido += t.getQuantidade();
				}
			}
		}
		System.out.println("Faturamento entre " + inicio.format(fmt) + " e " + fim.format(fmt) + ": R$ " + totalRecebido);
	}

	public void comparacaoFaturamento(SessaoAtual sessao, Scanner scanner) {
		if (sessao.getPlano().getPeso() < 3) {
			System.out.println("Acesso Negado: Funcionalidade exclusiva para plano PREMIUM.");
			return;
		}

		DateTimeFormatter fmt = DateTimeFormatter.ofPattern("dd/MM/yyyy");
		System.out.println("--- Período 1 ---");
		System.out.print("Data inicial 1: ");
		LocalDate inicio1 = LocalDate.parse(scanner.nextLine(), fmt);
		System.out.print("Data final 1: ");
		LocalDate fim1 = LocalDate.parse(scanner.nextLine(), fmt);

		System.out.println("--- Período 2 ---");
		System.out.print("Data inicial 2: ");
		LocalDate inicio2 = LocalDate.parse(scanner.nextLine(), fmt);
		System.out.print("Data final 2: ");
		LocalDate fim2 = LocalDate.parse(scanner.nextLine(), fmt);

		double total1 = 0, total2 = 0;
		for (Titulo t : titulos) {
			if (t.getTipoTitulo().equals("a receber") && t.isPago()) {
				if (!t.getData().isBefore(inicio1) && !t.getData().isAfter(fim1)) total1 += t.getQuantidade();
				if (!t.getData().isBefore(inicio2) && !t.getData().isAfter(fim2)) total2 += t.getQuantidade();
			}
		}
		
		System.out.println("Total Período 1: R$ " + total1);
		System.out.println("Total Período 2: R$ " + total2);
		System.out.println("Diferença: R$ " + Math.abs(total2 - total1) + (total2 >= total1 ? " (Crescimento)" : " (Queda)"));
	}

	private Pessoa buscarPessoaPorTipo(Scanner scanner, int tipo) {
		System.out.print("ID da Pessoa (tipo " + tipo + "): ");
		String id = scanner.nextLine();
		for (Pessoa p : pessoas) {
			if (p.getId().equals(id) && p.getTipo() == tipo) {
				return p;
			}
		}
		return null;
	}

	private void carregaProduto() throws IOException {
		File file = new File(PRODUTOS_ARQUIVO);
		if (file.exists()) {
			try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
				String line;
				while ((line = reader.readLine()) != null) {
					produtos.add(Produto.fromString(line));
				}
			}
		}
	}

	private void carregaTitulos() throws IOException {
		File file = new File(TITULOS_ARQUIVO);
		if (file.exists()) {
			try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
				String line;
				while ((line = reader.readLine()) != null) {
					titulos.add(Titulo.fromString(line));
				}
			}
		}
	}

	private void carregaPessoas() throws IOException {
		File file = new File(PESSOAS_ARQUIVO);
		if (file.exists()) {
			try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
				String line;
				while ((line = reader.readLine()) != null) {
					pessoas.add(Pessoa.fromString(line));
				}
			}
		}
	}

	private void saveProdutos() throws IOException {
		try (BufferedWriter writer = new BufferedWriter(new FileWriter(PRODUTOS_ARQUIVO))) {
			for (Produto product : produtos) {
				writer.write(product.toString());
				writer.newLine();
			}
		}
	}

	private void saveTitulos() throws IOException {
		try (BufferedWriter writer = new BufferedWriter(new FileWriter(TITULOS_ARQUIVO))) {
			for (Titulo title : titulos) {
				writer.write(title.toString());
				writer.newLine();
			}
		}
	}

	private void savePessoas() throws IOException {
		try (BufferedWriter writer = new BufferedWriter(new FileWriter(PESSOAS_ARQUIVO))) {
			for (Pessoa pessoa : pessoas) {
				writer.write(pessoa.toString());
				writer.newLine();
			}
		}
	}
}
