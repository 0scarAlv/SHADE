# Shade

Panel de música físico: un móvil Android muestra y controla lo que suena en
el PC (Spotify, navegador, cualquier app que reporte a SMTC).

## Arquitectura

```
PC (agente C#) ── lee SMTC ──> WebSocket en localhost:8080 (USB, vía adb reverse)
                └──────────── RFCOMM Bluetooth Classic (sin cable)

Móvil (app Compose) ──> ws://127.0.0.1:8080  ó  socket Bluetooth emparejado
```

El agente sirve un único protocolo de mensajes (track/state/lyrics/spectrum,
comandos playPause/next/prev/volumeUp/volumeDown/seek) sobre dos transportes
intercambiables: WebSocket por USB (para desarrollo) o Bluetooth RFCOMM
(uso normal, sin depuración USB). La app elige el transporte desde la
pantalla de Conexión.

## Estado actual

Agente (`src/Shade.Agent`) y app Android (`android/`) completos y probados
de punta a punta en un dispositivo real:

- Detecta la sesión SMTC activa y publica track/estado.
- Sirve la carátula (HTTP para WebSocket, frame binario para Bluetooth).
- Letras sincronizadas vía LRCLIB, con overlay de letra sobre la carátula
  desenfocada (doble tap en la carátula para mostrar/ocultar).
- Visualizador de espectro en tiempo real (NAudio WASAPI loopback) que
  también sirve de barra de progreso/seek.
- Control de volumen del sistema, mantener pantalla encendida.
- Transporte Bluetooth RFCOMM además del WebSocket por USB.

No incluido todavía: ver el roadmap hacia 1.0 (memoria del proyecto).

## Ejecutar el agente (desarrollo)

```
dotnet run --project src/Shade.Agent
```

En una consola interactiva el agente abre un panel estilo lazygit
(`Dashboard/ConsoleDashboard.cs`): lo que suena con espectro en vivo,
estado de USB/Bluetooth y clientes, recursos del PC y el registro.
Atajos: `espacio` play/pausa, `n`/`p` siguiente/anterior, `←`/`→` ±10 s,
`+`/`-` volumen, `m` silenciar, `s` aleatorio, `r` repetir, `tab`/`1-4`
cambiar de panel, `q` salir. En el panel de registro: `↑↓` desplazar,
`l` filtrar por nivel, `c` limpiar. En Conexión: `a` revisa adb ya.

`--plain` (o una consola redirigida) vuelve al log clásico línea a línea.
`--dashboard-snapshot <archivo>` escribe un frame del panel en texto y
sale (útil para revisar el layout sin terminal).

Requiere el SDK de .NET 8. La ruta a `adb.exe` se configura en
`src/Shade.Agent/appsettings.json` (`Adb:Path`).

## Compilar la app Android

```
cd android
./gradlew.bat :app:installRelease   # firma release (keystore.properties)
```

Necesita `android/local.properties` con `sdk.dir` (gitignored) y, para
compilar en modo release, `android/keystore.properties` con el keystore de
firma (gitignored).

## Empaquetar una distribución

```
dotnet publish src/Shade.Agent -c Release -r win-x64 --self-contained true -o dist/agent
"<ruta a Inno Setup 6>\ISCC.exe" installer/ServerShade.iss
```

Genera `dist/ServerShadeSetup.exe` (instalador del agente, sin permisos de
administrador) a partir de `dist/agent`. La versión se define en
`installer/ServerShade.iss` (`MyAppVersion`) y debe mantenerse en sync con
`android/app/build.gradle.kts` (`versionName`).

## Probar sin el móvil

Abre `test-client/index.html` directamente en el navegador (doble clic, sin
servidor) con el agente corriendo. Se conecta a `ws://127.0.0.1:8080` y
tiene los mismos controles que la app.
