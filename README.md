# FakeLocation Repro v1

This directory contains the first runnable Phase 1 reproduction of the FakeLocation "代理系统定位服务" architecture.

## Modules

- `app`
  - Control app shell, runtime dashboard, foreground services, and dependency graph.
- `core-model`
  - Shared state models and process-role enums.
- `core-ipc`
  - In-memory service registry, mock managers, and state store contracts.
- `core-runtime`
  - `/data/fl`-style staged runtime layout, manifest generation, placeholder artifact staging, and hidden API controller.
- `core-hookbridge`
  - Hook bridge abstraction with native and compatibility placeholders plus install history.
- `injector-orchestrator`
  - Injection plan modeling, runtime warm-up, and dry-run task generation.
- `payload-shared`
  - Shared Java payload entrypoints for `init` and `appHook` stages.

## Current scope

The project now implements a runnable Phase 1 foundation focused on:

1. Module boundaries
2. Process-role modeling
3. Injection-stage modeling
4. Shared state contracts
5. Hook bridge abstraction points
6. `/data/fl`-style staged runtime layout generation
7. Runtime manifest and placeholder artifact staging
8. Injection dry-run task generation for `system_server` and `com.android.phone`
9. In-app runtime dashboard for session state, staged artifacts, payload reports, and injection commands

It does **not** yet implement:

1. Real system process injection
2. Real ART hook installation
3. Real Binder registration into `ServiceManager`
4. Full hidden API exemption compatibility handling across ROMs
5. Real runtime payload loading into target processes
6. Real root shell execution and SELinux coordination

## Build

The project includes a Gradle wrapper and has been validated with:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_SDK_ROOT='C:\Users\xmg\AppData\Local\Android\Sdk'
$env:ANDROID_HOME='C:\Users\xmg\AppData\Local\Android\Sdk'
.\gradlew.bat :app:assembleDebug
```

The debug APK output path is:

`app/build/outputs/apk/debug/app-debug.apk`

## Runtime staging

`core-runtime` currently generates placeholder artifacts aligned to the reversed runtime names:

- `payload/2da3c574.s`
- `native/libfl_init64.so`
- `native/libfl_app64.so`
- `native/liblh64.so`
- `bin/inj64`
- `metadata/hook-registry-seed.txt`

The runtime dashboard shows:

- staged artifact paths and SHA-256 digests
- runtime manifest file path
- registered local services
- mock session state
- payload activation reports
- hook install history
- per-process dry-run injection commands

## Next steps

The next meaningful implementation step is connecting staged artifacts to a real root execution layer, then replacing placeholder loaders and hook bridge artifacts with working native components.
