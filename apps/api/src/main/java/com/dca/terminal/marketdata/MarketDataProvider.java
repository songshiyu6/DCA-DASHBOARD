package com.dca.terminal.marketdata;

import com.dca.terminal.instrument.InstrumentEntity;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static com.dca.terminal.marketdata.ProviderModels.EtfProfile;
import static com.dca.terminal.marketdata.ProviderModels.IntradayBar;
import static com.dca.terminal.marketdata.ProviderModels.IntradayResult;
import static com.dca.terminal.marketdata.ProviderModels.PriceBar;
import static com.dca.terminal.marketdata.ProviderModels.ProviderQuote;
import static com.dca.terminal.marketdata.ProviderModels.ProviderSearchResult;
import static com.dca.terminal.marketdata.ProviderModels.SplitEvent;

public interface MarketDataProvider {
    ProviderId id();

    boolean isConfigured();

    List<ProviderSearchResult> search(String query);

    ProviderQuote getLatestQuote(InstrumentEntity instrument);

    List<PriceBar> getHistoricalPrices(InstrumentEntity instrument, LocalDate from, LocalDate to);

    List<IntradayBar> getIntradayPrices(InstrumentEntity instrument, LocalDate from, LocalDate to);

    default IntradayResult getIntradayResult(InstrumentEntity instrument, LocalDate from, LocalDate to) {
        return IntradayResult.fromBars(getIntradayPrices(instrument, from, to));
    }

    Optional<EtfProfile> getProfile(InstrumentEntity instrument);

    List<SplitEvent> getSplits(InstrumentEntity instrument, LocalDate from, LocalDate to);
}
