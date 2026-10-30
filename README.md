# StratBot

Android app for **backtesting, dry-running and live-running trading strategies written in
JavaScript**, on **Binance** and **Bitfinex** — with live/dry-run reporting available at any
time (persistent notification + report screen), and support for **any number of simultaneous
runs**, each strategy running on its own thread.

Everything is packaged as a single debug APK. There is no server: market data comes from the
exchanges directly, strategies run locally in a Rhino (Mozilla JavaScript) interpreter.

---

## Feature overview

| Area | What you get |
|---|---|
| Modes | `backtest` (historical klines), `dry` (live quotes, simulated fills), `live` (real orders) |
| Exchanges | Binance spot (public + signed REST, combined websocket) and Bitfinex v2 (public/signed REST, websocket) |
| Strategies | Plain JS files, edited in-app, seeded with two examples (`sma_crossover`, `rsi_reversion`) |
| Concurrency | Unlimited simultaneous runs — every run has its own thread, engine and report |
| Reporting | Live report screen (metrics grid, equity curve, trades, logs), refreshed every second; report persisted to disk every ~1 s so it survives process death |
| Notifications | Foreground service shows a persistent notification with running counts, a **Stop all** action, and tap-to-open the Runs tab |
| Storage | Reports at `filesDir/runs/<runId>/report.json`, strategies at `filesDir/strategies/<id>.json`, API keys in `EncryptedSharedPreferences` |

Run modes and fill rules:

| Mode | Data source | Fill rule |
|---|---|---|
| backtest | REST klines (up to `maxBars`) | **next bar open**, plus fee bps and slippage bps |
| dry | websocket ticks (REST polling fallback) | bid/ask ± slippage, simulated position |
| live | websocket + REST | real `order` placement; market orders polled to fill, limit orders delta-accounted via `orderStatus` polling |

Spot-only accounting: `position > 0` = long. `allowShort` permits negative positions in the
simulated portfolio (backtest/dry); live is spot — the exchange will reject a sell you
cannot cover.

---

## Repository layout

```
stratbot/
├── README.md
├── build.gradle.kts            # AGP 8.7.3, Kotlin 2.0.21
├── settings.gradle.kts
├── gradle.properties           # heap/mworker tuning for a phone, aapt2 override
├── gradlew
├── local.properties            # sdk.dir (per device)
└── app/
    ├── build.gradle.kts        # androidx + okhttp + rhino dependencies
    └── src/main/
        ├── AndroidManifest.xml
        ├── assets/strategies/  # bundled example strategies (JS)
        ├── res/                # themes, colors, strings, ids, 2 drawables (views are built in code)
        └── java/com/stratbot/app/
            ├── App.kt                      # initialises Services
            ├── model/Models.kt             # Candle, Strategy, RunSpec, Trade, Metrics, Report, Tf
            ├── engine/
            │   ├── Indicators.kt           # JVM-side helpers (RSI/MACD/... also provided in JS prelude)
            │   ├── Portfolio.kt            # cash/position/fee/fill accounting
            │   └── StrategyRuntime.kt      # Rhino wrapper + StrategyContext (ctx) + JS prelude
            ├── exchange/                   # Http, Exchange interface, Binance, Bitfinex
            ├── runner/
            │   ├── RunSession.kt           # base: thread loop, publish/snapshot, metrics
            │   ├── BacktestSession.kt      # bar-by-bar replay
            │   ├── MarketSession.kt        # dry/live: ticks + bars + order handling
            │   ├── RunManager.kt           # start/stop sessions, restart-on-boot policy, reports
            │   └── RunnerService.kt        # foreground notification, Stop-all, tap-to-open
            ├── data/Store.kt               # Settings (prefs), Keys (encrypted), Strategy/Run stores
            ├── data/AppServices.kt         # Services singleton
            └── ui/                         # MainActivity + 5 fragments (programmatic views)
```

---

## Building

Two environments are documented: **(A) this phone** (Android + proot Debian, aarch64 — every
tool installed locally under `~/.local`, nothing system-wide) and **(B) a normal desktop**.

### A) Build on this phone (AndroPI)

All tooling lives in `~/.local`:

```
~/.local/bin/mise                          # mise, defines project toolchain via .mise.toml
~/.local/share/mise/installs/java/temurin-17
~/.local/share/android-sdk                 # cmdline-tools, platforms;android-34, build-tools;34/35
~/.local/share/gradle                      # GRADLE_USER_HOME (wrapper dist + caches)
```

One-off bootstrap (skipped if already present):

1. **mise + JDK 17** — project pins `java = "temurin-17"` in `.mise.toml`; `mise install` in the
   project dir puts Temurin 17 under `~/.local/share/mise/installs/java/temurin-17`.
2. **Android SDK** — cmdline-tools under `~/.local/share/android-sdk`, then
   `sdkmanager "platforms;android-34" "build-tools;34.0.0" "build-tools;35.0.0" "platform-tools"`
   with licenses pre-accepted. (`platform-34` may be slow through `sdkmanager`; the zip
   `https://dl.google.com/android/repository/platform-34-ext7_r03.zip` can be fetched with a
   resumable `curl -C -` and unpacked into `platforms/android-34/`.)
3. **Gradle 8.10.2 distribution** — `services.gradle.org` stalls on this network. Fetch from the
   Tencent mirror with resume + stall detection (script: `../.dl-gradle.sh`, i.e.
   `workspace/.dl-gradle.sh`) into
   `~/.local/share/gradle/wrapper/dists/gradle-8.10.2-bin/a04bxjujx95o3nb99gddekhwo/`, unpack
   with the JDK's `jar -xf`, `chmod u+x gradle-8.10.2/bin/*`. The wrapper then starts without
   downloading anything.
4. **`aapt2` for arm64** — Google's Maven `aapt2` (and the SDK's own `build-tools/*/aapt2`) are
   **x86_64 binaries**; they cannot run on this phone. Install Debian's native build
   (`apt-get install -y aapt` inside the proot Debian container) and point AGP at it — this line
   is already in `gradle.properties`:

   ```properties
   android.aapt2FromMavenOverride=/usr/lib/android-sdk/build-tools/debian/aapt2
   ```

   Debian's aapt2 (Android-14-era parser) cannot read API 35's `android.jar` res-table, which is
   why `compileSdk = 34` / `targetSdk = 34` (see `app/build.gradle.kts`).

Build command (every build needs the environment block because tool calls do not share state):

```sh
export HOME=/data/user/0/com.imtaqin.andropi/files/home
export PATH="$HOME/.local/bin:$PATH"
export ANDROID_HOME=$HOME/.local/share/android-sdk
export ANDROID_SDK_ROOT=$ANDROID_HOME
export JAVA_HOME=$HOME/.local/share/mise/installs/java/temurin-17
export GRADLE_USER_HOME=$HOME/.local/share/gradle
export GRADLE_OPTS="-Xmx900m -XX:MaxMetaspaceSize=320m -Dfile.encoding=UTF-8 -Duser.home=$HOME"

cd $HOME/workspace/stratbot
sh $HOME/workspace/.build.sh                  # convenience wrapper: env + ./gradlew assembleDebug
```

Notes for this sandbox:

- **Background processes are killed when a tool call ends** (proot `--kill-on-exit`), so a long
  build must run in the *foreground* of a call, bounded by `timeout`. Gradle is incremental:
  repeat `timeout 170 sh .build.sh` until you see `BUILD SUCCESSFUL`. ~3–4 chunks is typical
  for a cold build, 1 chunk for an incremental one.
- `gradle.properties` sets `-Xmx900m`, `workers.max=2`, `org.gradle.daemon=false` and a smaller
  Kotlin daemon heap — the phone has ~2 GB of free RAM for Gradle, and background JVMs get
  reclaimed.
- Output APK: `app/build/outputs/apk/debug/app-debug.apk` (~8 MB).

### B) Build on a normal desktop

```sh
# JDK 17, Android SDK with platforms;android-34 (or bump compileSdk once you have a modern aapt2)
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Restart hints: `./gradlew clean assembleDebug` if you change `gradle.properties` or resource IDs.

---

## Using the app

Three tabs at the bottom: **Strategies · Runs · Settings**.

- **Strategies** — list of strategies with quick actions *Edit / Backtest / Dry / Live*.
  *+ New* creates an empty strategy and opens the editor (name, exchange, default timeframe,
  symbol, params JSON, fee bps, slippage bps, allowShort, monospace code editor).
  Starting a run opens the run dialog: **symbol, timeframe, bars (backtest), starting cash,
  params JSON**, plus a *LIVE* warning. Starting live without API keys asks for confirmation.
- **Runs** — every run (any mode, any strategy) with status, summary, *Open report* / *Stop* /
  *Delete*. Auto-refreshes every 2.5 s. Header shows how many are running + *Stop all*.
- **Report** — big equity number, pnl/roi/buy&hold, 16-metric grid, equity curve, last 80
  trades, last 150 log lines (coloured warnings/errors). Refreshes every second while you
  scroll; *Stop* in the header, *Back* returns to the list. Every run has a `runId` (also shown
  in the notification) — reports keep being written to disk for the whole run.
- **Settings** — API key/secret per exchange (encrypted at rest), exchange **host overrides**
  (3 for Binance: market/trade/websocket; 3 for Bitfinex: public/auth/websocket), *testnet*
  preset for Binance, REST-fallback toggle, REST poll interval, warmup bars, default starting
  cash, and *Stop all runs*.

**Notification** — while any dry/live run is active a foreground service keeps a notification:
`StratBot · N running`, a compact per-run summary, **Stop all** action, tap = jump to the Runs
tab. Android 13+ asks for notification permission; grant it or the report is still reachable
in-app.

### Default endpoints & network notes

| Setting | Default |
|---|---|
| Binance market data | `https://data-api.binance.vision` (mirror; testnet → `https://testnet.binance.vision`) |
| Binance trading | `https://api.binance.com` |
| Binance websocket | `wss://data-stream.binance.vision` |
| Bitfinex public / auth / ws | `https://api-pub.bitfinex.com` / `https://api.bitfinex.com` / `wss://api-pub.bitfinex.com/ws/2` |

All hosts are editable in Settings — on restricted networks point them at whichever mirror is
reachable (testnet presets are included for Binance). If the websocket is silent for 45 s and
*REST fallback* is on, the session automatically switches to REST polling (interval configurable).

---

## Writing strategies

A strategy is a plain JavaScript file with up to three global functions:

```js
function init(ctx) {                 // once, before any data
  ctx.log("ready on " + ctx.symbol);
}

function onBar(ctx, bar) {           // every closed candle: {t,o,h,l,c,v}
  if (ctx.position === 0 && rsi(ctx.closes(200), 14) < 30) {
    ctx.buyFraction(Number(ctx.param("size", 0.9)));
  }
}

function onTick(ctx, tick) {         // optional — dry/live only: {ts,bid,ask,last}
  if (ctx.position > 0 && tick.last >= ctx.entryPrice * 1.02) ctx.close();
}
```

### `ctx` reference

| Member | Meaning |
|---|---|
| `ctx.exchange`, `ctx.symbol`, `ctx.timeframe`, `ctx.mode` | run identifiers (`mode` = backtest/dry/live) |
| `ctx.now` | current time (ms) |
| `ctx.price`, `ctx.bid`, `ctx.ask` | latest trade/bid/ask |
| `ctx.position`, `ctx.entryPrice` | current qty (>0 long, <0 short when allowed) and average entry |
| `ctx.cash`, `ctx.equity` | free cash and total equity (cash + position × price) |
| `ctx.openOrders` | working (dry/live) orders |
| `ctx.buy(qty)`, `ctx.sell(qty)` | market orders for `qty` base units |
| `ctx.buyFraction(frac)`, `ctx.sellFraction(frac)` | fraction of available cash/position (default sizing) |
| `ctx.close()` / `ctx.closeAll()` | flatten the position |
| `ctx.limitBuy(price, qty)`, `ctx.limitSell(price, qty)` | resting limit orders → orderId (see fill rules below) |
| `ctx.cancel(orderId)` | cancel a resting order |
| `ctx.bars(n)` | last `n` candles as objects `{t,o,h,l,c,v}` (oldest first) |
| `ctx.opens(n)`, `ctx.highs(n)`, `ctx.lows(n)`, `ctx.closes(n)`, `ctx.volumes(n)` | last `n` values as number arrays |
| `ctx.param(key, default)` | value from the run's *params JSON* (quoted before start) |
| `ctx.log(msg)` | write to the run log (visible in the report) |
| `ctx.stop()` | request a graceful stop of the run |

### Prelude helpers (global functions)

`len(a)`, `last(a)`, `slice(a, from, to)`, `shift(a)`,
`sma(a,p)`, `ema(a,p)`, `rsi(a,p)`, `stdev(a,p)`,
`highest(a,n)`, `lowest(a,n)`, `atr(h,l,c,p)`,
`macd(a,fast,slow,sig)` → `[macd, signal, hist]`,
`fmt(x)`-style helpers you define yourself, plus `PI`.

Arrays are plain JS arrays (`ctx.closes(300)` returns a JS array of numbers), so normal
`for`/`map`/`slice` usage works. `NaN` is returned when there is not enough data — guard with
length checks.

### Execution notes

- `init`/`onBar`/`onTick` always run on the run's own engine thread; network calls never
  happen inside your script (data fetch is done by the host).
- Backtest fills happen **on the next bar's open**, with fee + slippage applied; a strategy
  cannot time-travel.
- Dry fills use the live bid/ask ± slippage; limits fill when the quote crosses the price.
- Live market orders are considered filled once `executedQty > 0`; live limits are updated by
  polling `orderStatus`, so accounting catches up after a delay.
- Exceptions inside your functions are caught, logged to the run log and do not kill the run
  (the offending callback is skipped for one tick/bar; repeated failures are logged once).
- Rhino runs in interpreter mode in ES6. There is **no instruction watchdog**: a runaway
  `while(true){}` blocks that run's thread — use `ctx.stop()` and keep loops bounded.

---

## Data & storage paths

| Data | Path |
|---|---|
| Strategies | `<filesDir>/strategies/<strategyId>.json` (code, defaults, per-exchange) |
| Runs & reports | `<filesDir>/runs/<runId>/report.json` (status, metrics, curve, trades, logs) |
| Non-secret settings | `SharedPreferences` `stratbot.prefs` |
| API keys | `EncryptedSharedPreferences` `stratbot.keys` (Keystore AES-256; falls back to plain prefs if Keystore fails) |

Resources: `assets/strategies/*.js` are copied on first launch. Deleting a run deletes its
report; deleting a strategy does not touch its finished runs.

---

## Known limits / roadmap

- Spot only (no margin/futures); shorting is simulated unless the exchange allows the sell.
- One websocket connection per run; very many simultaneous runs on one phone is CPU/network
  bound (each run is a full Rhino context + thread).
- No instruction-level cancellation in Rhino — cooperative `stopRequested` only.
- `compileSdk/targetSdk = 34` because of the Debian aapt2 (arm64) workaround; bump together
  with a modern aapt2 binary.
- Reports cap retained log lines/trades shown in the UI (last 150 / last 80); the JSON keeps
  the full series wrapped by the store (bounded, see `Report`).

## Troubleshooting

| Symptom | Fix |
|---|---|
| `AAPT2 ... Daemon startup failed` | `android.aapt2FromMavenOverride` missing or not an arm64 binary → `apt-get install -y aapt` in the Debian container; gradle.properties already points at `/usr/lib/android-sdk/build-tools/debian/aapt2` |
| `Failed to load resources table in APK .../android-35/android.jar` | aapt2 too old for API-35 res tables → keep `compileSdk = 34` |
| `Could not resolve ... repositories` during build | network filter: check `curl -skI https://repo.maven.apache.org/...` and `https://dl.google.com/dl/android/maven2/`, or pre-warm caches while online |
| Wrapper hangs on "Downloading" | gradle dist mirror: run `sh $HOME/workspace/.dl-gradle.sh`, then unpack with `jar -xf` |
| Build dies silently / no daemon log | processes are reclaimed when the tool call ends → run the build in the **foreground** with `timeout 170 ...` and repeat |
| Live/dry run shows `stream ... → REST polling` | websocket host unreachable; expected on filtered networks, or disable *REST fallback* to force the socket |
| No notification | grant notification permission (Android 13+) and make sure a dry/live run is running (backtests finish on their own and do not notify) |