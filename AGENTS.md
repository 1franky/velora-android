# Instrucciones para agentes — velora-android

Repositorio de la aplicación Android (Kotlin + Jetpack Compose).

## Reglas específicas

- Consume la API únicamente según `openapi-v1.yaml`; no inventes endpoints ni campos.
- Todos los textos de interfaz en `strings.xml` (`es-MX`); nada hardcodeado en composables.
- Separa UI, dominio y datos; caché y sincronización tolerantes a desconexión.
- No guardes tokens fuera de almacenamiento seguro ni los registres en logs.

## Reglas comunes (resumen de `velora-project-kit/AGENTS.md`)

- Lee la especificación en `velora-project-kit` (`README.md`, `AGENTS.md`, `docs/`) antes de cambiar código.
- No inventes requisitos, endpoints, campos ni dependencias; el contrato HTTP es `contracts/openapi-v1.yaml`.
- Todo texto visible al usuario en español de México (`es-MX`), mediante el sistema de i18n; nunca hardcodeado.
- Zona horaria inicial `America/Mexico_City`, configurable. Fechas visibles `dd/MM/yyyy`; instantes en UTC.
- Unidades por defecto: km, m, km/h, °C.
- No registres tokens, contraseñas, archivos FIT/GPX/TCX ni coordenadas privadas en logs.
- No incluyas secretos en código, fixtures ni capturas. Este repositorio es **público**.
- El conector Strava permanece deshabilitado por defecto; ver `docs/09-strava-legal-and-data-governance.md`.
- Cambios pequeños, tipados y con pruebas. No afirmes que una prueba pasó si no la ejecutaste.
- Nunca hagas `git push` a ramas protegidas, publiques secretos ni despliegues sin autorización explícita.
