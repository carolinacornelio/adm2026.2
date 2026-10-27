# ADM ERP

ERP web para cadastro de produtos e pessoas, compras, vendas, estoque e operação de caixa (POS). Inclui controle de validade, inventário físico, chamados de atendimento e um assistente de IA opcional.

## Começar

### Requisitos

- **[Java JDK 21](https://adoptium.net/temurin/releases/?version=21)**
- **[Maven 3.9 ou superior](https://maven.apache.org/download.cgi)**
- Acesso à internet para o banco PostgreSQL compartilhado
- **Ollama** apenas para usar o assistente de IA

Este repositório não inclui Maven Wrapper. A pasta `src/com/erp/` contém uma implementação antiga de terminal; a aplicação web atual é iniciada pelo Spring Boot.

### Executar o ERP

1. Clone ou atualize a branch que deseja usar.
2. Abra a pasta do projeto que contém `pom.xml`.
3. Confira se `java -version` mostra Java 21 e se `mvn -v` está disponível e também usa Java 21.
4. Inicie a aplicação:

```powershell
mvn spring-boot:run
```

5. Acesse **[http://localhost:8080/login.html](http://localhost:8080/login.html)**. Cadastre uma empresa ou entre com uma conta existente e escolha ERP ou POS.

Para encerrar, pressione `Ctrl+C` no terminal.

### Alterar as telas e usar o Live Server

Edite somente `front-end-erp/`. O Spring serve essa pasta quando é iniciado na raiz do projeto, e o Maven copia seus arquivos para o JAR. Não edite `target/`: essa pasta é gerada pela compilação. No VS Code, a configuração **ERP — Spring Boot** executa `mvn compile` antes do F5 e inicia a aplicação na pasta correta. A compilação automática do Java no editor está desativada para evitar que sobrescreva as classes geradas pelo Maven.

O Live Server é opcional: ele serve o HTML, mas a API continua precisando do Spring Boot em execução na porta `8080`. A configuração do projeto abre `front-end-erp/` na porta `5501`. Faça login novamente ao trocar entre Live Server e `localhost:8080`, pois cada endereço mantém sua própria sessão do navegador.

Se estiver vendo arquivos de uma compilação antiga, encerre o servidor e execute `mvn clean spring-boot:run` na pasta de `pom.xml`. Ao distribuir um JAR, gere-o novamente depois de alterar as telas.

## Banco de dados e credenciais

O projeto já inclui `application-local.properties` e `db.properties`, versionados no Git por decisão da equipe. O Spring Boot lê o primeiro arquivo na raiz do projeto; a implementação antiga de terminal lê o segundo. As configurações atuais apontam para o PostgreSQL compartilhado. Não é preciso instalar um banco local quando esse servidor estiver acessível.

Inicie o Spring Boot a partir da pasta que contém `pom.xml` e `application-local.properties`. Se o arquivo não for encontrado, a configuração padrão tentará conectar a um PostgreSQL em `localhost`.

Os integrantes que têm acesso ao repositório recebem as mesmas configurações e compartilham os dados desse banco. Como as credenciais estão nos arquivos versionados, restrinja o acesso ao repositório e não as copie para mensagens ou outros locais públicos. O projeto não carrega arquivos `.env` automaticamente.

O Spring aplica `src/main/resources/schema.sql` ao iniciar. Em seguida, uma migração automática converte os campos antigos de data e hora. A aplicação armazena instantes com fuso e exibe datas e horários no fuso comercial de Fortaleza. Antes de atualizar uma base com dados importantes, faça um backup.

## Estoque, perecíveis e sincronização

Em **Cadastrar produto**, marque a validade como obrigatória para os perecíveis. Em **Compras**, informe o lote e a validade ao receber a mercadoria. A tela **Estoque** apresenta o total físico, o saldo disponível para venda e o inventário por lote. Produtos vencidos ou com validade obrigatória pendente ficam bloqueados para venda; use **Informar validade** somente após conferir a data real na embalagem.

**Produtos** e **Estoque** atualizam os saldos após movimentações confirmadas e fazem uma consulta de segurança a cada 15 segundos enquanto a página estiver visível. Os filtros são preservados. Ao recuperar a conexão ou voltar à página, o sistema consulta os dados novamente. O horário da última consulta aparece na listagem de produtos.

Na conferência de estoque físico, abra uma contagem, registre todos os produtos (inclusive quantidade zero) e revise antes de aplicar os ajustes. O sistema bloqueia a aprovação quando o estoque muda durante a contagem; nesse caso, abra uma nova contagem.

## Compliance: contratos, aprovações e equipe

Abra **Ferramentas → Compliance**. O primeiro usuário de cada empresa administra a equipe; na atualização de empresas antigas, o primeiro usuário com perfil padrão assume essa função se ainda não houver administrador. Em **Equipe e perfis**, o administrador cadastra colaboradores da mesma empresa e distribui os papéis de Comprador, Gerente de Compras, Diretor, Compliance e Financeiro. Cada colaborador define suas perguntas de segurança no primeiro login.

- **Mercadorias abaixo de R$ 10.000,00:** entram no estoque ao finalizar a compra e geram uma conta a pagar.
- **Compras a partir de R$ 10.000,00:** aguardam aprovação de nível 1 (Gerente de Compras, Diretor ou Administrador) e nível 2 (outro Diretor, Compliance ou Administrador). O solicitante não aprova. Os lotes, a validade e o preço ficam registrados no pedido; estoque e conta a pagar são efetivados uma única vez após a aprovação final.
- **Serviços:** selecione esse tipo em Compras, informe nota fiscal, descrição, valor e contrato aprovado do mesmo fornecedor. O sistema confere vigência e valor acumulado do contrato. Serviços não geram estoque.
- **Contratos acima de R$ 50.000,00:** geram um registro de risco e precisam de homologação por outra pessoa habilitada. O documento é referenciado por link HTTP/HTTPS; o ERP não hospeda o arquivo.
- **Dados bancários:** solicite a alteração com um comprovante em Compliance. Outra pessoa com perfil Administrador, Diretor ou Compliance aprova ou rejeita. Durante a análise, compras e pagamentos do fornecedor ficam suspensos. O cadastro básico de clientes e fornecedores continua disponível; compradores não cadastram nem alteram fornecedores.
- **Pagamentos:** Financeiro, Diretor ou Administrador registra a liquidação após realizá-la fora do ERP. Quem solicitou ou aprovou aquela compra não pode liquidá-la. O ERP não envia transferências bancárias.

O painel reúne os pedidos, contratos, alterações bancárias e registros de risco da empresa. Use **Atualizar painel** para conferir decisões de outros usuários. Títulos de compras antigas que não tinham conta a pagar não são criados retroativamente.

As tabelas e colunas de compliance são adicionadas automaticamente ao iniciar o backend atualizado. Após atualizar o código Java, reinicie o Spring Boot; o banco já existente é preservado.

## Usar o assistente de IA (opcional)

Instale o [Ollama](https://ollama.com/download) no computador que executa o backend e baixe o modelo uma vez:

```powershell
ollama pull gemma3:4b
```

O padrão é `gemma3:4b` em `http://localhost:11434`. No Windows, o ERP pode iniciar a instalação padrão do Ollama quando a IA for usada. Para outros sistemas ou instalações diferentes, mantenha o Ollama em execução. O assistente exige plano PLUS. Veja o [guia da IA](IA-ASSISTENTE-README.md).

## Acessar de outros computadores da rede

O servidor escuta na porta `8080` em todas as interfaces de rede. Em uma rede privada, os outros computadores podem abrir `http://IP-DO-COMPUTADOR-DO-SERVIDOR:8080/login.html`, se o firewall permitir. Isso não configura acesso seguro pela internet; para publicar externamente, configure HTTPS e restrinja o acesso.

## Compilar e verificar

```powershell
mvn clean verify
```

Os testes usam um PostgreSQL temporário separado da base compartilhada. Para gerar e iniciar o JAR:

```powershell
mvn clean package
java -jar target/adm2026-1.0.0.jar
```

Execute o segundo comando a partir da raiz do projeto para que o Spring encontre a configuração compartilhada.

## Guias de uso

- [POS: vendas, pagamentos e estoque](POS-README.md)
- [Assistente IA: instalação e exemplos](IA-ASSISTENTE-README.md)
- [Backend, autenticação e rotas da API](README-SPRING.md)
- [Estoque, perecíveis e sincronização](#estoque-perecíveis-e-sincronização)
- [Compliance: contratos, aprovações e equipe](#compliance-contratos-aprovações-e-equipe)

## Limites atuais

Pix e cartão são confirmados pelo operador na maquininha ou no banco; não há cobrança integrada. Os comprovantes não são documentos fiscais e o sistema não emite NFC-e.
