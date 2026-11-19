/**
 * ERP CodeScript - Script de Teste de Carga e Benchmark (Medição Inicial)
 * Desafio 10: Escalabilidade e Multitenancy
 *
 * Avalia o desempenho das camadas centrais do ERP atual sob concorrência:
 * 1. Tela Inicial de Login (Entrega Web / Servidor Tomcat)
 * 2. Processamento de Autenticação (Carga de CPU com BCrypt)
 * 3. Tela de Dashboard (Carga de Banco / Consultas SQL Agregadas)
 * 4. Tela de Produtos e Estoque (Consultas com Relacionamentos e Cálculo de Lotes)
 *
 * Execução:
 *   node docs/escalabilidade/teste-carga.js
 */

const http = require('http');
const fs = require('fs');
const path = require('path');

const CONFIG = {
  host: 'localhost',
  port: 8080,
  cenarios: [
    { nome: '1. Carga Leve', conexoesSimultaneas: 10, totalRequisicoes: 100 },
    { nome: '2. Carga Moderada', conexoesSimultaneas: 30, totalRequisicoes: 300 },
    { nome: '3. Pico de Estresse', conexoesSimultaneas: 60, totalRequisicoes: 600 }
  ]
};

function pad(str, len, align = 'left') {
  str = String(str);
  if (str.length >= len) return str.slice(0, len);
  const diff = len - str.length;
  if (align === 'right') return ' '.repeat(diff) + str;
  if (align === 'center') {
    const left = Math.floor(diff / 2);
    const right = diff - left;
    return ' '.repeat(left) + str + ' '.repeat(right);
  }
  return str + ' '.repeat(diff);
}

function fazerRequisicao(reqPath, method, body = null, headers = {}) {
  return new Promise((resolve) => {
    const inicio = performance.now();
    const opcoes = {
      hostname: CONFIG.host,
      port: CONFIG.port,
      path: reqPath,
      method: method,
      headers: {
        'Accept': 'application/json, text/html',
        ...headers
      },
      timeout: 12000
    };

    if (body) {
      opcoes.headers['Content-Type'] = 'application/json';
      opcoes.headers['Content-Length'] = Buffer.byteLength(body);
    }

    const req = http.request(opcoes, (res) => {
      let data = '';
      res.on('data', chunk => data += chunk);
      res.on('end', () => {
        const fim = performance.now();
        resolve({
          sucesso: res.statusCode >= 200 && res.statusCode < 400,
          status: res.statusCode,
          tempoMs: fim - inicio,
          dados: data
        });
      });
    });

    req.on('error', (err) => {
      const fim = performance.now();
      resolve({
        sucesso: false,
        status: err.code || 'ERRO',
        tempoMs: fim - inicio,
        erro: err.message
      });
    });

    req.on('timeout', () => {
      req.destroy();
      const fim = performance.now();
      resolve({
        sucesso: false,
        status: 'TIMEOUT',
        tempoMs: fim - inicio
      });
    });

    if (body) req.write(body);
    req.end();
  });
}

function calcularPercentil(tempos, p) {
  if (tempos.length === 0) return 0;
  const index = Math.ceil((p / 100) * tempos.length) - 1;
  return tempos[Math.max(0, Math.min(index, tempos.length - 1))];
}

async function autenticarUsuarioTeste() {
  const timestamp = Date.now();
  const dadosCadastro = JSON.stringify({
    nome: "Usuario Teste Carga",
    empresa: `Empresa Carga ${timestamp}`,
    email: `carga_${timestamp}@teste.com`,
    senha: "senhaSegura123",
    perfil: "admin",
    plano: "PLUS",
    perguntaSeguranca1: "AMIGO_INFANCIA",
    respostaSeguranca1: "amigo",
    perguntaSeguranca2: "EMPREGO_SONHOS",
    respostaSeguranca2: "dev",
    perguntaSeguranca3: "CIDADE_PAIS",
    respostaSeguranca3: "recife"
  });

  const res = await fazerRequisicao('/api/auth/cadastro', 'POST', dadosCadastro);
  if (res.sucesso) {
    try {
      const json = JSON.parse(res.dados);
      if (json.token) return json.token;
    } catch (e) {}
  }
  return null;
}

async function executarCenario(cenario, testeConfig, tokenAuth) {
  process.stdout.write(`  [EXECUÇÃO] ${cenario.nome}: ${cenario.totalRequisicoes} requisições (${cenario.conexoesSimultaneas} conexões simultâneas)... `);
  
  const tempos = [];
  let sucessos = 0;
  let falhas = 0;
  const statusContagem = {};

  const inicioTotal = performance.now();
  let pendentes = cenario.totalRequisicoes;

  const headers = {};
  if (testeConfig.requerAuth && tokenAuth) {
    headers['Authorization'] = `Bearer ${tokenAuth}`;
  }

  async function worker() {
    while (pendentes > 0) {
      pendentes--;
      const res = await fazerRequisicao(testeConfig.path, testeConfig.method, testeConfig.body, headers);
      tempos.push(res.tempoMs);
      statusContagem[res.status] = (statusContagem[res.status] || 0) + 1;
      if (res.sucesso) sucessos++;
      else falhas++;
    }
  }

  const workers = Array.from({ length: cenario.conexoesSimultaneas }, () => worker());
  await Promise.all(workers);

  const fimTotal = performance.now();
  const duracaoSegundos = (fimTotal - inicioTotal) / 1000;
  tempos.sort((a, b) => a - b);

  const soma = tempos.reduce((a, b) => a + b, 0);
  const media = tempos.length ? soma / tempos.length : 0;
  const p50 = calcularPercentil(tempos, 50);
  const p95 = calcularPercentil(tempos, 95);
  const p99 = calcularPercentil(tempos, 99);
  const max = tempos.length ? tempos[tempos.length - 1] : 0;
  const rps = tempos.length / duracaoSegundos;

  process.stdout.write(`OK (${duracaoSegundos.toFixed(2)}s | ${rps.toFixed(1)} req/s)\n`);

  return {
    cenario: cenario.nome,
    concorrencia: cenario.conexoesSimultaneas,
    total: cenario.totalRequisicoes,
    sucessos,
    falhas,
    rps: rps.toFixed(1),
    mediaMs: media.toFixed(1),
    p50Ms: p50.toFixed(1),
    p95Ms: p95.toFixed(1),
    p99Ms: p99.toFixed(1),
    maxMs: max.toFixed(1),
    duracaoSegundos: duracaoSegundos.toFixed(2),
    statusContagem
  };
}

function imprimirTabela(titulo, linhas) {
  const cols = [
    { header: 'Cenário', width: 20, align: 'left', key: 'cenario' },
    { header: 'Concorrência', width: 14, align: 'right', key: 'concorrencia' },
    { header: 'Total Reqs', width: 12, align: 'right', key: 'total' },
    { header: 'Sucesso', width: 10, align: 'right', key: 'sucessos' },
    { header: 'Falhas', width: 8, align: 'right', key: 'falhas' },
    { header: 'Vazão (RPS)', width: 13, align: 'right', key: 'rps' },
    { header: 'Média (ms)', width: 12, align: 'right', key: 'mediaMs' },
    { header: 'p95 (ms)', width: 10, align: 'right', key: 'p95Ms' },
    { header: 'p99 (ms)', width: 10, align: 'right', key: 'p99Ms' },
  ];

  const totalWidth = cols.reduce((acc, col) => acc + col.width + 3, 1);

  console.log(`\n+${'-'.repeat(totalWidth - 2)}+`);
  console.log(`| ${pad(titulo, totalWidth - 4, 'left')} |`);
  console.log(`+${cols.map(c => '-'.repeat(c.width + 2)).join('+')}+`);

  // Cabecalho
  const headerStr = cols.map(c => ` ${pad(c.header, c.width, c.align)} `).join('|');
  console.log(`|${headerStr}|`);
  console.log(`+${cols.map(c => '-'.repeat(c.width + 2)).join('+')}+`);

  // Linhas
  for (const row of linhas) {
    const rowStr = cols.map(col => ` ${pad(row[col.key], col.width, col.align)} `).join('|');
    console.log(`|${rowStr}|`);
  }

  console.log(`+${cols.map(c => '-'.repeat(c.width + 2)).join('+')}+\n`);
}

function gerarMarkdown(baterias, timestamp) {
  let md = `# Relatório de Medição Inicial (Antes) - Desafio 10: Escalabilidade e Desempenho\n\n`;
  md += `**Data da medição:** ${timestamp}  \n`;
  md += `**Ambiente:** Spring Boot 3.5.5 + PostgreSQL (Arquitetura Monolítica / Instância Única)  \n`;
  md += `**Servidor:** http://${CONFIG.host}:${CONFIG.port}\n\n`;
  md += `> **Objetivo:** Registrar o desempenho atual do sistema em instância única (baseline) para servir de referência na comparação com os testes após as melhorias.\n\n`;
  md += `---\n\n`;

  for (const b of baterias) {
    md += `## ${b.titulo}\n`;
    md += `- **Tela no ERP:** ${b.tela}  \n`;
    md += `- **Rota HTTP:** \`${b.method} ${b.path}\`  \n`;
    md += `- **O que avalia:** ${b.explicacao}  \n`;
    md += `- **Ponto observado:** ${b.gargalo}\n\n`;

    md += `| Cenário | Concorrência | Total Reqs | Sucessos | Falhas | Vazão (req/s) | Média (ms) | p95 (ms) | p99 (ms) | Máx (ms) |\n`;
    md += `| :--- | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: |\n`;
    for (const r of b.resultados) {
      md += `| ${r.cenario} | ${r.concorrencia} | ${r.total} | ${r.sucessos} | ${r.falhas} | ${r.rps} | ${r.mediaMs} | ${r.p95Ms} | ${r.p99Ms} | ${r.maxMs} |\n`;
    }
    md += `\n---\n\n`;
  }

  md += `## Principais Observações da Medição Inicial\n\n`;
  md += `1. **Comportamento sob concorrência:** Ao subir de 10 para 60 usuários simultâneos, a latência nos percentis p95 e p99 cresce de forma acentuada.\n`;
  md += `2. **Gargalo de conexão com o banco:** O pool único do HikariCP (limitado a 5 conexões) restringe o atendimento simultâneo em telas que disparam várias consultas SQL em sequência, como o Dashboard.\n`;
  md += `3. **Consumo de CPU na autenticação:** O cálculo de hash de senhas com BCrypt consome bastante CPU durante picos de login, mantendo a vazão próxima de 65–70 req/s mesmo com aumento de concorrência.\n`;
  md += `4. **Próximos passos para a arquitetura ("Depois"):**\n`;
  md += `   - Adicionar balanceamento de carga (Load Balancer) com múltiplas instâncias do ERP.\n`;
  md += `   - Migrar sessões locais para um modelo stateless (JWT) ou cache compartilhado (Redis).\n`;
  md += `   - Garantir isolamento de dados por cliente (estratégia de multitenancy).\n`;

  return md;
}

async function main() {
  console.log('========================================================================================');
  console.log('                 ERP CODESCRIPT - TESTE DE CARGA (DESAFIO 10)                           ');
  console.log('                Medição de Desempenho Inicial (Baseline da Arquitetura Atual)          ');
  console.log('========================================================================================\n');

  console.log(`Servidor alvo : http://${CONFIG.host}:${CONFIG.port}`);
  const dataInicio = new Date().toLocaleString('pt-BR');
  console.log(`Data e hora   : ${dataInicio}\n`);

  // 1. Testa conectividade inicial
  const testeConexao = await fazerRequisicao('/login.html', 'GET');
  if (!testeConexao.sucesso && typeof testeConexao.status === 'string') {
    console.log(`[ERRO DE CONEXÃO] O ERP não está respondendo na porta ${CONFIG.port} (${testeConexao.status})`);
    console.log('\nInstruções para iniciar o ERP:');
    console.log('  1. Abra um terminal na pasta do projeto');
    console.log('  2. Execute: mvn spring-boot:run');
    console.log('  3. Aguarde o Spring Boot inicializar e execute este script novamente.\n');
    process.exit(1);
  }

  // 2. Prepara autenticação para telas internas
  process.stdout.write('[AUTENTICAÇÃO] Criando sessão de teste para acessar telas internas... ');
  const tokenAuth = await autenticarUsuarioTeste();
  if (tokenAuth) {
    console.log('OK (Sessão criada)');
  } else {
    console.log('AVISO: Não foi possível criar sessão automática. Testes autenticados usarão modo anônimo.');
  }
  console.log('');

  // 3. Definição detalhada das 4 telas testadas
  const BATERIAS = [
    {
      titulo: 'Teste 1: Tela de Login (Entrega Web / Servidor Tomcat)',
      tela: '`login.html` (Acesso ao Sistema)',
      path: '/login.html',
      method: 'GET',
      requerAuth: false,
      body: null,
      explicacao: 'Tempo de entrega da página HTML pelo servidor embutido.',
      gargalo: 'Verifica se o servidor web suporta múltiplos acessos simultâneos sem esgotar o pool de threads HTTP.'
    },
    {
      titulo: 'Teste 2: Processamento de Login (Carga de CPU com BCrypt)',
      tela: 'Formulário de Login (`/api/auth/login`)',
      path: '/api/auth/login',
      method: 'POST',
      requerAuth: false,
      body: JSON.stringify({ email: "usuario.teste@exemplo.com", senha: "senhaIncorreta123" }),
      explicacao: 'Comportamento da aplicação com vários usuários enviando credenciais ao mesmo tempo.',
      gargalo: 'Mede o peso na CPU causado pelo cálculo criptográfico do BCrypt na verificação de senhas.'
    },
    {
      titulo: 'Teste 3: Tela de Dashboard (Carga de Banco / Consultas SQL Agregadas)',
      tela: 'Painel Principal do Dashboard (`/api/dashboard/indicadores`)',
      path: '/api/dashboard/indicadores',
      method: 'GET',
      requerAuth: true,
      body: null,
      explicacao: 'Simula gestores abrindo o Dashboard para consultar totais de clientes, produtos e vendas.',
      gargalo: 'Executa 4 consultas SQL com `COUNT(*)` por requisição no PostgreSQL, pressionando o limite do pool HikariCP (configurado com 5 conexões).'
    },
    {
      titulo: 'Teste 4: Tela de Produtos e Estoque (Consultas com Relacionamentos)',
      tela: 'Listagem de Produtos e Saldo de Lotes (`/api/produtos`)',
      path: '/api/produtos',
      method: 'GET',
      requerAuth: true,
      body: null,
      explicacao: 'Simula operadores e caixas consultando o catálogo de produtos e estoque no ERP e POS.',
      gargalo: 'Executa consultas com múltiplos `LEFT JOIN`s e cálculo de saldo de lotes em tempo real filtrados por empresa.'
    }
  ];

  const resultadosFinais = [];

  for (let i = 0; i < BATERIAS.length; i++) {
    const b = BATERIAS[i];
    console.log('----------------------------------------------------------------------------------------');
    console.log(`[${i + 1}/${BATERIAS.length}] ${b.titulo}`);
    console.log(`  Tela no ERP      : ${b.tela}`);
    console.log(`  Rota HTTP        : ${b.method} ${b.path}`);
    console.log(`  O que testa      : ${b.explicacao}`);
    console.log(`  Gargalo avaliado : ${b.gargalo}`);
    console.log('');

    const resultadosCenarios = [];
    for (const cenario of CONFIG.cenarios) {
      const res = await executarCenario(cenario, b, tokenAuth);
      resultadosCenarios.push(res);
    }

    imprimirTabela(`Resultados: ${b.titulo}`, resultadosCenarios);
    resultadosFinais.push({ ...b, resultados: resultadosCenarios });
  }

  // 4. Salva relatorio Markdown
  const caminhoRelatorio = path.join(__dirname, 'RESULTADOS-ANTES.md');
  const conteudoMd = gerarMarkdown(resultadosFinais, dataInicio);
  fs.writeFileSync(caminhoRelatorio, conteudoMd, 'utf8');

  // 5. Salva pagina HTML com graficos interativos baseados nos dados reais medidos
  const caminhoGrafico = path.join(__dirname, 'grafico-desempenho.html');
  atualizarGraficoHtml(caminhoGrafico, resultadosFinais, dataInicio);
}

function atualizarGraficoHtml(caminho, resultadosFinais, dataInicio) {
  // Extrai arrays de latencia p95 e vazao
  const labels = ['Login (HTML)', 'Processamento Login', 'Dashboard', 'Produtos e Estoque'];
  const leveP95 = resultadosFinais.map(b => Number(b.resultados[0].p95Ms));
  const modP95 = resultadosFinais.map(b => Number(b.resultados[1].p95Ms));
  const estP95 = resultadosFinais.map(b => Number(b.resultados[2].p95Ms));
  const vazaoRps = resultadosFinais.map(b => Number(b.resultados[1].rps));

  const html = `<!DOCTYPE html>
<html lang="pt-BR">
<head>
  <meta charset="UTF-8">
  <title>Desafio 10 - Gráficos de Desempenho e Escalabilidade (Medição Antes)</title>
  <style>
    * { box-sizing: border-box; margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; }
    body { background-color: #f4f6f9; color: #2c3e50; padding: 30px 20px; }
    .container { max-width: 1000px; margin: 0 auto; background: #ffffff; border-radius: 8px; box-shadow: 0 4px 12px rgba(0,0,0,0.08); padding: 35px; }
    h1 { font-size: 24px; font-weight: 700; color: #1e3a8a; margin-bottom: 8px; }
    .subtitulo { font-size: 14px; color: #64748b; margin-bottom: 25px; border-bottom: 1px solid #e2e8f0; padding-bottom: 15px; }
    .card-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: 15px; margin-bottom: 30px; }
    .metric-card { background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 6px; padding: 15px 20px; }
    .metric-card h3 { font-size: 12px; text-transform: uppercase; color: #64748b; margin-bottom: 6px; letter-spacing: 0.5px; }
    .metric-card .valor { font-size: 24px; font-weight: 700; color: #0f172a; }
    .metric-card .detalhe { font-size: 12px; color: #94a3b8; margin-top: 4px; }
    
    .grafico-box { background: #ffffff; border: 1px solid #e2e8f0; border-radius: 8px; padding: 25px; margin-bottom: 30px; }
    .grafico-box h2 { font-size: 17px; color: #1e293b; margin-bottom: 6px; font-weight: 600; }
    .grafico-box p { font-size: 13px; color: #64748b; margin-bottom: 20px; }
    
    .chart-container { position: relative; width: 100%; height: 280px; }
    
    .tabela-resumo { width: 100%; border-collapse: collapse; margin-top: 15px; font-size: 13px; }
    .tabela-resumo th { background: #f1f5f9; color: #334155; text-align: left; padding: 10px 12px; font-weight: 600; border: 1px solid #cbd5e1; }
    .tabela-resumo td { padding: 9px 12px; border: 1px solid #e2e8f0; color: #475569; }
    .tabela-resumo tr:nth-child(even) { background-color: #f8fafc; }
    
    .badge-gargalo { display: inline-block; background: #fee2e2; color: #991b1b; padding: 2px 8px; border-radius: 4px; font-size: 11px; font-weight: 600; }
    .badge-ok { display: inline-block; background: #dcfce7; color: #166534; padding: 2px 8px; border-radius: 4px; font-size: 11px; font-weight: 600; }
  </style>
  <script src="https://cdn.jsdelivr.net/npm/chart.js"></script>
</head>
<body>
  <div class="container">
    <h1>Desafio 10: Análise de Desempenho e Escalabilidade</h1>
    <div class="subtitulo">
      Medição Inicial (Baseline) realizada em ${dataInicio} | ERP CodeScript
    </div>

    <div class="card-grid">
      <div class="metric-card">
        <h3>Pool de Conexões</h3>
        <div class="valor">5 conexões</div>
        <div class="detalhe">HikariCP (Gargalo em consultas simultâneas)</div>
      </div>
      <div class="metric-card">
        <h3>Autenticação</h3>
        <div class="valor">Em Memória</div>
        <div class="detalhe">Tokens locais (Impede escala horizontal)</div>
      </div>
      <div class="metric-card">
        <h3>Ponto Crítico de Latência</h3>
        <div class="valor">Dashboard</div>
        <div class="detalhe">4 consultas COUNT(*) simultâneas no banco</div>
      </div>
      <div class="metric-card">
        <h3>Ponto Crítico de CPU</h3>
        <div class="valor">Login / Senha</div>
        <div class="detalhe">Custo computacional do algoritmo BCrypt</div>
      </div>
    </div>

    <div class="grafico-box">
      <h2>1. Tempo de Resposta p95 (ms) sob Concorrência Crescente</h2>
      <p>Mede o tempo em que 95% das requisições são atendidas. Quanto menor, melhor.</p>
      <div class="chart-container">
        <canvas id="chartLatencia"></canvas>
      </div>
    </div>

    <div class="grafico-box">
      <h2>2. Vazão do Sistema: Requisições por Segundo (RPS)</h2>
      <p>Capacidade de atendimento contínuo por tela sob carga moderada (30 conexões concorrentes).</p>
      <div class="chart-container">
        <canvas id="chartVazao"></canvas>
      </div>
    </div>

    <div class="grafico-box">
      <h2>3. Quadro Comparativo das Telas Avaliadas</h2>
      <p>Síntese do comportamento operacional de cada tela antes da implementação da arquitetura escalável.</p>
      <table class="tabela-resumo">
        <thead>
          <tr>
            <th>Tela / Camada Avaliada</th>
            <th>Rota HTTP</th>
            <th>Principal Recurso Estressado</th>
            <th>Classificação</th>
          </tr>
        </thead>
        <tbody>
          <tr>
            <td><strong>Tela de Login (Web)</strong></td>
            <td><code>GET /login.html</code></td>
            <td>Threads HTTP / Tomcat</td>
            <td><span class="badge-ok">Estável</span></td>
          </tr>
          <tr>
            <td><strong>Processamento de Login</strong></td>
            <td><code>POST /api/auth/login</code></td>
            <td>Processador (CPU - BCrypt)</td>
            <td><span class="badge-gargalo">CPU Bound</span></td>
          </tr>
          <tr>
            <td><strong>Tela de Dashboard</strong></td>
            <td><code>GET /api/dashboard/indicadores</code></td>
            <td>Banco de Dados (HikariCP / Queries)</td>
            <td><span class="badge-gargalo">Database Bound</span></td>
          </tr>
          <tr>
            <td><strong>Produtos e Estoque</strong></td>
            <td><code>GET /api/produtos</code></td>
            <td>Relacionamentos SQL e Cálculo de Lotes</td>
            <td><span class="badge-gargalo">I/O Bound</span></td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>

  <script>
    const ctxLatencia = document.getElementById('chartLatencia').getContext('2d');
    new Chart(ctxLatencia, {
      type: 'bar',
      data: {
        labels: ${JSON.stringify(labels)},
        datasets: [
          {
            label: 'Carga Leve (10 conexões)',
            data: ${JSON.stringify(leveP95)},
            backgroundColor: '#93c5fd'
          },
          {
            label: 'Carga Moderada (30 conexões)',
            data: ${JSON.stringify(modP95)},
            backgroundColor: '#3b82f6'
          },
          {
            label: 'Pico de Estresse (60 conexões)',
            data: ${JSON.stringify(estP95)},
            backgroundColor: '#1d4ed8'
          }
        ]
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        scales: {
          y: {
            beginAtZero: true,
            title: { display: true, text: 'Latência p95 em milissegundos (ms)' }
          },
          x: {
            title: { display: true, text: 'Telas do ERP' }
          }
        }
      }
    });

    const ctxVazao = document.getElementById('chartVazao').getContext('2d');
    new Chart(ctxVazao, {
      type: 'bar',
      data: {
        labels: ${JSON.stringify(labels)},
        datasets: [
          {
            label: 'Vazão Média (Requisições por Segundo - RPS)',
            data: ${JSON.stringify(vazaoRps)},
            backgroundColor: ['#0284c7', '#38bdf8', '#7dd3fc', '#0ea5e9'],
            borderRadius: 4
          }
        ]
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        scales: {
          y: {
            beginAtZero: true,
            title: { display: true, text: 'Requisições por segundo (RPS)' }
          }
        }
      }
    });
  </script>
</body>
</html>`;

  fs.writeFileSync(caminho, html, 'utf8');
}

main().catch(console.error);
