# velora-android

Aplicación Android nativa de **Velora**, plataforma personal de analítica de ciclismo (ruta y MTB).

> Estado: Fase 6, entrega 2 (inicio de sesión). La especificación está en `../docs/07-android-application.md` del kit.

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
| `core:session` | Login, renovación de tokens y cierre de sesión; el refresh token, cifrado con el Keystore (ADR-022) |

Las siguientes entregas agregan `core:database` (Room), `core:model` y los módulos `feature:*`.

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

Para probar en el emulador contra la API local, el servidor del emulador hacia la computadora es `http://10.0.2.2:8090`.
Solo la versión de desarrollo acepta `http://`; la publicable exige HTTPS (ADR-022).

## Licencia

Apache-2.0.
