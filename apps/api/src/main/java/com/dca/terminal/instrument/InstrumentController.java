package com.dca.terminal.instrument;

import com.dca.terminal.common.DomainException;
import com.dca.terminal.marketdata.MarketDataDtos;
import com.dca.terminal.marketdata.MarketDataService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import static com.dca.terminal.instrument.InstrumentDtos.AddInstrumentRequest;
import static com.dca.terminal.instrument.InstrumentDtos.InstrumentResponse;
import static com.dca.terminal.instrument.InstrumentDtos.SearchResult;
import static com.dca.terminal.marketdata.MarketDataDtos.MetricsResponse;
import static com.dca.terminal.marketdata.MarketDataDtos.PriceHistoryResponse;
import static com.dca.terminal.marketdata.MarketDataDtos.SyncResponse;

@RestController
@RequestMapping("/api/v1/instruments")
public class InstrumentController {
    private final MarketDataService marketDataService;

    public InstrumentController(MarketDataService marketDataService) {
        this.marketDataService = marketDataService;
    }

    @GetMapping
    public List<InstrumentResponse> tracked() { return marketDataService.tracked(); }

    @GetMapping("/search")
    public List<SearchResult> search(@RequestParam String q) { return marketDataService.search(q); }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public InstrumentResponse add(@Valid @RequestBody AddInstrumentRequest request) {
        return response(marketDataService.add(request.symbol()));
    }

    @GetMapping("/{symbol}")
    public InstrumentResponse detail(@PathVariable @Pattern(regexp = "[A-Za-z0-9.-]{1,16}") String symbol) {
        return response(etf(symbol));
    }

    @DeleteMapping("/{symbol}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void untrack(@PathVariable String symbol) { marketDataService.untrack(symbol); }

    @GetMapping("/{symbol}/quote")
    public InstrumentDtos.QuoteResponse quote(@PathVariable String symbol) {
        return marketDataService.latestQuote(etf(symbol));
    }

    @GetMapping("/{symbol}/metrics")
    public MetricsResponse metrics(@PathVariable String symbol) {
        return marketDataService.metrics(etf(symbol));
    }

    @GetMapping("/{symbol}/prices")
    public PriceHistoryResponse prices(@PathVariable String symbol,
                                       @RequestParam(defaultValue = "1Y") String range) {
        return marketDataService.prices(etf(symbol), range);
    }

    @PostMapping("/{symbol}/sync")
    public SyncResponse sync(@PathVariable String symbol) {
        return marketDataService.sync(etf(symbol));
    }

    @PostMapping("/{symbol}/sync/full")
    public SyncResponse fullResync(@PathVariable String symbol) {
        return marketDataService.fullResync(etf(symbol));
    }

    @GetMapping("/providers")
    public MarketDataDtos.ProvidersResponse providers() {
        return new MarketDataDtos.ProvidersResponse(marketDataService.providerStatuses(),
                marketDataService.primaryProvider(), marketDataService.fallbackProvider());
    }

    private InstrumentEntity etf(String symbol) {
        InstrumentEntity instrument = marketDataService.getInstrument(symbol);
        if (instrument.getInstrumentType() != InstrumentType.ETF) {
            throw new DomainException(HttpStatus.NOT_FOUND, "ETF_NOT_FOUND", "ETF not found: " + symbol);
        }
        return instrument;
    }

    private InstrumentResponse response(InstrumentEntity entity) {
        return new InstrumentResponse(entity.getId(), entity.getSymbol(), entity.getName(), entity.getExchange(),
                entity.getCurrency(), entity.getInstrumentType(), entity.getIssuer(), entity.getExpenseRatio(),
                entity.getAum(), entity.getDividendYield(), entity.getDataProvider(), entity.isTracked(), entity.getDataStatus());
    }
}
