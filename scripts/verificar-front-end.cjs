const fs = require('node:fs');
const path = require('node:path');
const root = path.resolve(__dirname, '..');
const frontend = path.join(root, 'front-end-erp');
const statics = path.join(root, 'src', 'main', 'resources', 'static');
const pages = ['categorias.html', 'compras.html', 'vendas.html'];
let failures = 0;
function fail(message) { console.error(`FALHA: ${message}`); failures++; }
for (const page of pages) {
  const canonical = path.join(frontend, page);
  const served = path.join(statics, page);
  if (!fs.existsSync(canonical)) fail(`página canônica ausente: ${page}`);
  if (!fs.existsSync(served)) fail(`página Spring static ausente: ${page}`);
  if (fs.existsSync(canonical) && fs.existsSync(served) && !fs.readFileSync(canonical).equals(fs.readFileSync(served))) {
    fail(`front-end-erp e static divergentes: ${page}`);
  }
  if (fs.existsSync(canonical)) {
    const html = fs.readFileSync(canonical, 'utf8');
    if (!html.includes('modulos.css?v=20261118-modulos-v2')) fail(`${page} não usa o CSS v2`);
    if (html.includes('href="./') || html.includes('src="./')) fail(`${page} ainda possui recurso relativo à pasta`);
    for (const id of page === 'categorias.html'
      ? ['formCategoria', 'nomeCategoria', 'listaCategorias']
      : page === 'compras.html'
        ? ['formCompra', 'corpoItensCompra', 'btnFinalizarCompra']
        : ['formVenda', 'corpoItensVenda', 'btnFinalizarVenda']) {
      if (!html.includes(`id="${id}"`)) fail(`${page} perdeu o ID funcional ${id}`);
    }
  }
}
const pom = fs.readFileSync(path.join(root, 'pom.xml'), 'utf8');
if (!pom.includes('<directory>front-end-erp</directory>') || !pom.includes('<targetPath>static</targetPath>')) {
  fail('pom.xml não copia a origem canônica para classpath:/static');
}
if (failures) process.exit(1);
console.log('OK: Categorias, Compras e Vendas usam o front-end v2 e estão sincronizadas com Spring static.');
