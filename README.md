# Nayan Kalpande PT

Android app for **manual paper trading** on **NSE (₹2,00,000)** and **US ($2,000)** - to try strategy
ideas by hand, with broker-style charts, orders and analytics. Paper only: no real order is ever sent.

## What it does

| | |
|---|---|
| **Search** | Every NSE share (≈3,700 incl. SME / T2T, + indices) and every US listing (≈12,900, NASDAQ + NYSE / Arca / Cboe) - ranked ticker / name match, 30 results a page as you scroll |
| **Charts** | Native candlestick chart: candles + volume, 1m / 5m / 15m / 1H / 1D / 1W (bars aligned to the session open), EMA 20/50, session VWAP, live last-price tag, your avg / stop-loss / target lines (pinned to the edge when off-screen) and fill markers; pinch to zoom, drag sideways to scroll, long-press crosshair with OHLC |
| **Live price** | The open chart polls its stock every 2 s (US; NSE with the Upstox token - 5 s from 1-minute candles without it), updates the forming candle and starts new ones at each bucket; full candles re-downloaded every minute |
| **Orders** | Market, limit, stop · Intraday (auto square-off 15:20 IST / 15:55 ET) or Hold (swing / long term) · long and short (NSE: intraday only, like MIS) · stop-loss + target on positions · quantity by % of buying power |
| **Realistic fills** | Live quote ± slippage while open; otherwise every working order / stop / target is checked on **1-minute candles** (gap through a limit fills at the better open; stop-loss assumed before target in the same bar) - also in the background every ~15 min, so fills land at the right minute even with the app closed |
| **Charges & margin** | NSE: Zerodha equity rates (STT, exchange, SEBI, stamp, GST, DP), intraday 5x margin. US: SEC + FINRA TAF sell fees, shorts at 150% margin |
| **Analytics** | Equity curve, win rate, profit factor, expectancy, payoff, avg R, max drawdown, streaks, t-stat - and breakdowns by **strategy tag**, intraday vs hold, long vs short, exit type, holding time, weekday, symbol. CSV export |

## Online storage (GitHub)

Settings → **Online storage (GitHub)**: data repo (default `AdityaKalpandeEren/nkpt-data`, private) + a fine-grained token
(only that repo, Contents = Read and write). From then on every order, fill, exit, SL/target edit, watchlist / strategy
change, reset and restore is committed ~2 s later as one commit to `users/<login>/<NSE|US>/`:
`state.json` (the full account), `trades.csv`, `orders.csv` and `daily/<date>.json`; an end-of-day commit follows each close.
Background fills push too. Failed pushes are retried. On start / Connect / Sync now the app loads the GitHub copy when it is
newer (new phone, reinstall, another device). Each user's token picks their folder, so more users just add folders.
The token stays on the phone (not in backups, not in the repo). See the data repo README for pulling it into research.

## Backup, restore and using the data in the bots

Settings → **Back up to file** saves one JSON file (format 1) with both accounts: every order, closed
trade, open position, equity-curve point (last 30 days at 15 minutes, older ones daily - never dropped),
watchlist and strategy tag, plus the fill settings. The Upstox token is never included. **Restore from
file** shows what the file holds and asks before replacing the accounts on the phone.

Read it from Python, e.g. to see which manually traded strategy tags are worth automating in the US / NSE
alert bots (times are epoch seconds, UTC):

```python
import json, pandas as pd
b = json.load(open("nkpt_backup_2026-10-13.json"))
for acc in b["accounts"]:
    t = pd.DataFrame(acc["trades"])
    if t.empty: continue
    t["net"] = t["gross"] - t["charges"]
    print(acc["market"], t.groupby("strategy")["net"].agg(["count", "sum", "mean"]))
```

## Data

| Market | Source | Login |
|---|---|---|
| NSE | Upstox: instrument master, v3 candles (public), v2 quotes | candles need none; paste the **Upstox extended token** (the long-lived one the NSE alert bot uses) once in Settings for live quotes - Settings shows its expiry date |
| US | Yahoo Finance chart / spark / search, NASDAQ Trader symbol directory | none |

Everything else (accounts, orders, trades, watchlists, token) stays on the phone.

## Build

Needs JDK 17 and Android SDK 35.

```bash
cd android
./gradlew :app:testDebugUnitTest            # engine, charges, analytics, search + screenshot tests
NKPT_LIVE=1 ./gradlew :app:testDebugUnitTest --tests '*LiveDataTest*'   # hits the real data sources
./gradlew :app:recordRoborazziDebug          # screenshots -> app/build/outputs/roborazzi
./gradlew :app:assembleRelease               # app/build/outputs/apk/release/app-release.apk
```

Release builds are signed with the machine's debug key unless `~/.android/nkpt-release.properties`
names a keystore (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`).

## Layout

| Path | What |
|---|---|
| `domain/Engine.kt` | the paper broker: validation, fills, positions, P&L, margin, square-off (pure, unit-tested) |
| `domain/Charges.kt`, `Analytics.kt`, `MarketHours.kt` | charges, stats, sessions |
| `data/` | symbol lists + paged search, Upstox / Yahoo market data, account storage, `Broker` (data + engine) |
| `ui/` | Compose screens: Watchlist, Search, Detail + order ticket, Orders, Portfolio / journal, Analytics, Settings, intro animation |
| `ui/components/PriceChart.kt`, `domain/Bars.kt` | native chart; session-aligned bars + live-tick folding |
| `work/FillWorker.kt` | background order checks + notifications |

