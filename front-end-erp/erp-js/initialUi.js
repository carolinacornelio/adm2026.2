// Carregado antes do CSS para fixar o estado inicial da sessão e do plano.
(() => {
    const raiz = document.documentElement;
    const rota = location.pathname.split('/').pop();
    function ler(chave) {
        try { return JSON.parse(sessionStorage.getItem(chave) || 'null'); }
        catch { return null; }
    }

    const usuario = ler('usuario');
    try { raiz.dataset.uiSessao = usuario && sessionStorage.getItem('authToken') ? 'ativa' : 'ausente'; }
    catch { raiz.dataset.uiSessao = 'ausente'; }
    const cache = ler('permissoesUsuarioV2');
    const cacheDaSessao = cache?.usuario && cache.usuario.id === usuario?.id
        && cache.usuario.empresaId === usuario?.empresaId;
    const inicial = cacheDaSessao ? cache : usuario?.proprietario ? { proprietario: true } : null;
    const plano = String(usuario?.plano || '').trim().toUpperCase();
    raiz.dataset.planoInicial = ['PREMIUM', 'CORPORATIVO'].includes(plano) ? 'PREMIUM'
        : ['PLUS', 'INTERMEDIARIO', 'INTERMEDIÁRIO', 'EMPRESARIAL'].includes(plano) ? 'PLUS' : 'BASE';
    raiz.dataset.uiPermissoes = inicial ? 'conhecidas' : 'pendentes';

    // A navegação e o acesso rápido compartilham os mesmos módulos e permissões.
    const grupos = [
        ['cadastros', 'Cadastros', [
            ['pessoas-listagem.html', 'Pessoas', 'people.view', 'users', 'Consulte clientes e fornecedores'],
            ['pessoas-cadastro.html', 'Cadastrar pessoa', 'people.manage', 'user-plus', 'Cadastre um cliente ou fornecedor'],
            ['produtos-listagem.html', 'Produtos', 'products.view', 'package', 'Consulte produtos e saldos'],
            ['produtos-cadastro.html', 'Cadastrar produto', 'products.create', 'package-plus', 'Adicione um produto ao catálogo'],
            ['categorias.html', 'Categorias', 'products.view', 'folder', 'Organize o catálogo de produtos', 'PLUS'],
        ]],
        ['operacao', 'Operação', [
            ['compras.html', 'Compras', 'purchases.view', 'cart-in', 'Registre entradas de fornecedores'],
            ['vendas.html', 'Vendas', 'sales.view', 'cart-out', 'Registre saídas de produtos'],
            ['historico-vendas.html', 'Histórico de vendas', 'sales.view', 'history', 'Consulte vendas do ERP e do POS'],
            ['estoque.html', 'Estoque', 'stock.view', 'boxes', 'Acompanhe saldos, lotes e validade'],
        ]],
        ['ferramentas', 'Ferramentas', [
            ['ia.html', 'Assistente IA', 'ai.use', 'sparkles', 'Analise dados do ERP', 'PLUS'],
            ['atendimento.html', 'Central de atendimento', 'support.use', 'headset', 'Abra e acompanhe chamados'],
        ]],
        ['administracao', 'Administração', [
            ['empresas.html', 'Empresa', 'company.view', 'building', 'Consulte dados da empresa'],
            ['funcionarios.html', 'Funcionários', 'users.view', 'users', 'Gerencie contas de funcionários'],
            ['perfis-acesso.html', 'Perfis de acesso', 'users.view', 'shield', 'Configure permissões'],
            ['squads.html', 'Squads', 'squads.view', 'network', 'Organize as equipes'],
            ['okrs.html', 'OKRs', 'okrs.view', 'target', 'Acompanhe objetivos e resultados'],
            ['financeiro.html', 'Financeiro', 'finance.view', 'wallet', 'Consulte contas a pagar e a receber'],
        ]],
    ];
    const rotaPai = { 'squad-detalhes.html': 'squads.html', 'objetivo-detalhes.html': 'okrs.html' };
    const imagensDashboard = {
        'pessoas-listagem.html': 'pessoas.png',
        'pessoas-cadastro.html': 'cadastrar-pessoa.png',
        'produtos-listagem.html': 'produtos.png',
        'produtos-cadastro.html': 'cadastrar-produto.png',
        'categorias.html': 'categorias.png',
        'compras.html': 'compras.png',
        'vendas.html': 'vendas.png',
        'historico-vendas.html': 'historico-vendas.png',
        'estoque.html': 'estoque.png',
        'ia.html': 'assistente-ia.png',
        'atendimento.html': 'atendimento.png',
        'empresas.html': 'empresa.png',
        'funcionarios.html': 'funcionarios.png',
        'perfis-acesso.html': 'perfis-acesso.png',
        'squads.html': 'squads.png',
        'okrs.html': 'okrs.png',
        'financeiro.html': 'financeiro.png',
    };
    const icone = (nome, classe = 'erp-icone') => `<svg class="${classe}" aria-hidden="true" focusable="false"><use href="/assets/erp-icons.svg#${nome}"></use></svg>`;
    const indiceDashboard = Object.keys(imagensDashboard);
    const permite = (info, permissao) => Boolean(info?.proprietario || info?.permissoes?.includes(permissao));
    function classeAtiva(pagina) {
        if (rota === pagina) return ' class="ativo" aria-current="page"';
        return rotaPai[rota] === pagina ? ' class="ativo" aria-current="location"' : '';
    }

    function renderizar(info = inicial) {
        const nome = document.querySelector('.usuario-nome');
        if (nome) nome.textContent = usuario?.nome || '';
        const atalho = document.querySelector('.atalho-atendimento');
        if (atalho) {
            atalho.hidden = !permite(info, 'support.use');
            if (rota === 'atendimento.html') atalho.setAttribute('aria-current', 'page');
        }
        const menu = document.querySelector('[data-menu-erp]');
        const rapido = document.getElementById('atalhosDashboard');
        const modulos = grupos.map(([id, titulo, itens]) => [id, titulo, itens.filter(([, , permissao, , , minimo]) =>
            permite(info, permissao) && (!minimo || raiz.dataset.planoInicial !== 'BASE'))]
        ).filter(([, , itens]) => itens.length);
        const pos = permite(info, 'pos.use');
        const assinatura = JSON.stringify([pos, modulos.map(([, , itens]) => itens.map(([pagina]) => pagina))]);
        if (menu && menu.dataset.modulos !== assinatura) {
            const anteriores = new Map([...menu.querySelectorAll('[data-pasta]')].map((p) => [p.dataset.pasta, p.open]));
            menu.innerHTML = `<a href="/dashboard.html"${classeAtiva('dashboard.html')}>Dashboard</a>
              ${pos ? `<a href="/pos.html" data-troca-sistema="true"${classeAtiva('pos.html')}>POS</a>` : ''}
              ${menu.dataset.menuErp === 'pos' ? '' : modulos.map(([id, titulo, itens]) => {
                  const aberta = anteriores.get(id) ?? itens.some(([pagina]) => pagina === rota || pagina === rotaPai[rota]);
                  return `<details class="menu-pasta" data-pasta="${id}"${aberta ? ' open' : ''}>
                    <summary>${icone('folder', 'menu-pasta-icone')}<span>${titulo}</span><span class="menu-pasta-seta" aria-hidden="true"></span></summary>
                    <div class="menu-pasta-itens">${itens.map(([pagina, texto]) => `<a href="/${pagina}"${classeAtiva(pagina)}>${texto}</a>`).join('')}</div>
                  </details>`;
              }).join('')}`;
            menu.dataset.modulos = assinatura;
        }
        if (rapido && rapido.dataset.modulos !== assinatura) {
            rapido.innerHTML = modulos.map(([id, titulo, itens]) => `<section class="dashboard-grupo" aria-labelledby="atalhos-${id}">
              <h3 id="atalhos-${id}">${titulo}</h3><div class="dashboard-atalhos-grade">${itens.map(([pagina, texto, , desenho, descricao]) =>
                  `<a class="dashboard-atalho" href="/${pagina}"><span class="dashboard-atalho-imagem" aria-hidden="true" style="--icone-x: ${(indiceDashboard.indexOf(pagina) % 5) * 25}%; --icone-y: ${Math.floor(indiceDashboard.indexOf(pagina) / 5) * 100 / 3}%"></span>
                    <span><strong>${texto}</strong><small>${descricao}</small></span></a>`).join('')}</div>
            </section>`).join('');
            rapido.closest('section').hidden = !modulos.length;
            rapido.dataset.modulos = assinatura;
        }
    }

    window.erpUi = { renderizar };
})();
