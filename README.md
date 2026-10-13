# Nayan Kalpande PT

Android app for **manual paper trading** on **NSE (₹2,00,000)** and **US ($2,000)** - to try strategy
ideas by hand, with broker-style charts, orders and analytics. Paper only: no real order is ever sent.

## What it does

| | |
|---|---|
| **Search** | Every NSE share (≈3,700 incl. SME / T2T, + indices) and every US listing (≈12,900, NASDAQ + NYSE / Arca / Cboe) - ranked ticker / name match, 30 results a page as you scroll |
| **Charts** | TradingView Lightweight Charts™: candles + volume, 1m / 5m / 15m / 1H / 1D / 1W, EMA 20/50, session VWAP, your avg / stop-loss / target lines and fill markers, pinch-zoom, OHLC readout |
| **Orders** | Market, limit, stop · Intraday (auto square-off 15:20 IST / 15:55 ET) or Hold (swing / long term) · long and short (NSE: intraday only, like MIS) · stop-loss + target on positions · quantity by % of buying power |
| **Realistic fills** | Live quote ± slippage while open; otherwise every working order / stop / target is checked on **1-minute candles** (gap through a limit fills at the better open; stop-loss assumed before target in the same bar) - also in the background every ~15 min, so fills land at the right minute even with the app closed |
| **Charges & margin** | NSE: Zerodha equity rates (STT, exchange, SEBI, stamp, GST, DP), intraday 5x margin. US: SEC + FINRA TAF sell fees, shorts at 150% margin |
| **Analytics** | Equity curve, win rate, profit factor, expectancy, payoff, avg R, max drawdown, streaks, t-stat - and breakdowns by **strategy tag**, intraday vs hold, long vs short, exit type, holding time, weekday, symbol. CSV export |

## Data

| Market | Source | Login |
|---|---|---|
| NSE | Upstox: instrument master, v3 candles (public), v2 quotes | candles need none; paste a daily **Upstox access token** in Settings for live quotes (otherwise prices are ≤ 1 min behind) |
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
| `assets/chart/` | chart page (Lightweight Charts, Apache-2.0) |
| `work/FillWorker.kt` | background order checks + notifications |

Charts: Lightweight Charts™ by TradingView (Apache License 2.0).
