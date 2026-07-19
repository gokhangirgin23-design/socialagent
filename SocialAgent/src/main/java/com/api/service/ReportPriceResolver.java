package com.api.service;

import org.springframework.stereotype.Service;

import com.api.config.CreditCatalog;
import com.api.entity.AnalysisMode;

/**
 * report_type (AnalysisMode) → rapor kredi maliyeti eşlemesi (FAZ CREDIT).
 * Tüm rapor tipleri için tek kredi maliyeti uygulanır: CreditCatalog.REPORT_CREDIT_COST.
 */
@Service
public class ReportPriceResolver {

    /** Verilen mod için kredi maliyeti (mod'dan bağımsız — tek fiyat). */
    public int creditCostFor(AnalysisMode mode) {
        return CreditCatalog.REPORT_CREDIT_COST;
    }
}
