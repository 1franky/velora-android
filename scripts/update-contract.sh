#!/usr/bin/env bash
# Copia el contrato OpenAPI de velora-api fijado a un commit (ADR-011): el cliente se genera de esta copia, así el
# build no depende de la red y un cambio de contrato es un cambio explícito y revisable en este repositorio.
#   scripts/update-contract.sh <sha>     fija el commit y descarga el contrato
#   scripts/update-contract.sh --check   comprueba que la copia corresponde al commit fijado
set -euo pipefail
cd "$(dirname "$0")/.."
REPO="1franky/velora-api"
if [[ "${1:-}" == "--check" ]]; then
  ref="$(tr -d '[:space:]' < contract/REF)"
  diff -q <(curl -fsSL "https://raw.githubusercontent.com/$REPO/$ref/openapi/openapi-v1.yaml") contract/openapi-v1.yaml \
    >/dev/null && echo "Contrato al día con velora-api@$ref." || { echo "contract/openapi-v1.yaml no coincide con velora-api@$ref"; exit 1; }
  exit 0
fi
ref="${1:?Uso: scripts/update-contract.sh <sha de velora-api>}"
curl -fsSL "https://raw.githubusercontent.com/$REPO/$ref/openapi/openapi-v1.yaml" -o contract/openapi-v1.yaml
echo "$ref" > contract/REF
echo "Contrato copiado de velora-api@$ref."
