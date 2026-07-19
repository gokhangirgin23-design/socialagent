package com.api.config;

import java.math.BigDecimal;
import java.util.List;

import com.api.entity.ContentType;

/**
 * Statik kredi kataloğu (FAZ CREDIT). Paket fiyat/kredi ve ürün başına kredi tüketimi
 * TEK bu sınıfta sabittir — env değişkeni eklenmez. Paket/fiyat değişikliği için bu
 * sınıf düzenlenip deploy edilir.
 *
 * REEL ve eski ALL içerik tipi katalogda YOK — ikisi de kapalı ürünler.
 */
public final class CreditCatalog {

    private CreditCatalog() {
    }

    /** Satın alınabilir kredi paketi (satın alma anında snapshot olarak loglanır). */
    public record CreditPackage(String code, String name, BigDecimal priceTl, int credits, boolean featured) {
    }

    public static final CreditPackage STARTER = new CreditPackage("STARTER", "Starter", new BigDecimal("349"), 60, false);
    public static final CreditPackage STANDARD = new CreditPackage("STANDARD", "Standard", new BigDecimal("699"), 150, true);
    public static final CreditPackage PRO = new CreditPackage("PRO", "Pro", new BigDecimal("1199"), 300, false);
    public static final CreditPackage AGENCY = new CreditPackage("AGENCY", "Agency", new BigDecimal("2499"), 700, false);

    private static final List<CreditPackage> PACKAGES = List.of(STARTER, STANDARD, PRO, AGENCY);

    // Ürün başına kredi tüketimi
    public static final int REPORT_CREDIT_COST = 20;
    public static final int POST_CREDIT_COST = 15;
    public static final int STORY_CREDIT_COST = 18;
    public static final int CAROUSEL_CREDIT_COST = 35;

    /** Tüm paketlerin immutable listesi (satın alma ekranı için). */
    public static List<CreditPackage> packages() {
        return PACKAGES;
    }

    /** Paket kodundan paketi bulur; yoksa null. */
    public static CreditPackage findPackage(String code) {
        if (code == null) {
            return null;
        }
        for (CreditPackage p : PACKAGES) {
            if (p.code().equalsIgnoreCase(code)) {
                return p;
            }
        }
        return null;
    }

    /** İçerik tipine göre kredi maliyeti. REEL katalogda yok (kapalı ürün). */
    public static int creditCostFor(ContentType type) {
        return switch (type) {
            case STORY -> STORY_CREDIT_COST;
            case CAROUSEL -> CAROUSEL_CREDIT_COST;
            case POST -> POST_CREDIT_COST;
            case REEL -> throw new IllegalArgumentException("REEL içerik üretimi kapalı; kredi maliyeti tanımlı değil.");
        };
    }
}
