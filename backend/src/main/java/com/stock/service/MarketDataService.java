package com.stock.service;

import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class MarketDataService {
    public record Quote(String symbol, String name, String market, double price, double change, double changePercent, String currency, double dividendYield) {}
    public record Index(String symbol, String name, double value, double change, double changePercent) {}
    public record Point(String date, double close) {}
    public record Dividend(String exDate, String payDate, double amount, double yield) {}
    public record Overview(List<Index> indexes, List<Quote> movers) {}

    private final YahooFinanceClient marketClient;
    private final List<Quote> fallbackQuotes = List.of(
        new Quote("0700.HK", "Tencent Holdings", "HK", 382.20, 4.80, 1.27, "HKD", 0.62),
        new Quote("9988.HK", "Alibaba Group", "HK", 101.40, -1.60, -1.55, "HKD", 0.00),
        new Quote("0005.HK", "HSBC Holdings", "HK", 70.85, 0.35, 0.50, "HKD", 7.42),
        new Quote("AAPL", "Apple Inc.", "US", 273.41, 2.16, 0.80, "USD", 0.38),
        new Quote("MSFT", "Microsoft Corp.", "US", 493.20, -3.77, -0.76, "USD", 0.67),
        new Quote("NVDA", "NVIDIA Corp.", "US", 182.06, 5.12, 2.89, "USD", 0.03),
        new Quote("O", "Realty Income", "US", 60.14, 0.42, 0.70, "USD", 5.92)
    );

    public MarketDataService(YahooFinanceClient marketClient) { this.marketClient = marketClient; }

    public Overview overview() {
        return new Overview(List.of(
            liveIndex("^HSI", "Hang Seng", 26352.72, 188.14, 0.72),
            liveIndex("^HSCE", "HS China Enterprises", 9465.15, 102.41, 1.09),
            liveIndex("^GSPC", "S&P 500", 6796.86, -14.23, -0.21),
            liveIndex("^DJI", "Dow Jones", 47289.33, -386.21, -0.81),
            liveIndex("^IXIC", "Nasdaq Composite", 23004.54, 65.39, 0.29)
        ), quotes("all"));
    }

    public List<Quote> quotes(String market) {
        return fallbackQuotes.stream()
            .filter(q -> market.equalsIgnoreCase("all") || q.market().equalsIgnoreCase(market))
            .map(this::liveQuote)
            .toList();
    }

    public List<Quote> search(String query) {
        String normalized = query == null ? "" : query.trim();
        if (normalized.isBlank()) return List.of();
        try {
            return marketClient.search(normalized).stream()
                .map(result -> liveQuote(new Quote(result.symbol(), result.name(), result.market(), 0, 0, 0, "USD", 0)))
                .toList();
        } catch (RuntimeException ex) {
            String value = normalized.toLowerCase();
            return fallbackQuotes.stream()
                .filter(q -> q.symbol().toLowerCase().contains(value) || q.name().toLowerCase().contains(value))
                .map(this::liveQuote)
                .toList();
        }
    }

    public Quote quote(String symbol) {
        return liveQuote(resolveQuote(symbol));
    }

    public List<Point> history(String symbol, String range) {
        Quote fallback = resolveQuote(symbol);
        try {
            return marketClient.history(symbol, range).stream().map(p -> new Point(p.date(), p.close())).toList();
        } catch (RuntimeException ex) {
            return fallbackHistory(fallback, range);
        }
    }

    public List<Dividend> dividends(String symbol) {
        Quote quote = resolveQuote(symbol);
        try {
            List<YahooFinanceClient.Dividend> liveDividends = marketClient.dividends(symbol);
            double annualYield = quote.price() == 0 ? quote.dividendYield()
                : liveDividends.stream().limit(4).mapToDouble(YahooFinanceClient.Dividend::amount).sum() / quote.price() * 100;
            return liveDividends.stream().map(d -> new Dividend(d.exDate(), "—", d.amount(), annualYield)).toList();
        } catch (RuntimeException ex) {
            return fallbackDividends(quote);
        }
    }

    private Quote liveQuote(Quote fallback) {
        try {
            YahooFinanceClient.Snapshot live = marketClient.snapshot(fallback.symbol());
            return new Quote(fallback.symbol(), fallback.name(), fallback.market(), live.price(), live.change(), live.changePercent(), live.currency(), fallback.dividendYield());
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    private Index liveIndex(String symbol, String name, double fallbackValue, double fallbackChange, double fallbackPercent) {
        try {
            YahooFinanceClient.Snapshot live = marketClient.snapshot(symbol);
            return new Index(symbol, name, live.price(), live.change(), live.changePercent());
        } catch (RuntimeException ex) {
            return new Index(symbol, name, fallbackValue, fallbackChange, fallbackPercent);
        }
    }

    private Quote resolveQuote(String symbol) {
        String normalizedSymbol = symbol == null ? "" : symbol.trim();
        return fallbackQuotes.stream().filter(q -> q.symbol().equalsIgnoreCase(normalizedSymbol)).findFirst()
            .orElseGet(() -> {
                try {
                    YahooFinanceClient.Snapshot snapshot = marketClient.snapshot(normalizedSymbol);
                    String market = normalizedSymbol.toUpperCase().endsWith(".HK") ? "HK" : "US";
                    String name = marketClient.search(normalizedSymbol).stream()
                        .filter(result -> result.symbol().equalsIgnoreCase(normalizedSymbol))
                        .map(YahooFinanceClient.SearchResult::name)
                        .findFirst()
                        .orElse(normalizedSymbol);
                    return new Quote(normalizedSymbol, name, market, snapshot.price(), snapshot.change(), snapshot.changePercent(), snapshot.currency(), 0);
                } catch (RuntimeException ex) {
                    String market = normalizedSymbol.toUpperCase().endsWith(".HK") ? "HK" : "US";
                    return new Quote(normalizedSymbol, normalizedSymbol, market, 0, 0, 0, "USD", 0);
                }
            });
    }

    private List<Point> fallbackHistory(Quote q, String range) {
        int count = range.equalsIgnoreCase("1D") ? 12 : range.equalsIgnoreCase("1Y") ? 12 : 20;
        return java.util.stream.IntStream.range(0, count).mapToObj(i -> {
            double wave = Math.sin(i * 0.72) * q.price() * 0.025;
            double trend = (i - count / 2.0) * q.price() * 0.002;
            if (range.equalsIgnoreCase("1D")) {
                return new Point(java.time.LocalDateTime.now().minusMinutes((long) (count - i) * 30).toString(), Math.round((q.price() + wave + trend) * 100.0) / 100.0);
            }
            return new Point(java.time.LocalDate.now().minusDays((long) (count - i) * (range.equalsIgnoreCase("1Y") ? 30 : 3)).toString(), Math.round((q.price() + wave + trend) * 100.0) / 100.0);
        }).toList();
    }

    private List<Dividend> fallbackDividends(Quote q) {
        if (q.dividendYield() == 0) return List.of();
        return List.of(
            new Dividend("2025-09-05", "2025-09-19", Math.round(q.price() * q.dividendYield() / 100.0 / 4 * 100.0) / 100.0, q.dividendYield()),
            new Dividend("2025-06-06", "2025-06-20", Math.round(q.price() * q.dividendYield() / 100.0 / 4 * 100.0) / 100.0, q.dividendYield()),
            new Dividend("2025-03-07", "2025-03-21", Math.round(q.price() * q.dividendYield() / 100.0 / 4 * 100.0) / 100.0, q.dividendYield())
        );
    }
}
