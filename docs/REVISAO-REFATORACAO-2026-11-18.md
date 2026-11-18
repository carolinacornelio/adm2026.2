# Revisão e refatoração do ERP — 18/11/2026

## Alterações aplicadas

1. `src/main/resources/static/` passou a ser a cópia canônica integral de `front-end-erp/`, incluindo HTML, `erp-js/`, `css/` e `assets/`.
2. O conteúdo anterior de `src/main/resources/static/` foi preservado em `backups/static-legacy-20261118-0431/` (diretório fora do classpath público).
3. `src/main/resources/static/erp-js/apiService.js`: API padronizada para `${window.location.origin}/api`, removendo a exceção de portas 5500/5501/8765; token lido da sessão (com compatibilidade transitória para localStorage); limpeza de sessão/permissões em resposta 401.
4. `src/main/resources/static/erp-js/dashboard.js`: fallback visual acessível para imagens de Acesso Rápido que falharem.
5. `src/main/resources/static/css/style.css` e `src/main/resources/static/css/tela.css`: camada aditiva de design tokens, foco acessível, estados desabilitados, cards, menu, tabelas e responsividade, sem remover seletores existentes.
6. `src/main/resources/static/` recebeu exatamente os arquivos da versão canônica após as alterações acima.
7. `pom.xml` passou a usar a configuração padrão de `src/main/resources`, sem copiar uma segunda origem para `static/`; links de navegação, assets e redirecionamentos das telas de Cadastros e Operação agora são resolvidos a partir da raiz da aplicação.
8. `permissoesService.js` normaliza o nome final da página antes de consultar o mapa de permissões, preservando o controle visual depois da mudança dos links para `/pagina.html`.
7. `application.properties` já estava configurado para servir `classpath:/static` e não define diretório externo do front-end. `ApiConfig.java` não contém URL fixa para o front-end; mantém CORS para ambientes de demonstração, sem interferir na origem única.

## Auditoria e limitações

- Não foram encontrados `localhost:5500` nem referências `/front-end-erp/` nos scripts e HTMLs da versão canônica.
- O login usa fluxo de segundo fator; após autenticação/cadastro redireciona para `selecionar-sistema.html`.
- O front-end consulta permissões em `/api/auth/me`, esconde itens sem permissão e o interceptor Java mantém autorização no servidor com negação por padrão para rotas sem regra.
- A validação completa de fluxos autenticados e permissões depende de banco de dados e execução da aplicação; não foi possível executar testes de integração neste ambiente.
- `mvn test-compile` não pôde ser executado: Maven não está instalado neste ambiente (`mvn: command not found`). Java 21 está disponível. O projeto não contém `mvnw`/`mvnw.cmd`.
