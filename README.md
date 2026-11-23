# Stock Market Dashboard

A lightweight HK and US stock market dashboard with a Vue 3 frontend and a Java 25 Spring Boot backend. The application supports stock search, live quote lookup, price history, interactive chart inspection, and dividend history.

## Features

- View market indexes for Hong Kong and US markets, including the Dow Jones Industrial Average.
- View a starter watchlist containing HK and US equities.
- Add stocks to or remove stocks from the watchlist directly in the UI; changes are saved in the browser.
- Search for a stock by ticker/code or company name.
- Display live quote data when Yahoo Finance is available.
- Display price history for `1D`, `1M`, and `1Y` periods.
- Scale the graph to the highest and lowest prices in the selected period.
- Hover over the graph to see the nearest price, currency, and date/time.
- Display recent dividend events and an estimated annual yield.
- Use representative fallback data when the external market provider is unavailable or rate-limits requests.

## Project structure

```text
stock/
├── backend/
│   ├── pom.xml
│   └── src/main/
│       ├── java/com/stock/
│       │   ├── StockApplication.java
│       │   ├── api/
│       │   │   ├── ApiExceptionHandler.java
│       │   │   └── MarketController.java
│       │   └── service/
│       │       ├── MarketDataService.java
│       │       └── YahooFinanceClient.java
│       └── resources/application.yml
├── frontend/
│   ├── package.json
│   ├── vite.config.js
│   └── src/
│       ├── App.vue
│       ├── main.js
│       └── style.css
└── README.md
```

## Technology stack

### Backend

- Java 25
- Spring Boot 4.0.10
- Spring Web and Validation
- Jackson Databind
- Maven

### Frontend

- Vue 3
- Vite
- JavaScript modules
- npm

## Prerequisites

- JDK 25
- Maven 3.9 or newer is recommended
- Node.js and npm

Check the installed versions:

```bash
java -version
mvn -version
node --version
npm --version
```

The backend is configured with `<java.version>25</java.version>`, so Maven must run with a Java 25 JDK.

## Run locally

The frontend and backend run as separate processes.

### Start the backend

From the project root:

```bash
cd backend
mvn spring-boot:run
```

The API listens on `http://localhost:8080`.

For a clean verification build:

```bash
cd backend
mvn clean test
```

### Install and start the frontend

In a second terminal:

```bash
cd frontend
npm install
npm run dev
```

The dashboard is available at `http://localhost:5173`. Vite proxies `/api` requests to `http://localhost:8080`, as configured in [vite.config.js](frontend/vite.config.js).

## Frontend commands

Run these commands from `frontend/`:

```bash
npm install       # Install dependencies
npm run dev       # Start the Vite development server
npm run build     # Create a production build
npm run preview   # Preview the production build
```

## Maintain the watchlist

Use the search box in the Market watch section to find a stock by ticker/code or company name. Search results include an `Add` button for stocks that are not already in the watchlist. Select `Remove` on a watchlist row to remove it. The current watchlist is stored in the browser's local storage, so it persists across refreshes in that browser. Clearing site data resets the list to the starter watchlist.

## REST API

All routes are served below `/api`.

| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/api/overview` | Market indexes, including Dow Jones (`^DJI`), and the default watchlist quotes. |
| `GET` | `/api/quotes?market=all` | Quotes filtered by `all`, `HK`, or `US`. |
| `GET` | `/api/search?q=apple` | Searches Yahoo Finance for equity symbols and names. |
| `GET` | `/api/quotes/{symbol}` | Current quote for a symbol. |
| `GET` | `/api/quotes/{symbol}/history?range=1D` | Historical prices for `1D`, `1M`, or `1Y`. |
| `GET` | `/api/quotes/{symbol}/dividends` | Dividend events for a symbol. |

Example requests:

```bash
curl http://localhost:8080/api/overview
curl 'http://localhost:8080/api/search?q=Microsoft'
curl 'http://localhost:8080/api/quotes/AAPL/history?range=1M'
curl http://localhost:8080/api/quotes/AAPL/dividends
```

Symbols containing characters such as `^` or spaces should be URL-encoded when calling the API directly. The frontend encodes symbols automatically.

## Market data behavior

`YahooFinanceClient` retrieves chart, quote, search, and dividend data from Yahoo Finance without requiring an API key. Successful chart responses are cached in memory for 60 seconds to reduce duplicate requests and provider rate limits.

When Yahoo Finance cannot be reached:

- Known watchlist symbols use representative quote, history, and dividend fallback values.
- Dynamically searched symbols use live data when available and otherwise show an unavailable/empty state where no safe fallback exists.
- Empty history responses fall back to generated history for the selected range.

External market data can be delayed, incomplete, or rate-limited. This integration is suitable for demonstration or development. Before production use, review Yahoo Finance terms, data licensing, caching limits, reliability requirements, and any required commercial market-data agreement.

## History range mapping

Keep the frontend range names and backend mapping consistent:

| UI range | Yahoo range | Yahoo interval | Graph labels |
|---|---|---|---|
| `1D` | `1d` | `5m` | Time of day |
| `1M` | `1mo` | `1d` | Month and day |
| `1Y` | `1y` | `1wk` | Month and day |

## Configuration

Backend settings are in [application.yml](backend/src/main/resources/application.yml):

```yaml
spring:
  application:
    name: stock-api
server:
  port: 8080
```

To change the backend port, update `server.port` and the Vite proxy target in [vite.config.js](frontend/vite.config.js). If the frontend is hosted separately in production, configure a reverse proxy or an appropriate CORS policy.

## Development notes

- The Spring Boot entry point is [StockApplication.java](backend/src/main/java/com/stock/StockApplication.java).
- HTTP routes are defined in [MarketController.java](backend/src/main/java/com/stock/api/MarketController.java).
- Yahoo Finance parsing and provider calls are in [YahooFinanceClient.java](backend/src/main/java/com/stock/service/YahooFinanceClient.java).
- Fallback behavior and market-data mapping are in [MarketDataService.java](backend/src/main/java/com/stock/service/MarketDataService.java).
- The dashboard and interactive graph are in [App.vue](frontend/src/App.vue).

## Troubleshooting

### Maven cannot resolve Spring Boot dependencies

Check that Java 25 is active and Maven can reach Maven Central:

```bash
java -version
mvn -version
```

Retry when network access is available if dependency resolution fails with a DNS or network error.

### `NoClassDefFoundError: org/springframework/boot/SpringApplication`

Do not launch with only `backend/target/classes` on the classpath. Spring Boot requires its dependency classpath. Use:

```bash
cd backend
mvn spring-boot:run
```

### The frontend cannot load data

Confirm that the backend is running on port 8080 before starting Vite. Then check browser developer tools for failed `/api` requests.

### Yahoo Finance returns HTTP 429

HTTP 429 means the provider has rate-limited requests. Wait before retrying. The backend caches successful responses and supplies fallback data for known symbols, but it does not make aggressive retries.

### The graph is empty

Use the refresh button and verify the backend is running. Selecting `1D`, `1M`, or `1Y` reloads the corresponding history. If the provider returns no points, the backend attempts range-specific fallback history.

## Production considerations

This is a development/demo dashboard. It does not currently include authentication, persistent watchlists, portfolio storage, scheduled data ingestion, observability, or a production market-data entitlement. Before deployment, consider:

- Secure configuration and secrets management.
- A licensed and reliable market-data provider.
- Request throttling, retries with backoff, and circuit breaking.
- Persistent caching and a database for user portfolios.
- Authentication and authorization.
- Automated frontend and backend tests.
- HTTPS, CORS policy, structured logging, and health monitoring.

## License and data disclaimer

No application license is currently declared. Market prices and dividend data are for informational and demonstration purposes only and are not financial advice. Verify all data independently before making investment decisions.
# stock
