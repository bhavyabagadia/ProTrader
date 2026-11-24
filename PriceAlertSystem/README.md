
# ProTrader — Crypto & Stock Price Alert Dashboard

A local web dashboard powered by Java 21 and the existing price-alert engine.

## Requirements
- JDK 17 or newer
- Internet connection for live market providers
- Windows 10/11 or Linux

## Windows Quick Start

1. Open this project folder in VS Code.
2. Open a terminal.
3. Run:

   .\run.bat

4. Open http://localhost:8080 in Chrome if it does not open automatically.
5. Keep the terminal open while using ProTrader.
6. Press Ctrl+C to stop.

## Features

- Market overview
- Live price monitoring
- Crypto and stock providers
- Create price alerts
- Remove and re-arm alerts
- Watchlist management
- Alert history
- Telegram notifications
- Configurable refresh interval

## Supported symbols

- BTCUSDT
- ETHUSDT
- RELIANCE.NS
- AAPL
- coingecko:bitcoin
- finnhub:AAPL
- sim:BTC

## Optional API configuration

Copy config.properties.example to config.properties.

Add your own:
- Finnhub API key
- CoinGecko API key
- Telegram bot token
- Telegram chat ID

Never share your API credentials.

## Important

ProTrader runs locally at 127.0.0.1:8080.
Prices are polled and may be delayed or rate-limited.
The dashboard is for monitoring and education, not financial advice.
