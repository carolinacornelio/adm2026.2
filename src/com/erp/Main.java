package com.erp;

import java.io.IOException;
import java.util.Scanner;

public class Main {
    public static void main(String[] args) throws IOException {
    Scanner scanner = new Scanner(System.in);
    Estoque estoque = new Estoque();

    System.out.println("Simular acesso com qual plano?");
    System.out.println("1 - BASE");
    System.out.println("2 - PLUS");
    System.out.println("3 - PREMIUM");
    int opPlano = scanner.nextInt();
    scanner.nextLine();
    
    PlanoAssinatura planoEscolhido = PlanoAssinatura.BASE;
    if (opPlano == 2) planoEscolhido = PlanoAssinatura.PLUS;
    if (opPlano == 3) planoEscolhido = PlanoAssinatura.PREMIUM;
    
    SessaoAtual sessao = new SessaoAtual(planoEscolhido);

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
            System.out.println("9. Relatório de Faturamento (Plus/Premium)");
            System.out.println("10. Comparação de Faturamento (Premium)");
            System.out.println("11. Sair");
            System.out.print("Escolha uma opção: ");
            int choice = scanner.nextInt();
            scanner.nextLine(); // Consumir nova linha

            switch (choice) {
                case 1:
                    estoque.addProduto(scanner); 
                    break;
                case 2:
                    estoque.listaProdutos(sessao, scanner); 
                    break;
                case 3:
                    estoque.compraProduto(scanner); 
                    break;
                case 4:
                    estoque.vendaProduto(scanner, sessao); 
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
                    break;
                case 9:
                    estoque.relatorioFaturamento(sessao, scanner); 
                    break;
                case 10:
                    estoque.comparacaoFaturamento(sessao, scanner);
                    break;
                case 11:
                    System.out.println("Saindo...");
                    return;
                default:
                    System.out.println("Opção inválida. Tente novamente.");
            }
        }
    }
}
