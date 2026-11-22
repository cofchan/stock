package com.stock.api;

import com.stock.service.MarketDataService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class MarketController {
    private final MarketDataService marketDataService;

    public MarketController(MarketDataService marketDataService) {
        this.marketDataService = marketDataService;
    }

    @GetMapping("/overview")
    public MarketDataService.Overview overview() { return marketDataService.overview(); }

    @GetMapping("/quotes")
    public Object quotes(@RequestParam(defaultValue = "all") String market) {
        return marketDataService.quotes(market);
    }

    @GetMapping("/search")
    public Object search(@RequestParam String q) { return marketDataService.search(q); }

    @GetMapping("/quotes/{symbol}")
    public Object quote(@PathVariable String symbol) { return marketDataService.quote(symbol); }

    @GetMapping("/quotes/{symbol}/history")
    public Object history(@PathVariable String symbol, @RequestParam(defaultValue = "1M") String range) {
        return marketDataService.history(symbol, range);
    }

    @GetMapping("/quotes/{symbol}/dividends")
    public Object dividends(@PathVariable String symbol) { return marketDataService.dividends(symbol); }
}
