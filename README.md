# Shade

Panel de música físico: un móvil Android conectado por USB muestra y controla
lo que suena en el PC (Spotify, navegador, cualquier app que reporte a SMTC).

## Arquitectura

```
PC (agente C#) ── lee SMTC ──> WebSocket en localhost:8080
                                        ▲
                            adb reverse tcp:8080 tcp:8080
                                        │
Móvil (app Compose) ──────────> ws://127.0.0.1:8080
```

## Fase 1 (actual)

Solo el agente (`src/Shade.Agent`):

- Detecta la sesión SMTC activa y publica `track` / `state` por WebSocket en `:8080`.
- Sirve la carátula por HTTP en `/art/{hash}`.
- Acepta comandos `playPause` / `next` / `prev`.
- Ejecuta y vigila `adb reverse tcp:8080 tcp:8080` (necesita el móvil conectado con depuración USB).

No incluye todavía: visualizador/FFT, volumen, seek, ni la app Android.

## Ejecutar el agente

```
dotnet run --project src/Shade.Agent
```

Requiere el SDK de .NET 8. La ruta a `adb.exe` se configura en
`src/Shade.Agent/appsettings.json` (`Adb:Path`).

## Probar sin el móvil

Abre `test-client/index.html` directamente en el navegador (doble clic, sin
servidor) con el agente corriendo. Se conecta a `ws://127.0.0.1:8080`, muestra
la sesión activa y tiene botones de play/pausa/siguiente/anterior.
