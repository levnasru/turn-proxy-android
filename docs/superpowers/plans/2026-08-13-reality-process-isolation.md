# Reality Process Isolation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move `RealityVpnService` into its own Android process (`:reality`) so `libgojni.so` (Xray) never coexists in the same process as `libwg-go.so` (WireGuard), which is the confirmed cause of a SIGSEGV crash when switching transport from vk-turn to Reality.

**Architecture:** A one-way `Messenger`/`Binder` bridge carries state updates and log lines from `RealityVpnService` (now in `:reality`) to `ProxyServiceState` in the main process, so the rest of the app (UI, `LocalProxyManager`, notifications) keeps reading the same singleton unchanged. Start/stop of the service stays on the existing `Intent` path — only the status-reporting direction changes.

**Tech Stack:** Kotlin, Android `Messenger`/`Binder` IPC, Koin DI, existing JUnit4 (no Robolectric).

**Spec:** `docs/superpowers/specs/2026-08-13-reality-process-isolation-design.md`

## Global Constraints

- Scope is `RealityVpnService` only — `ProxyService` (core subprocess + WireGuard) stays in the main process, unmodified in this plan.
- `android.os.Bundle` cannot be unit-tested in this project (plain JUnit4, no Robolectric, no `unitTests.isReturnDefaultValues`) — do not write JUnit tests that touch `Bundle`. The real acceptance check is the live-device reproduction in Task 5.
- Start/stop of `RealityVpnService` stays on the existing `Intent` path (`ProxyActions.STOP`, `startForegroundService`) — do not add a second control channel over the new bridge.
- Every existing `ProxyServiceState.*` call inside `RealityVpnService.kt` must be replaced, not duplicated — the service no longer has access to a `ProxyServiceState` that the main process can see.

---

### Task 1: IPC protocol and Bundle mapping

**Files:**
- Create: `app/src/main/java/com/freeturn/app/service/reality/RealityIpc.kt`

**Interfaces:**
- Produces: `object RealityIpc` with `const val MSG_REGISTER_CLIENT = 1`, `MSG_STATE_UPDATE = 2`, `MSG_LOG_LINE = 3`; `data class RealityState(running: Boolean, active: Int, total: Int, failedMessage: String?, tunnelActive: Boolean, connectedSince: Long?, teardownComplete: Boolean)`; `fun RealityState.toBundle(): Bundle`; `fun Bundle.toRealityState(): RealityState`; `fun realityLogBundle(text: String): Bundle`; `fun Bundle.toRealityLogText(): String`. Tasks 2 and 3 both import from this file.

- [ ] **Step 1: Create the file**

```kotlin
package com.freeturn.app.service.reality

import android.os.Bundle

/**
 * IPC-протокол между RealityVpnService (процесс :reality) и основным
 * процессом через Messenger. Старт/стоп сервиса идёт отдельно, через
 * Intent (см. AndroidProxyServiceLauncher) - здесь только поток статуса
 * в обратную сторону, :reality -> основной процесс.
 */
object RealityIpc {
    /** Клиент -> сервис: регистрация, Message.replyTo несёт обратный Messenger. */
    const val MSG_REGISTER_CLIENT = 1
    /** Сервис -> клиент: полный снепшот состояния (и на регистрацию, и на каждое изменение). */
    const val MSG_STATE_UPDATE = 2
    /** Сервис -> клиент: одна строка лога. */
    const val MSG_LOG_LINE = 3
}

data class RealityState(
    val running: Boolean,
    val active: Int,
    val total: Int,
    val failedMessage: String?,
    val tunnelActive: Boolean,
    val connectedSince: Long?,
    val teardownComplete: Boolean
)

private const val KEY_RUNNING = "running"
private const val KEY_ACTIVE = "active"
private const val KEY_TOTAL = "total"
private const val KEY_FAILED_MESSAGE = "failedMessage"
private const val KEY_TUNNEL_ACTIVE = "tunnelActive"
private const val KEY_CONNECTED_SINCE = "connectedSince"
private const val KEY_TEARDOWN_COMPLETE = "teardownComplete"
private const val KEY_LOG_TEXT = "logText"

fun RealityState.toBundle(): Bundle = Bundle().apply {
    putBoolean(KEY_RUNNING, running)
    putInt(KEY_ACTIVE, active)
    putInt(KEY_TOTAL, total)
    putString(KEY_FAILED_MESSAGE, failedMessage)
    putBoolean(KEY_TUNNEL_ACTIVE, tunnelActive)
    connectedSince?.let { putLong(KEY_CONNECTED_SINCE, it) }
    putBoolean(KEY_TEARDOWN_COMPLETE, teardownComplete)
}

fun Bundle.toRealityState(): RealityState = RealityState(
    running = getBoolean(KEY_RUNNING),
    active = getInt(KEY_ACTIVE),
    total = getInt(KEY_TOTAL),
    failedMessage = getString(KEY_FAILED_MESSAGE),
    tunnelActive = getBoolean(KEY_TUNNEL_ACTIVE),
    connectedSince = if (containsKey(KEY_CONNECTED_SINCE)) getLong(KEY_CONNECTED_SINCE) else null,
    teardownComplete = getBoolean(KEY_TEARDOWN_COMPLETE)
)

fun realityLogBundle(text: String): Bundle = Bundle().apply { putString(KEY_LOG_TEXT, text) }

fun Bundle.toRealityLogText(): String = getString(KEY_LOG_TEXT).orEmpty()
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL (no test — see Global Constraints on `Bundle`).

- [ ] **Step 3: Commit**

```bash
cd ~/turn-proxy-android
git add app/src/main/java/com/freeturn/app/service/reality/RealityIpc.kt
git commit -m "feat(reality): add IPC protocol for cross-process state bridge"
```

---

### Task 2: RealityStateSink and onBind in RealityVpnService

**Files:**
- Modify: `app/src/main/java/com/freeturn/app/service/RealityVpnService.kt`

**Interfaces:**
- Consumes: `RealityIpc.MSG_REGISTER_CLIENT/MSG_STATE_UPDATE/MSG_LOG_LINE`, `RealityState`, `RealityState.toBundle()`, `realityLogBundle()` from Task 1.
- Produces: `RealityVpnService.onBind()` returning a `Messenger`-backed `IBinder` — Task 3's `RealityStateBridge` binds to this.

This task replaces every `ProxyServiceState.*` call inside the file with an equivalent call on a new private `RealityStateSink`, and adds the `onBind()` entry point clients connect to.

- [ ] **Step 1: Add imports**

At the top of `RealityVpnService.kt`, replace:

```kotlin
import com.freeturn.app.domain.proxy.ProxyServiceState
```

with:

```kotlin
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import com.freeturn.app.service.reality.RealityIpc
import com.freeturn.app.service.reality.RealityState
import com.freeturn.app.service.reality.realityLogBundle
import com.freeturn.app.service.reality.toBundle
```

(`ParcelFileDescriptor`, `PowerManager`, etc. stay as they are — only the `ProxyServiceState` import is swapped.)

- [ ] **Step 2: Add the sink class at the bottom of the file, after the `RealityVpnService` class closing brace**

```kotlin
/**
 * Заменяет прямые вызовы ProxyServiceState внутри RealityVpnService: этот сервис
 * с этого момента живёт в отдельном процессе (:reality, см. AndroidManifest.xml),
 * у него своя JVM-копия синглтона ProxyServiceState, недоступная основному
 * процессу. Рассылает состояние подключённым клиентам (RealityStateBridge) через
 * Messenger вместо прямой записи в общий объект.
 */
private class RealityStateSink {
    private val clients = mutableListOf<Messenger>()

    private var running = false
    private var active = 0
    private var total = 0
    private var failedMessage: String? = null
    private var tunnelActive = false
    private var connectedSince: Long? = null
    private var teardownComplete = true

    private fun currentState() = RealityState(
        running = running,
        active = active,
        total = total,
        failedMessage = failedMessage,
        tunnelActive = tunnelActive,
        connectedSince = connectedSince,
        teardownComplete = teardownComplete
    )

    /** Новый клиент подключился - сразу шлём полный снепшот, не только будущие изменения. */
    fun registerClient(client: Messenger) {
        clients += client
        sendTo(client, RealityIpc.MSG_STATE_UPDATE, currentState().toBundle())
    }

    fun setRunning(value: Boolean) {
        running = value
        broadcastState()
    }

    fun setStartupResult(result: StartupResult) {
        failedMessage = (result as? StartupResult.Failed)?.message
        broadcastState()
    }

    fun setConnectionStats(stats: ConnectionStats) {
        active = stats.active
        total = stats.total
        broadcastState()
    }

    fun setTunnelActive(value: Boolean) {
        tunnelActive = value
        broadcastState()
    }

    fun markConnectedIfAbsent(nowElapsed: Long) {
        if (connectedSince == null) connectedSince = nowElapsed
        broadcastState()
    }

    fun clearConnectedSince() {
        connectedSince = null
        broadcastState()
    }

    fun markTeardownStarted() {
        teardownComplete = false
        broadcastState()
    }

    fun markTeardownComplete() {
        teardownComplete = true
        broadcastState()
    }

    fun addLog(text: String) = broadcastAll(RealityIpc.MSG_LOG_LINE, realityLogBundle(text))

    private fun broadcastState() = broadcastAll(RealityIpc.MSG_STATE_UPDATE, currentState().toBundle())

    private fun broadcastAll(what: Int, bundle: android.os.Bundle) {
        val dead = mutableListOf<Messenger>()
        for (client in clients) {
            if (!sendTo(client, what, bundle)) dead += client
        }
        clients.removeAll(dead)
    }

    private fun sendTo(client: Messenger, what: Int, bundle: android.os.Bundle): Boolean = try {
        client.send(Message.obtain(null, what).apply { data = bundle })
        true
    } catch (e: RemoteException) {
        false
    }
}
```

- [ ] **Step 3: Add the sink instance, handler, and `onBind` inside the `RealityVpnService` class**

Add right after the existing `private val dialerController = object : DialerController { ... }` block (before `override fun onCreate()`):

```kotlin
    private val stateSink = RealityStateSink()

    private val incomingHandler = Handler(Looper.getMainLooper()) { msg ->
        if (msg.what == RealityIpc.MSG_REGISTER_CLIENT) {
            msg.replyTo?.let { stateSink.registerClient(it) }
        }
        true
    }

    override fun onBind(intent: Intent?): IBinder = Messenger(incomingHandler).binder
```

- [ ] **Step 4: Replace every `ProxyServiceState.*` call site with `stateSink.*`**

Five call sites, same method names and arguments — only the receiver changes from `ProxyServiceState` to `stateSink`:

In `onStartCommand`:
```kotlin
        ProxyServiceState.setRunning(true)
        acquireWakeLock()
        ProxyServiceState.addLog("Reality: запуск")
```
becomes:
```kotlin
        stateSink.setRunning(true)
        acquireWakeLock()
        stateSink.addLog("Reality: запуск")
```

At the end of `startXray()` (success path):
```kotlin
        ProxyServiceState.addLog("Reality: туннель поднят")
        ProxyServiceState.setStartupResult(StartupResult.Success)
        ProxyServiceState.setConnectionStats(ConnectionStats(1, 1))
        ProxyServiceState.setTunnelActive(true)
        ProxyServiceState.markConnectedIfAbsent(SystemClock.elapsedRealtime())
```
becomes:
```kotlin
        stateSink.addLog("Reality: туннель поднят")
        stateSink.setStartupResult(StartupResult.Success)
        stateSink.setConnectionStats(ConnectionStats(1, 1))
        stateSink.setTunnelActive(true)
        stateSink.markConnectedIfAbsent(SystemClock.elapsedRealtime())
```

In `fail(message: String)`:
```kotlin
    private fun fail(message: String) {
        ProxyServiceState.addLog("Reality: $message")
        ProxyServiceState.setStartupResult(StartupResult.Failed(message))
        ProxyServiceState.setRunning(false)
        stopSelf()
    }
```
becomes:
```kotlin
    private fun fail(message: String) {
        stateSink.addLog("Reality: $message")
        stateSink.setStartupResult(StartupResult.Failed(message))
        stateSink.setRunning(false)
        stopSelf()
    }
```

In `teardownTunnel()` (the block after the `tunFd == null` early-return guard):
```kotlin
        ProxyServiceState.markTeardownStarted()
        ProxyServiceState.setRunning(false)
        ProxyServiceState.setConnectionStats(ConnectionStats.IDLE)
        ProxyServiceState.clearConnectedSince()
        ProxyServiceState.addLog("Reality: остановка")
```
becomes:
```kotlin
        stateSink.markTeardownStarted()
        stateSink.setRunning(false)
        stateSink.setConnectionStats(ConnectionStats.IDLE)
        stateSink.clearConnectedSince()
        stateSink.addLog("Reality: остановка")
```

And inside the background `Thread { ... }` at the end of `teardownTunnel()`:
```kotlin
            runCatching { LibXray.invoke(stopRequest.toString()) }
            runCatching { LibXray.resetDNS() }
            ProxyServiceState.markTeardownComplete()
```
becomes:
```kotlin
            runCatching { LibXray.invoke(stopRequest.toString()) }
            runCatching { LibXray.resetDNS() }
            stateSink.markTeardownComplete()
```

Do not leave any `ProxyServiceState.` reference in this file — the import was removed in Step 1, so a leftover reference is a compile error you'll catch in Step 5.

- [ ] **Step 5: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL. If it fails on an unresolved `ProxyServiceState` reference, you missed a call site in Step 4.

- [ ] **Step 6: Commit**

```bash
cd ~/turn-proxy-android
git add app/src/main/java/com/freeturn/app/service/RealityVpnService.kt
git commit -m "feat(reality): report state via Messenger sink instead of ProxyServiceState"
```

---

### Task 3: Process isolation and client-side bridge

**Files:**
- Modify: `app/src/main/AndroidManifest.xml:104-116`
- Create: `app/src/main/java/com/freeturn/app/service/reality/RealityStateBridge.kt`

**Interfaces:**
- Consumes: `RealityIpc.*`, `RealityState`, `Bundle.toRealityState()`, `Bundle.toRealityLogText()` from Task 1; `RealityVpnService` from Task 2 (as a bind target only — no direct method calls).
- Produces: `class RealityStateBridge(context: Context) { fun bind(); fun unbind() }` — Task 4 wires this into `AndroidProxyServiceLauncher` and `App`.

- [ ] **Step 1: Put `RealityVpnService` in its own process**

In `app/src/main/AndroidManifest.xml`, change:

```xml
        <service
            android:name=".service.RealityVpnService"
            android:exported="false"
            android:foregroundServiceType="specialUse"
            android:permission="android.permission.BIND_VPN_SERVICE"
            tools:targetApi="29">
```

to:

```xml
        <service
            android:name=".service.RealityVpnService"
            android:exported="false"
            android:process=":reality"
            android:foregroundServiceType="specialUse"
            android:permission="android.permission.BIND_VPN_SERVICE"
            tools:targetApi="29">
```

- [ ] **Step 2: Create the bridge**

```kotlin
package com.freeturn.app.service.reality

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import com.freeturn.app.domain.ConnectionStats
import com.freeturn.app.domain.StartupResult
import com.freeturn.app.domain.proxy.ProxyServiceState
import com.freeturn.app.service.RealityVpnService

/**
 * Клиентская сторона моста к RealityVpnService (процесс :reality, см.
 * AndroidManifest.xml). Только слушает - не поднимает и не останавливает
 * сервис (старт/стоп остаются через Intent, см. AndroidProxyServiceLauncher).
 * Живёт в основном процессе, применяет полученные события к
 * ProxyServiceState - так что LocalProxyManager и весь остальной UI не
 * меняются вообще.
 */
class RealityStateBridge(private val context: Context) {

    private var bound = false

    private val clientMessenger = Messenger(
        Handler(Looper.getMainLooper()) { msg ->
            when (msg.what) {
                RealityIpc.MSG_STATE_UPDATE -> applyState(msg.data.toRealityState())
                RealityIpc.MSG_LOG_LINE -> ProxyServiceState.addLog(msg.data.toRealityLogText())
            }
            true
        }
    )

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            bound = true
            val messenger = Messenger(binder)
            val register = Message.obtain(null, RealityIpc.MSG_REGISTER_CLIENT).apply {
                replyTo = clientMessenger
            }
            try {
                messenger.send(register)
            } catch (e: RemoteException) {
                bound = false
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bound = false
            // Процесс :reality умер не по штатному teardown - не оставлять UI
            // подвисшим на "подключено", когда сервиса на деле уже нет.
            ProxyServiceState.setRunning(false)
        }
    }

    /** No-op, если RealityVpnService не запущен - не поднимает его сам (flags=0, без BIND_AUTO_CREATE). */
    fun bind() {
        if (bound) return
        runCatching {
            context.bindService(Intent(context, RealityVpnService::class.java), connection, 0)
        }
    }

    fun unbind() {
        if (!bound) return
        runCatching { context.unbindService(connection) }
        bound = false
    }

    private fun applyState(state: RealityState) {
        ProxyServiceState.setRunning(state.running)
        ProxyServiceState.setConnectionStats(ConnectionStats(state.active, state.total))
        ProxyServiceState.setStartupResult(
            state.failedMessage?.let { StartupResult.Failed(it) } ?: StartupResult.Success
        )
        ProxyServiceState.setTunnelActive(state.tunnelActive)
        if (state.connectedSince != null) {
            ProxyServiceState.markConnectedIfAbsent(state.connectedSince)
        } else {
            ProxyServiceState.clearConnectedSince()
        }
        if (state.teardownComplete) ProxyServiceState.markTeardownComplete() else ProxyServiceState.markTeardownStarted()
    }
}
```

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
cd ~/turn-proxy-android
git add app/src/main/AndroidManifest.xml app/src/main/java/com/freeturn/app/service/reality/RealityStateBridge.kt
git commit -m "feat(reality): isolate RealityVpnService into :reality process, add client bridge"
```

---

### Task 4: Wire the bridge into the launcher and app startup

**Files:**
- Modify: `app/src/main/java/com/freeturn/app/service/AndroidProxyServiceLauncher.kt`
- Modify: `app/src/main/java/com/freeturn/app/di/AppModule.kt`
- Modify: `app/src/main/java/com/freeturn/app/App.kt`

**Interfaces:**
- Consumes: `RealityStateBridge` from Task 3.

- [ ] **Step 1: Register the bridge in Koin**

In `AppModule.kt`, add the import:

```kotlin
import com.freeturn.app.service.reality.RealityStateBridge
```

and add this line inside the `module { ... }` block, before the `AndroidProxyServiceLauncher` line (it depends on it):

```kotlin
    single { RealityStateBridge(androidContext()) }
```

Then change:

```kotlin
    single<ProxyServiceLauncher> { AndroidProxyServiceLauncher(androidContext(), get()) }
```

to:

```kotlin
    single<ProxyServiceLauncher> { AndroidProxyServiceLauncher(androidContext(), get(), get()) }
```

- [ ] **Step 2: Accept the bridge in the launcher and bind/unbind at the right points**

In `AndroidProxyServiceLauncher.kt`, add the import:

```kotlin
import com.freeturn.app.service.reality.RealityStateBridge
```

Change the constructor:

```kotlin
class AndroidProxyServiceLauncher(
    private val context: Context,
    private val prefs: AppPreferences,
) : ProxyServiceLauncher {
```

to:

```kotlin
class AndroidProxyServiceLauncher(
    private val context: Context,
    private val prefs: AppPreferences,
    private val realityStateBridge: RealityStateBridge,
) : ProxyServiceLauncher {
```

Change `start()` from:

```kotlin
    override fun start() {
        val intent = Intent(context, targetServiceClass())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }
```

to:

```kotlin
    override fun start() {
        val targetClass = targetServiceClass()
        val intent = Intent(context, targetClass)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
        if (targetClass == RealityVpnService::class.java) {
            realityStateBridge.bind()
        }
    }
```

Change `stop()` to also unbind — add one line at the end of the existing method body (after the two `context.stopService`/`context.startService` calls):

```kotlin
        realityStateBridge.unbind()
```

- [ ] **Step 3: Bind eagerly at app startup**

In `App.kt`, add the import:

```kotlin
import com.freeturn.app.service.reality.RealityStateBridge
```

Add a property next to `appPreferences`:

```kotlin
    private val realityStateBridge: RealityStateBridge by inject()
```

And call it in `onCreate()`, after `observeWidgetState()`:

```kotlin
        // Если Reality-туннель уже работал в фоне (процесс :reality пережил
        // пересоздание основного процесса), подключаемся к нему сразу, а не
        // ждём следующего нажатия "подключиться".
        realityStateBridge.bind()
```

- [ ] **Step 4: Verify it compiles**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Run the existing unit test suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, no regressions (this plan didn't touch anything the existing tests cover).

- [ ] **Step 6: Commit**

```bash
cd ~/turn-proxy-android
git add app/src/main/java/com/freeturn/app/service/AndroidProxyServiceLauncher.kt app/src/main/java/com/freeturn/app/di/AppModule.kt app/src/main/java/com/freeturn/app/App.kt
git commit -m "feat(reality): bind state bridge from launcher and app startup"
```

---

### Task 5: Live device verification (the real acceptance test)

**Files:** none — this is a manual verification task, no code changes.

**Interfaces:**
- Consumes: the fully wired app from Tasks 1-4.

This reproduces the exact scenario that used to crash — WireGuard enabled — which Tasks 1-4 were built to fix. The earlier hypothesis test (2026-08-13, this session) proved the crash disappears with WireGuard *disabled*; this step proves the *fix* makes it disappear with WireGuard *enabled*, the real family configuration.

- [ ] **Step 1: Install the build from Task 4 on the test device**

```bash
adb -s R5CY93LVDGA install -r app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
```

- [ ] **Step 2: Confirm RealityVpnService actually runs in its own process**

```bash
adb -s R5CY93LVDGA shell am force-stop com.freeturn.app
```

Start the app, connect a Reality profile, then:

```bash
adb -s R5CY93LVDGA shell ps -A | grep freeturn
```

Expected: two rows — `com.freeturn.app.debug` and `com.freeturn.app.debug:reality`. One row only means the manifest change from Task 3 Step 1 didn't take, or the installed APK is stale — rebuild and reinstall before continuing.

- [ ] **Step 3: Reproduce the original crash scenario**

Ask the device operator to, in order:
1. Re-enable WireGuard on the "Любимый Я" profile (undo the Task-independent test from earlier this session, if not already restored).
2. Connect the vk-turn profile, wait for "Туннель активен".
3. Switch to the Reality profile and connect.

- [ ] **Step 4: Verify via log, not "process alive"**

Before Step 3, start a continuous capture:

```bash
adb -s R5CY93LVDGA logcat -c
adb -s R5CY93LVDGA logcat -b crash -b main > /tmp/reality-fix-verify.log 2>&1 &
```

After Step 3 completes, stop the capture and check:

```bash
grep -iE "zygote.*signal|SIGSEGV|FATAL EXCEPTION|AndroidRuntime" /tmp/reality-fix-verify.log
```

Expected: no matches. Also confirm the UI log screen (in-app) still shows the `Reality: запуск` / `Reality: туннель поднят` lines — proves the bridge is actually forwarding, not just "no crash for an unrelated reason".

- [ ] **Step 5: Update the plan doc and project tracking**

Once verification passes, mark this in `/home/lev/.claude/plans/fluttering-hugging-feather.md` (P1 section) as resolved, and record the outcome in mem0 (`workspace: default, project: free-turn-proxy`) the same way the original hypothesis confirmation was recorded.
