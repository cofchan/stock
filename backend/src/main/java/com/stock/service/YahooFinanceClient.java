package com.stock.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class YahooFinanceClient {
    public record Snapshot(double price, double previousClose, String currency) {
        public double change() { return price - previousClose; }
        public double changePercent() { return previousClose == 0 ? 0 : change() / previousClose * 100; }
    }
    public record Point(String date, double close) {}
    public record Dividend(String exDate, double amount) {}
    public record SearchResult(String symbol, String name, String market) {}

    private final HttpClient httpClient = HttpClient.newBuilder().build();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, CachedResponse> responseCache = new ConcurrentHashMap<>();

    private record CachedResponse(JsonNode value, Instant expiresAt) {}

    public Snapshot snapshot(String symbol) {
        JsonNode meta = request(symbol, "1d", "1d").path("meta");
        double price = number(meta, "regularMarketPrice", 0);
        double previousClose = number(meta, "previousClose", number(meta, "chartPreviousClose", price));
        if (price == 0) throw new IllegalStateException("No live price returned for " + symbol);
        return new Snapshot(price, previousClose, meta.path("currency").asText("USD"));
    }

    public List<Point> history(String symbol, String range) {
        String yahooRange = range.equalsIgnoreCase("1D") ? "1d" : range.equalsIgnoreCase("1Y") ? "1y" : "1mo";
        String interval = range.equalsIgnoreCase("1D") ? "5m" : range.equalsIgnoreCase("1Y") ? "1wk" : "1d";
        boolean intraday = range.equalsIgnoreCase("1D");
        JsonNode result = request(symbol, yahooRange, interval);
        JsonNode timestamps = result.path("timestamp");
        JsonNode closes = result.path("indicators").path("quote").path(0).path("close");
        List<Point> points = new ArrayList<>();
        for (int i = 0; i < timestamps.size() && i < closes.size(); i++) {
            if (!closes.get(i).isNumber()) continue;
            var dateTime = Instant.ofEpochSecond(timestamps.get(i).asLong()).atZone(ZoneOffset.UTC);
            String date = intraday
                ? dateTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                : dateTime.toLocalDate().toString();
            points.add(new Point(date, closes.get(i).asDouble()));
        }
        if (points.isEmpty()) throw new IllegalStateException("Market provider returned no price history for " + symbol);
        return points;
    }

    public List<Dividend> dividends(String symbol) {
        JsonNode events = request(symbol, "5y", "1d").path("events").path("dividends");
        List<Dividend> dividends = new ArrayList<>();
        events.fields().forEachRemaining(entry -> {
            JsonNode dividend = entry.getValue();
            String date = Instant.ofEpochSecond(dividend.path("date").asLong()).atZone(ZoneOffset.UTC).toLocalDate().toString();
            dividends.add(new Dividend(date, dividend.path("amount").asDouble()));
        });
        return dividends.reversed();
    }

    public List<SearchResult> search(String query) {
        URI uri = URI.create("https://query1.finance.yahoo.com/v1/finance/search?q="
            + URLEncoder.encode(query, StandardCharsets.UTF_8) + "&quotesCount=10&newsCount=0");
        HttpRequest request = HttpRequest.newBuilder(uri).header("User-Agent", "stock-dashboard/1.0").GET().build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) throw new IllegalStateException("Market provider returned HTTP " + response.statusCode());
            JsonNode quotes = objectMapper.readTree(response.body()).path("quotes");
            List<SearchResult> results = new ArrayList<>();
            for (JsonNode quote : quotes) {
                if (!"EQUITY".equalsIgnoreCase(quote.path("quoteType").asText())) continue;
                String symbol = quote.path("symbol").asText();
                String name = quote.path("longname").asText(quote.path("shortname").asText(symbol));
                String exchange = quote.path("exchange").asText();
                String market = exchange.contains("HK") || exchange.contains("Hong") ? "HK" : "US";
                results.add(new SearchResult(symbol, name, market));
            }
            return results;
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to reach market provider", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Market provider request interrupted", ex);
        }
    }

    private JsonNode request(String symbol, String range, String interval) {
        String cacheKey = symbol.toUpperCase() + "|" + range + "|" + interval;
        CachedResponse cached = responseCache.get(cacheKey);
        if (cached != null && Instant.now().isBefore(cached.expiresAt())) return cached.value();

        String encodedSymbol = URLEncoder.encode(symbol, StandardCharsets.UTF_8).replace("+", "%20");
        URI uri = URI.create("https://query1.finance.yahoo.com/v8/finance/chart/" + encodedSymbol + "?range=" + range + "&interval=" + interval + "&events=div%2Csplits");
        HttpRequest request = HttpRequest.newBuilder(uri).header("User-Agent", "stock-dashboard/1.0").GET().build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) throw new IllegalStateException("Market provider returned HTTP " + response.statusCode());
            JsonNode result = objectMapper.readTree(response.body()).path("chart").path("result").path(0);
            if (result.isMissingNode() || result.isNull()) throw new IllegalStateException("Market provider returned no data");
            responseCache.put(cacheKey, new CachedResponse(result, Instant.now().plusSeconds(60)));
            return result;
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to reach market provider", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Market provider request interrupted", ex);
        }
    }

    private static double number(JsonNode node, String field, double fallback) {
        return node.path(field).isNumber() ? node.path(field).asDouble() : fallback;
    }
}
