#!/usr/bin/env bash
# Sube a GitHub Secrets la llave de firma de release a partir de keystore.properties (gitignored), sin imprimir las
# contraseñas. Se corre una sola vez (o al cambiar de llave) desde la raíz del repo, con `gh` autenticado.
set -euo pipefail
repo="1franky/velora-android"
props="keystore.properties"
[ -f "$props" ] || { echo "Falta $props (ver keystore.properties.example)." >&2; exit 1; }
value() { sed -n "s/^$1=//p" "$props"; }
store_file="$(value storeFile)"
[ -f "$store_file" ] || { echo "No existe la llave $store_file." >&2; exit 1; }
base64 < "$store_file" | tr -d '\n' | gh secret set RELEASE_KEYSTORE_BASE64 -R "$repo"
value storePassword | tr -d '\n' | gh secret set KEYSTORE_PASSWORD -R "$repo"
value keyPassword | tr -d '\n' | gh secret set KEY_PASSWORD -R "$repo"
value keyAlias | tr -d '\n' | gh secret set KEY_ALIAS -R "$repo"
gh secret list -R "$repo"
