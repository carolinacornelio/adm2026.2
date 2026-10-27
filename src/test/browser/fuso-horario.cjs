const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { resolve } = require('node:path');
const { execFileSync } = require('node:child_process');

async function verificar() {
    const fonte = readFileSync(resolve(__dirname, '../../../front-end-erp/erp-js/timeUtils.js'), 'utf8');
    const tempo = await import('data:text/javascript;base64,' + Buffer.from(fonte).toString('base64'));
    assert.match(tempo.formatarDataHora('2026-10-24T01:24:00Z'), /23\/10\/2026.*22:24/);
    assert.match(tempo.formatarDataHora('2026-10-23T22:24:00-03:00'), /23\/10\/2026.*22:24/);
    assert.match(tempo.formatarDataHora('2026-10-24T03:00:00Z'), /24\/10\/2026.*00:00/);
    assert.equal(tempo.formatarData('2026-10-23'), '23/10/2026');
    assert.equal(tempo.formatarDataHora(null), '—');

    const DateOriginal = global.Date;
    for (const [agora, esperado] of [
        ['2026-10-24T01:24:00Z', '2026-10-23'],
        ['2026-11-01T01:24:00Z', '2026-10-31'],
        ['2026-10-24T03:00:00Z', '2026-10-24'],
    ]) {
        global.Date = class extends DateOriginal {
            constructor(...args) { super(...(args.length ? args : [agora])); }
        };
        assert.equal(tempo.hojeNoFusoERP(), esperado);
    }
    global.Date = DateOriginal;
}

if (process.argv.includes('--child')) {
    verificar().catch(erro => { console.error(erro); process.exitCode = 1; });
} else {
    for (const TZ of ['UTC', 'America/Fortaleza', 'Asia/Tokyo', 'America/Los_Angeles']) {
        execFileSync(process.execPath, [__filename, '--child'], { env: { ...process.env, TZ }, stdio: 'inherit' });
        console.log(`Datas e horários corretos com computador em ${TZ}`);
    }
}
