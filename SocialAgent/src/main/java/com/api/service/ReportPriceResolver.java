package com.api.service;

import java.math.BigDecimal;

import org.springframework.stereotype.Service;

import com.api.config.ReportPriceProperties;
import com.api.entity.AnalysisMode;

/**
 * report_type (AnalysisMode) → rapor fiyatı eşlemesi (FAZ PAYMENT).
 * Fiyatlar app.report.price.* ayarlarından gelir (ReportPriceProperties).
 */
@Service
public class ReportPriceResolver {

    private final ReportPriceProperties prices;

    public ReportPriceResolver(ReportPriceProperties prices) {
        this.prices = prices;
    }

    /** Verilen mod için fiyatı döndürür. */
    public BigDecimal priceFor(AnalysisMode mode) {
        if (mode == null) {
            return prices.getNone();
        }
        switch (mode) {
            case OWN_ONLY:
                return prices.getOwnOnly();
            case COMPETITOR_ONLY:
                return prices.getCompetitorOnly();
            case BOTH:
                return prices.getBoth();
            case NONE:
            default:
                return prices.getNone();
        }
    }
}
