package com.erp;

import java.io.IOException;
import java.util.Scanner;

public class Main {
    public static void main(String[] args) throws IOException {
        Scanner scanner = new Scanner(System.in);
        Estoque estoque = new Estoque();

        while (true) {
            System.out.println("\nMenu:");
            System.out.println("1. Adicionar Produto");
            System.out.println("2. Listar Produtos");
            System.out.println("3. Comprar Produto (Fornecedor)");
            System.out.println("4. Vender Produto (Cliente)");
            System.out.println("5. Efetuar Pagamento");
            System.out.println("6. Listar Títulos em Aberto");
            System.out.println("7. Cadastrar Pessoa");
            System.out.println("8. Listar Pessoas");
            System.out.println("9. Sair");
            System.out.print("Escolha uma opção: ");
            int choice = scanner.nextInt();
            scanner.nextLine(); // Consumir nova linha

            switch (choice) {
                case 1:
                    estoque.addProduto(scanner);
                    break;
                case 2:
                    estoque.listaProdutos();
                    break;
                case 3:
                    estoque.compraProduto(scanner);
                    break;
                case 4:
                    estoque.vendaProduto(scanner);
                    break;
                case 5:
                    estoque.fazPagamento(scanner);
                    break;
                case 6:
                    estoque.listarTitulosDeDestaque();
                    break;
                case 7:
                    estoque.addPessoa(scanner);
                    break;
                case 8:
                	estoque.listaPessoas();
                    return;
                case 9:
                    System.out.println("Saindo...");
                    return;
                default:
                    System.out.println("Opção inválida. Tente novamente.");
            }
        }
    }
}
