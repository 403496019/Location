# FakeLocation Repro v1

This directory contains the first skeleton for a high-fidelity reproduction project of the FakeLocation "代理系统定位服务" architecture.

## Modules

- `app`
  - Control app shell, foreground services, and dependency graph.
- `core-model`
  - Shared state models and process-role enums.
- `core-ipc`
  - Binder-bus-facing service names and service contracts.
- `core-runtime`
  - Runtime asset layout and hidden API controller placeholders.
- `core-hookbridge`
  - Hook bridge abstraction with native and compatibility placeholders.
- `injector-orchestrator`
  - Injection plans and stage orchestration skeleton.
- `payload-shared`
  - Shared Java payload entrypoints for `InitStage` and `AppHookStage`.

## Current scope

This is a Phase 1 scaffold only. It intentionally focuses on:

1. Module boundaries
2. Process-role modeling
3. Injection-stage modeling
4. Shared state contracts
5. Hook bridge abstraction points

It does **not** yet implement:

1. Real system process injection
2. Real ART hook installation
3. Real Binder registration into `ServiceManager`
4. Real hidden API exemption logic
5. Real runtime payload loading

## Notes

- The workstation currently does not expose a ready Android SDK / Gradle wrapper setup in this workspace, so this skeleton focuses on source and build-file structure first.
- The next implementation step should be wiring a local Android SDK / Gradle wrapper, then filling `core-runtime`, `injector-orchestrator`, and `payload-shared` with the real Phase 1 flow.
