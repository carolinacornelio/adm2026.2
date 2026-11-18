# Refatoração visual do ERP - 18/11/2026

## O que mudou

- Atualização da camada visual compartilhada em `src/main/resources/static/css/tela.css`, preservando IDs, classes e seletores consumidos pelos módulos JavaScript.
- Refinamento da navegação lateral, cabeçalho, espaçamento, tipografia, cards, formulários, botões, tabelas e breakpoints responsivos.
- Reorganização visual dos cards de Acesso Rápido com área de ícone em tamanho fixo, fundo de contraste e alinhamento uniforme.
- Redimensionamento dos dez PNGs do Acesso Rápido para no máximo 640 px no maior lado, mantendo transparência e formato PNG.
- Inclusão de fallback SVG individual para cada ação quando uma imagem não puder ser carregada ou decodificada.
- Cache-busting nos links de CSS das páginas HTML e no módulo `dashboard.js`, para evitar que o navegador continue usando os estilos antigos.
- Espelhamento das mesmas alterações em `front-end-erp/`; o Spring Boot publica `src/main/resources/static/` como fonte única.

## Validações

- Verificar sintaxe JavaScript com `node --check` nos módulos alterados.
- Verificar a integridade dos PNGs e os caminhos referenciados pelo dashboard.
- A compilação Maven deve ser executada no ambiente de desenvolvimento do projeto, onde o Maven e as dependências do projeto estejam disponíveis.
