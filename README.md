# ERP + POS — início rápido

ERP web com estoque, compras, vendas e IA local. Interface e API rodam juntas na porta **8080**.

## 1. Instale as dependências

| Dependência | Como instalar no Windows |
| --- | --- |
| [Java JDK 21](https://adoptium.net/temurin/releases/?version=21) | Execute o instalador; habilite JAVA_HOME e inclusão no Path. |
| [Maven 3.9+](https://maven.apache.org/download.cgi) | Baixe o ZIP binário, extraia e adicione a pasta `bin` ao Path. |
| [PostgreSQL 14+](https://www.postgresql.org/download/windows/) | Instale servidor e pgAdmin. Guarde a senha e mantenha a porta 5432. |
| [Ollama](https://ollama.com/download/windows) | Opcional: necessário apenas para a IA, no computador do backend. |

Abra um **novo terminal** e confira `java -version` e `mvn -v`: ambos devem usar Java 21.
Não precisa instalar Spring Boot, Node.js, Go ou Docker. O Maven baixa as bibliotecas automaticamente.

## 2. Baixe e configure

Baixe a branch desejada pelo GitHub (ZIP) ou clone com Git:

```powershell
git clone --branch NOME-DA-BRANCH URL-DO-REPOSITORIO
```

Abra a pasta baixada, onde está `pom.xml`. No pgAdmin, conecte ao banco `postgres` e execute **cada comando separadamente**, escolhendo sua senha:

```sql
CREATE ROLE erp_dev WITH LOGIN PASSWORD 'SUA_SENHA';
CREATE DATABASE adm_erp OWNER erp_dev;
```

Crie `application-local.properties` na raiz do projeto:

```properties
spring.datasource.url=jdbc:postgresql://localhost:5432/adm_erp
spring.datasource.username=erp_dev
spring.datasource.password=SUA_SENHA
```

Esse arquivo é local e ignorado pelo Git. Cada integrante configura o seu. Para banco remoto, use a URL JDBC e SSL do provedor. As tabelas são criadas/atualizadas automaticamente ao iniciar; faça backup antes de atualizar uma base importante.

## 3. Inicie o ERP

Com PostgreSQL ligado, abra o terminal na pasta do `pom.xml`:

```powershell
mvn spring-boot:run
```

Abra **[localhost:8080/login.html](http://localhost:8080/login.html)**, cadastre sua empresa ou faça login e escolha ERP ou POS.
Para parar: **Ctrl+C**. Para reiniciar: execute o mesmo comando. Após alterações Java, reinicie o backend.

## 4. Ative a IA (opcional)

Com o Ollama instalado, execute uma vez:

```powershell
ollama pull gemma3:4b
```

Mantenha o Ollama aberto e acesse **Ferramentas → Assistente IA** (plano PLUS). O ERP usa `gemma3:4b` em `localhost:11434`; não exige chave de API. A primeira pergunta pode demorar mais para carregar o modelo.

## Problemas comuns

| Problema | O que conferir |
| --- | --- |
| `mvn` ou `java` não encontrado | Instalação e Path; reabra o terminal. |
| Erro ao conectar ao banco | Serviço PostgreSQL, banco, usuário e senha no arquivo local. |
| Porta 8080 ocupada | Encerre a instância anterior do ERP. |
| IA indisponível | Confira `ollama list`; deve aparecer `gemma3:4b`. Veja o [guia da IA](IA-ASSISTENTE-README.md). |

## Equipe e outros comandos

- **Servidor compartilhado:** dependências ficam no servidor; colegas acessam `http://IP-DO-SERVIDOR:8080/login.html`. Autorize a porta 8080 no firewall da rede privada.
- **Execução local por integrante:** todos seguem os passos acima. Compartilhar código não compartilha o banco nem instala o modelo.
- **Gerar JAR:** `mvn clean package`, depois `java -jar target/adm2026-1.0.0.jar`.
- **Testes:** `mvn test` (banco temporário separado). Reiniciar o ERP encerra as sessões atuais.

## Guias rápidos

- [POS: vender, pagamentos e comprovantes](POS-README.md)
- [IA: exemplos, configuração e limites](IA-ASSISTENTE-README.md)
- [Backend e API](README-SPRING.md) · [Arquitetura da IA](IA-ARQUITETURA-GEMMA.md)
- [Entrega e documentação do inventário](docs/inventario/ENTREGA.md)

**Limites:** Pix/cartão exigem confirmação externa pelo operador; não há cobrança integrada nem emissão fiscal. Para publicar na internet, configure HTTPS, backups e acesso restrito ao banco.
