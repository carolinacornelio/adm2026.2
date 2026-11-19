import http from 'k6/http';
import { check, sleep } from 'k6';

// Configuração do teste de carga com rampa de usuários virtuais (VUs)
export const options = {
  stages: [
    { duration: '15s', target: 20 },  // Rampa de aquecimento: sobe até 20 usuários
    { duration: '30s', target: 50 },  // Carga constante: 50 usuários simultâneos
    { duration: '15s', target: 100 }, // Pico de estresse: 100 usuários simultâneos
    { duration: '15s', target: 0 },   // Desaceleração
  ],
  thresholds: {
    http_req_duration: ['p(95)<2000'], // 95% das requisições devem responder em menos de 2s
    http_req_failed: ['rate<0.05'],    // Taxa de erro inferior a 5%
  },
};

const BASE_URL = __ENV.ERP_URL || 'http://localhost:8080';

// Considera 401/403 como esperados quando o teste roda sem autenticação
http.setResponseCallback(http.expectedStatuses(200, 401, 403));

export default function () {
  // 1. Acesso à página inicial / login (estático)
  const resLoginHtml = http.get(`${BASE_URL}/login.html`);
  check(resLoginHtml, {
    'login.html status 200': (r) => r.status === 200,
  });

  // 2. Consulta à API de categorias (ou produtos se autenticado)
  const resCategorias = http.get(`${BASE_URL}/api/categorias`, {
    headers: { 'Accept': 'application/json' },
  });
  check(resCategorias, {
    'status esperado (200 ou 401/403)': (r) => r.status === 200 || r.status === 401 || r.status === 403,
  });

  sleep(0.5);
}
