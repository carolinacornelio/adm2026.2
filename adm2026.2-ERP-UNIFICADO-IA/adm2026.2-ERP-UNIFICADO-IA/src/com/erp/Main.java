package com.erp;

import java.util.Scanner;

public class Main {

    private static String lerLinha(Scanner scanner) {
        return scanner.hasNextLine() ? scanner.nextLine().trim() : "";
    }

    private static int lerInteiro(Scanner scanner, int padrao) {
        String linha = lerLinha(scanner);
        if (linha.isBlank()) return padrao;
        try {
            return Integer.parseInt(linha);
        } catch (NumberFormatException e) {
            return padrao;
        }
    }

    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);
        Estoque estoque = new Estoque();

        System.out.println("\n[Sistema ERP]");

        System.out.print("\nInforme o ID da Empresa (Tenant) [Padrão: 1]: ");
        int empresaId = lerInteiro(scanner, 1);

        System.out.println("\nSimular acesso com qual plano?");
        System.out.println();
        System.out.println("1 - BASE");
        System.out.println("2 - PLUS");
        System.out.println("3 - PREMIUM");
        System.out.println();
        System.out.print("Escolha o plano [1-3, Padrão: 1]: ");
        int opPlano = lerInteiro(scanner, 1);

        PlanoAssinatura planoEscolhido = PlanoAssinatura.BASE;
        if (opPlano == 2) planoEscolhido = PlanoAssinatura.PLUS;
        if (opPlano == 3) planoEscolhido = PlanoAssinatura.PREMIUM;

        SessaoAtual sessao = new SessaoAtual(empresaId, planoEscolhido);
        System.out.println("Sessão iniciada -> Empresa ID: " + sessao.getEmpresaId() + " | Plano: " + sessao.getPlano());

        while (scanner.hasNextLine()) {
            System.out.println();
            System.out.println("Menu ERP (Empresa ID: " + sessao.getEmpresaId() + " | Plano: " + sessao.getPlano() + "):");
            System.out.println();
            System.out.println("1. Adicionar Produto");
            System.out.println("2. Listar Produtos");
            System.out.println("3. Comprar Produto (Fornecedor)");
            System.out.println("4. Vender Produto (Cliente)");
            System.out.println("5. Efetuar Pagamento de Título");
            System.out.println("6. Listar Títulos em Aberto");
            System.out.println("7. Cadastrar Pessoa (Cliente/Fornecedor/Funcionário)");
            System.out.println("8. Listar Pessoas");
            System.out.println("9. Relatório de Faturamento (Plus/Premium)");
            System.out.println("10. Comparação de Faturamento (Premium)");
            System.out.println("11. Alternar Empresa (Testar Multi-Tenant)");
            System.out.println("12. Sair");
            System.out.print("Escolha uma opção: ");

            int choice = lerInteiro(scanner, -1);

            switch (choice) {
                case 1:
                    estoque.addProduto(scanner, sessao);
                    break;
                case 2:
                    estoque.listaProdutos(sessao, scanner);
                    break;
                case 3:
                    estoque.compraProduto(scanner, sessao);
                    break;
                case 4:
                    estoque.vendaProduto(scanner, sessao);
                    break;
                case 5:
                    estoque.fazPagamento(scanner, sessao);
                    break;
                case 6:
                    estoque.listarTitulosDeDestaque(sessao);
                    break;
                case 7:
                    estoque.addPessoa(scanner, sessao);
                    break;
                case 8:
                    estoque.listaPessoas(sessao);
                    break;
                case 9:
                    estoque.relatorioFaturamento(sessao, scanner);
                    break;
                case 10:
                    estoque.comparacaoFaturamento(sessao, scanner);
                    break;
                case 11:
                    System.out.print("Digite o novo ID da Empresa (Tenant): ");
                    int novaEmpresa = lerInteiro(scanner, sessao.getEmpresaId());
                    sessao.setEmpresaId(novaEmpresa);
                    System.out.println("Empresa alternada com sucesso para ID: " + sessao.getEmpresaId());
                    break;
                case 12:
                    System.out.println("Saindo...");
                    return;
                default:
                    System.out.println("Opção inválida. Tente novamente.");
            }
        }
    }
}
