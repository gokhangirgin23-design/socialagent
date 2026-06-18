package com.api.config;

import java.math.BigDecimal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Getter;
import lombok.Setter;

/**
 * Rapor fiyatları (app.report.price.*) — FAZ PAYMENT.
 * Mütabık varsayılanlar: NONE=200, OWN_ONLY=300, COMPETITOR_ONLY=300 (TL).
 * BOTH bu projede artık desteklenmiyor (ReportRequestService reddediyor) ama
 * ileride açılırsa diye fiyatı tutuldu. İleride tabloya alınabilir.
 */
@Component
@ConfigurationProperties(prefix = "app.report.price")
@Getter
@Setter
public class ReportPriceProperties {

    private BigDecimal none = new BigDecimal("200");
    private BigDecimal ownOnly = new BigDecimal("300");
    private BigDecimal competitorOnly = new BigDecimal("300");
    private BigDecimal both = new BigDecimal("400");
}
