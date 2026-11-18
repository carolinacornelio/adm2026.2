# Correção de navegação do front-end — 18/11/2026

## Causa encontrada

O POM excluía `src/main/resources/static/**` e publicava `front-end-erp/` como `static` no JAR. Isso criava duas cópias com papéis diferentes, dependendo do modo de execução. Além disso, links HTML e redirecionamentos JavaScript eram relativos ao diretório atual; quando uma tela era aberta sob um caminho aninhado, a navegação podia continuar nesse caminho e resolver outra árvore de arquivos.

## Correções

- `src/main/resources/static/` passou a ser a única fonte de recursos do Maven/Spring Boot.
- O bloco de recursos personalizado que excluía `static/` e copiava `front-end-erp/` foi removido do `pom.xml`.
- Links internos de páginas e assets passaram a usar caminhos a partir da raiz da aplicação (`/pessoas-cadastro.html`, `/css/tela.css`, `/erp-js/app.js`, etc.).
- Redirecionamentos em módulos JavaScript e links gerados dinamicamente passaram a apontar para a raiz da aplicação.
- `front-end-erp/` foi sincronizado com `src/main/resources/static/` para continuar funcionando como cópia de desenvolvimento.
- A cópia antiga dentro de `src/main/resources/` foi retirada do classpath; o backup histórico continua preservado em `backups/`.

## Validação estática

- `scripts/validar-front-end.py`: aprovado; 25 páginas HTML, links e assets locais, telas de Cadastros/Operação e sincronização das duas cópias.
- `node --check` em todos os 50 módulos JavaScript: aprovado.
- XML do `pom.xml`: válido; configuração não contém mais uma segunda origem de front-end.
- `mvn clean test-compile`: não executado porque Maven não está instalado neste ambiente.
