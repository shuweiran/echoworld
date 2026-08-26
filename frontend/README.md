# Frontend structure

This directory is the active EchoWorld client. It is built with TypeScript, React and Phaser, then copied into Spring Boot static resources for the integrated application.

| Directory | Status | Responsibility |
|---|---|---|
| `src/demo2` | active application shell | navigation, application state and primary user flow; the historical name remains temporarily for import compatibility |
| `src/phaser` | active renderer | 2D world rendering and player input bridge |
| `src/gal` | active presentation | narrative conversation view |
| `src/api`, `src/services`, `src/store` | active client layer | API/SSE adapters and client state |
| `src/components`, `src/styles`, `src/utils`, `src/types` | shared UI | reusable UI, styling, utilities and types |
| `src/social`, `src/assets` | supporting | social/visual resources, not the core simulation runtime |

The client does not own authoritative world state. It renders snapshots and sends user intent; the backend validates movement, perception, conversation membership and context visibility.

`demo2` is not a disposable demo despite its name. A future rename will be done together with import and route migration, rather than by a misleading mechanical directory move.
