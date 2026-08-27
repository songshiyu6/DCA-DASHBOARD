package com.dca.terminal.marketdata;

import java.util.ArrayList;
import java.util.List;

public final class ProviderPriority {
    private ProviderPriority() { }

    public static List<ProviderId> ordered(ProviderId primary, ProviderId fallback) {
        List<ProviderId> result = new ArrayList<>();
        if (primary != null) result.add(primary);
        if (fallback != null && !result.contains(fallback)) result.add(fallback);
        for (ProviderId provider : ProviderId.values()) {
            if (!result.contains(provider)) result.add(provider);
        }
        return result;
    }

    public static int rank(String source, List<ProviderId> priority) {
        if (source == null) return Integer.MAX_VALUE;
        for (int index = 0; index < priority.size(); index++) {
            if (priority.get(index).name().equalsIgnoreCase(source)) return index;
        }
        return Integer.MAX_VALUE;
    }
}
