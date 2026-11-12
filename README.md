# velora-android

Aplicación Android nativa de **Velora**, plataforma personal de analítica de ciclismo (ruta y MTB).

> Estado: Fase 6 completa (inicio de sesión, Inicio, historial, detalle con mapa, importación y sincronización). Última versión publicada: v0.1.0. La especificación está en `../docs/07-android-application.md` del kit.

## Stack

- Kotlin + Jetpack Compose + Material 3, tema propio de Velora (cempasúchil sobre niebla o noche, sin color dinámico).
- **Android 12 (API 31) o posterior.** Se compila contra API 37 y apunta a API 37.
- Cliente HTTP **generado del contrato OpenAPI** de `velora-api` (ADR-011) con openapi-generator (Ktor +
  kotlinx.serialization) en cada build: si el contrato cambia y el código no, no compila.
- Textos solo en `res/values/strings.xml` (es-MX).

## Módulos

| Módulo | Qué contiene |
|---|---|
| `app` | Actividad, navegación y pantallas |
| `core:designsystem` | `VeloraTheme` y colores (los mismos tokens que la web) |
| `core:network` | Cliente generado (`mx.velora.android.api`) y `VeloraApi`, que lo configura para un servidor |
| `core:database` | Caché local con Room; el esquema exportado está en `core/database/schemas` (ADR-023) |
| `core:data` | Repositorios: combinan la API (fuente de verdad) con la caché |
| `core:session` | Login, renovación de tokens y cierre de sesión; el refresh token, cifrado con el Keystore (ADR-022) |

Las pantallas viven en `app` por paquete (`home`, `activities`, `detail`, `map`, `imports`, `sync`, `login`, `settings`); se separarán en módulos `feature:*` cuando crezcan.

## Contrato

`contract/openapi-v1.yaml` es una copia del contrato de `velora-api` fijada al commit de `contract/REF`. Así el build
no depende de la red y un cambio de contrato es un cambio explícito en este repositorio.

```bash
scripts/update-contract.sh <sha-de-velora-api>   # fija otro commit y descarga su contrato
scripts/update-contract.sh --check               # comprueba la copia (lo corre el CI)
```

## Desarrollo

Requiere JDK 21 o posterior y el SDK de Android (`local.properties` con `sdk.dir`).

```bash
./gradlew assembleDebug        # APK de depuración
./gradlew testDebugUnitTest    # pruebas (JVM y Robolectric)
./gradlew lintDebug            # lint: las advertencias fallan el build
./gradlew :core:session:connectedDebugAndroidTest   # cifrado con el Keystore real (emulador encendido)
```

La app arranca apuntando al servidor desplegado, `https://velora-api.franciscolopez.uk`, y se puede cambiar en el login.
Para probar en el emulador contra la API local, el servidor del emulador hacia la computadora es `http://10.0.2.2:8090`.
Solo la versión de desarrollo acepta `http://`; la publicable exige HTTPS (ADR-022).

## Release

`.github/workflows/release.yml` publica la app como en `nexora-android`. Cada tag `vX.Y.Z` corre las pruebas y el
lint, compila y firma el APK y lo publica como [Release de GitHub](https://github.com/1franky/velora-android/releases)
(`velora-android-vX.Y.Z.apk`). El tag debe coincidir con `versionName` de `app/build.gradle.kts`.

Para cerrar una versión:

```bash
# 1. Sube versionCode y versionName en app/build.gradle.kts (PR e integración a main)
git tag v0.2.0 && git push origin v0.2.0
```

La llave de firma vive en GitHub Secrets (`RELEASE_KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`,
`KEY_PASSWORD`), nunca en el repositorio. `scripts/configurar-secretos-release.sh` los sube a partir de
`keystore.properties`, que también sirve para compilar `assembleRelease` en local (ver `keystore.properties.example`).
**Guarda una copia de la llave y sus contraseñas fuera de esta computadora:** sin ellas, Android no acepta una versión
nueva sobre la ya instalada.

## Licencia

Apache-2.0.
