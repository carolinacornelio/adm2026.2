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

## Banco de dados e credenciais

O projeto já inclui `application-local.properties` e `db.properties`, versionados no Git por decisão da equipe. O Spring Boot lê o primeiro arquivo na raiz do projeto; a implementação antiga de terminal lê o segundo. As configurações atuais apontam para o PostgreSQL compartilhado. Não é preciso instalar um banco local quando esse servidor estiver acessível.

Inicie o Spring Boot a partir da pasta que contém `pom.xml` e `application-local.properties`. Se o arquivo não for encontrado, a configuração padrão tentará conectar a um PostgreSQL em `localhost`.

Os integrantes que têm acesso ao repositório recebem as mesmas configurações e compartilham os dados desse banco. Como as credenciais estão nos arquivos versionados, restrinja o acesso ao repositório e não as copie para mensagens ou outros locais públicos. O projeto não carrega arquivos `.env` automaticamente.

O Spring aplica `src/main/resources/schema.sql` ao iniciar. Antes de atualizar uma base com dados importantes, faça um backup.

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
- [Inventário físico: contagem e aprovação](docs/integracao/INVENTARIO-FISICO.md)

## Histórico e materiais da equipe

- [Entrega acadêmica do inventário de lotes](docs/inventario/RESUMO.md) · [relatório detalhado](docs/inventario/ENTREGA.md)
- [Histórico da integração das novas funções](docs/integracao/ANALISE-E-INTEGRACAO.md)
- [Auditoria técnica](docs/integracao/AUDITORIA-DO-PROJETO.md)

## Limites atuais

Pix e cartão são confirmados pelo operador na maquininha ou no banco; não há cobrança integrada. Os comprovantes não são documentos fiscais e o sistema não emite NFC-e.
