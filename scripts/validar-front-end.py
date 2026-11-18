#!/usr/bin/env python3
"""Valida caminhos locais do front-end e sincronização com o espelho de desenvolvimento."""
from html.parser import HTMLParser
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
STATIC = ROOT / "src/main/resources/static"
MIRROR = ROOT / "front-end-erp"

class References(HTMLParser):
    def __init__(self):
        super().__init__()
        self.items = []

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        for key in ("href", "src", "action"):
            value = attrs.get(key)
            if value:
                self.items.append((key, value))

errors = []
html_pages = sorted(STATIC.glob("*.html"))
for page in html_pages:
    parser = References()
    parser.feed(page.read_text(encoding="utf-8"))
    for attr, value in parser.items:
        if value.startswith(("http://", "https://", "mailto:", "tel:", "data:", "#", "javascript:")):
            continue
        if not value.startswith("/"):
            errors.append(f"{page.name}: {attr} relativo encontrado: {value}")
            continue
        local_path = value[1:].split("?", 1)[0].split("#", 1)[0]
        if local_path and not (STATIC / local_path).is_file():
            errors.append(f"{page.name}: {attr} aponta para arquivo inexistente: {value}")

for file in sorted(MIRROR.rglob("*")):
    if file.is_file():
        mirror = STATIC / file.relative_to(MIRROR)
        if not mirror.is_file() or mirror.read_bytes() != file.read_bytes():
            errors.append(f"espelho diferente: {file.relative_to(MIRROR)}")

for file in sorted(STATIC.rglob("*")):
    if file.is_file() and not (MIRROR / file.relative_to(STATIC)).is_file():
        errors.append(f"arquivo sem espelho: {file.relative_to(STATIC)}")

for required in (
    "dashboard.html", "pessoas-listagem.html", "pessoas-cadastro.html",
    "produtos-listagem.html", "produtos-cadastro.html", "categorias.html",
    "compras.html", "vendas.html", "historico-vendas.html", "estoque.html",
):
    if not (STATIC / required).is_file():
        errors.append(f"tela de navegação ausente: {required}")

if errors:
    print("FALHA NA VALIDAÇÃO DO FRONT-END:")
    print("\n".join(f"- {error}" for error in errors))
    sys.exit(1)

print(f"OK: {len(html_pages)} páginas HTML verificadas")
print("OK: todos os links e assets locais são absolutos e existem")
print("OK: front-end-erp e src/main/resources/static estão idênticos")
print("OK: telas de Cadastros e Operação presentes")
