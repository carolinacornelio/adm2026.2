/** Indicadores oficiais calculados pelo backend. */
import { apiService } from './apiService.js';
import { fromDashboardIndicadoresDTO } from './dto.js';
export async function buscarIndicadores() {
    const dados = await apiService.get('/dashboard/indicadores');
    return fromDashboardIndicadoresDTO(dados);
}
