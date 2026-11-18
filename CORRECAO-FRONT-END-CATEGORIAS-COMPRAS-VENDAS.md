# Correção do front-end: Categorias, Compras e Vendas

## O que mudou

- `front-end-erp/` passa a ser a origem canônica das páginas, CSS, JavaScript e assets do front-end.
- O Maven copia o conteúdo de `front-end-erp/` para `classpath:/static` depois dos recursos de `src/main/resources`, de modo que as páginas novas prevaleçam no JAR.
- `src/main/resources/static/` foi sincronizada com a origem canônica.
- As telas de Categorias, Compras e Vendas receberam cabeçalhos, formulários, tabelas, estados e espaçamentos próprios do novo padrão visual, mantendo os IDs usados pelos módulos JavaScript.
- Os links para essas três telas usam rotas absolutas e identificador de versão para evitar reutilização do HTML antigo em cache.
- URLs antigas como `/front-end-erp/compras.html`, `/cadastros/categorias.html` e `/operacoes/vendas.html` são redirecionadas para as rotas oficiais.
- `spring.web.resources.cache.cachecontrol.no-store=true` evita reutilização de recursos estáticos antigos durante a validação.

## Como aplicar no Windows

1. Extraia o ZIP completo em uma pasta nova (não misture com a pasta da versão anterior).
2. Crie `application-local.properties` na mesma pasta do `pom.xml` e configure ali a URL, usuário e senha do Neon. Não publique esse arquivo no Git.
3. Encerre processos antigos do ERP e feche o Live Server/servidor da porta 5500, caso esteja sendo usado.
4. Abra o PowerShell na pasta do `pom.xml` e execute:

   ```powershell
   mvn clean spring-boot:run
   ```

5. Espere o log indicar `Started ErpApplication` e acesse somente `http://localhost:8080/dashboard.html`.
6. Faça recarga forçada no navegador com `Ctrl+F5`.

> Se o Spring Boot não chegar a `Started ErpApplication`, a versão nova não estará sendo servida. O erro de conexão SSL do Neon reportado anteriormente precisa ser resolvido na configuração local antes da validação visual.

## Verificação disponível

Na raiz do projeto:

```powershell
node .\scripts\verificar-front-end.cjs
```

O script confere a sincronização de Categorias, Compras e Vendas entre `front-end-erp/` e `src/main/resources/static`, o uso do CSS atualizado e a preservação dos IDs funcionais.

A compilação Maven não foi executada neste ambiente porque Maven não está instalado.
