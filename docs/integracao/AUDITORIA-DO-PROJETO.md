# Auditoria técnica do ERP

**Registro da revisão:** 23/10/2026. A revisão cobriu autenticação, cadastros, compras, vendas, POS, estoque, validade, inventários, Atendimento, IA, interface e documentação.

## Correções principais

- **Acesso:** sessões expiram após 12 horas; sair revoga o token no servidor. O segundo fator permite corrigir uma resposta até três tentativas e expira em cinco minutos.
- **Cadastros e API:** validações de campos, identificadores, preços e parâmetros agora devolvem erros controlados. Respostas antigas de pesquisa não substituem resultados mais recentes.
- **Estoque e vendas:** movimentações conferem limites e empresa, usam transações e bloqueios em ordem consistente. Saldos sem lote não recebem validade presumida.
- **Inventário físico:** a aprovação rejeita contagens incompletas ou saldos alterados e reverte a operação inteira em caso de falha.
- **Interface e conexões:** formulários aceitam envio por Enter; o CSV do estoque tem cabeçalhos alinhados; eventos em tempo real tratam reconexões e conexões inativas.
- **Datas:** mês e data comerciais usam o fuso `America/Fortaleza`.

## Organização dos arquivos

- A imagem antiga e sem uso `front-end-erp/img-assistente-ia.png` foi removida. As dez imagens usadas no dashboard ficam em `front-end-erp/assets/dashboard/`.
- O SQL de demonstração que estava em `teste.txt` foi preservado em `docs/exemplos/dados-demonstracao.sql`.
- `src/com/erp/`, `lib/postgresql-42.7.3.jar` e os scripts SQL antigos foram mantidos para a implementação de terminal e seus exemplos. O ERP web usa Maven, Spring Boot e o driver definido em `pom.xml`.
- Diretórios de compilação, classes e logs permanecem fora do Git.

## Configuração compartilhada

Por decisão da equipe, `application-local.properties` e `db.properties` continuam versionados e não são ignorados pelo `.gitignore`. O Spring Boot lê o primeiro na raiz do projeto; o segundo pertence à implementação antiga de terminal. Os valores não são reproduzidos nesta documentação. Inicie o Spring Boot na raiz para que o caminho relativo da configuração seja encontrado.

Quem pode ler o repositório também pode ler as credenciais e acessar a base compartilhada. Mantenha o repositório restrito às pessoas autorizadas.

## Última validação registrada

Antes da atualização desta documentação, `mvn clean verify` concluiu com **57 testes aprovados**, sem falhas ou testes ignorados, e gerou `target/adm2026-1.0.0.jar`. Os testes usaram PostgreSQL temporário, separado da base compartilhada.

Os roteiros de navegador `src/test/browser/auditoria.cjs` e `src/test/browser/novos-modulos.cjs` também passaram. A checagem anterior encontrou 32 módulos JavaScript sem erros de sintaxe, referências locais das páginas presentes e dez imagens do dashboard no JAR.

O ajuste posterior para manter a barra superior fixa durante a rolagem foi feito em CSS e ainda não foi incluído nessa validação automatizada. A validação da IA usa Ollama simulado; não mede a qualidade das respostas do modelo real.
