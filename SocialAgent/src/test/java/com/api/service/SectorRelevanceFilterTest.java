package com.api.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * SectorRelevanceFilter için birim testi.
 *
 * Doğrulanan davranış: Apify'ın sektör keyword aramasıyla bulduğu SECTOR hesapları arasında,
 * gerçek konusu (productCategory) diğer hiçbir sektör hesabıyla örtüşmeyen bir hesap varsa
 * (ör. "Lüks Moda" aramasında yanlışlıkla eşleşen bir emlak hesabı — Sur Balık/Moda vakasından),
 * bu hesap "alakasız" sayılır. Yeterli emsal (>=3 farklı hesap) yoksa hiçbir hesap elenmez.
 */
class SectorRelevanceFilterTest {

    @Test
    void digerSektorHesaplariylaHicOrtusmeyenHesapAlakasizSayilir() {
        Map<String, List<String>> categories = new LinkedHashMap<>();
        categories.put("moda_hesap_1", List.of("kadın giyim"));
        categories.put("moda_hesap_2", List.of("erkek giyim"));
        categories.put("emlak_hesap", List.of("gayrimenkul"));

        Set<String> irrelevant = SectorRelevanceFilter.findIrrelevantAccounts(categories);

        assertEquals(Set.of("emlak_hesap"), irrelevant);
    }

    @Test
    void ortakKelimesiOlanHesaplarAlakasizSayilmaz() {
        Map<String, List<String>> categories = new LinkedHashMap<>();
        categories.put("moda_hesap_1", List.of("kadın giyim"));
        categories.put("moda_hesap_2", List.of("erkek giyim"));
        categories.put("moda_hesap_3", List.of("çocuk giyim"));

        Set<String> irrelevant = SectorRelevanceFilter.findIrrelevantAccounts(categories);

        assertTrue(irrelevant.isEmpty());
    }

    @Test
    void ikiHesaptanAzsaHicbirHesapElenmez() {
        // Yalnızca 2 farklı SECTOR hesabı varsa (yetersiz emsal), yanlış pozitif riskinden
        // kaçınmak için hiçbir hesap alakasız sayılmaz — ne kadar farklı olurlarsa olsunlar.
        Map<String, List<String>> categories = new LinkedHashMap<>();
        categories.put("moda_hesap_1", List.of("kadın giyim"));
        categories.put("emlak_hesap", List.of("gayrimenkul"));

        Set<String> irrelevant = SectorRelevanceFilter.findIrrelevantAccounts(categories);

        assertTrue(irrelevant.isEmpty());
    }

    @Test
    void kategoriVerisiOlmayanHesapYargilanmaz() {
        Map<String, List<String>> categories = new LinkedHashMap<>();
        categories.put("moda_hesap_1", List.of("kadın giyim"));
        categories.put("moda_hesap_2", List.of("erkek giyim"));
        categories.put("veri_yok_hesap", List.of()); // boş kategori listesi

        Set<String> irrelevant = SectorRelevanceFilter.findIrrelevantAccounts(categories);

        assertTrue(irrelevant.isEmpty());
    }

    @Test
    void extractProductCategoryVisualAltAlanindanCeker() {
        String json = "{\"visual\":{\"productCategory\":\"gayrimenkul\"}}";

        assertEquals("gayrimenkul", SectorRelevanceFilter.extractProductCategory(json));
    }

    @Test
    void extractProductCategoryBozukJsonDaNullDoner() {
        assertEquals(null, SectorRelevanceFilter.extractProductCategory("{bozuk"));
        assertEquals(null, SectorRelevanceFilter.extractProductCategory(null));
    }

    // ============================================================
    // BACKEND-TODO SORUN 1, madde 1.3 — subsector-aware overload
    // ============================================================

    @Test
    void altSektorleOrtusenHesapDigerleriyleHicOrtusmeseBileAlakasizSayilmaz() {
        // "Takı" alt sektörü seçilmiş; productCategory'si "takı" içeren hesap, diğer sektör
        // hesaplarıyla (genel "moda") hiç örtüşmese bile KESİN alakalı sayılmalı.
        Map<String, List<String>> categories = new LinkedHashMap<>();
        categories.put("moda_hesap_1", List.of("kadın giyim"));
        categories.put("moda_hesap_2", List.of("erkek giyim"));
        categories.put("taki_hesap", List.of("takı"));

        Set<String> irrelevant = SectorRelevanceFilter.findIrrelevantAccounts(categories, Set.of("takı"));

        assertTrue(irrelevant.isEmpty(), "taki_hesap alt sektörle örtüştüğü için elenmemeli: " + irrelevant);
    }

    @Test
    void altSektorleOrtusmeyenHesapIcinMevcutIzolasyonSezgisiUygulanir() {
        // subsectorTokens verilse bile, örtüşmeyen hesaplar için eski davranış (izolasyon
        // sezgisi) aynen çalışmaya devam eder — emlak_hesap ne diğer hesaplarla ne de alt
        // sektörle örtüşüyor, alakasız sayılmalı.
        Map<String, List<String>> categories = new LinkedHashMap<>();
        categories.put("moda_hesap_1", List.of("kadın giyim"));
        categories.put("moda_hesap_2", List.of("erkek giyim"));
        categories.put("emlak_hesap", List.of("gayrimenkul"));

        Set<String> irrelevant = SectorRelevanceFilter.findIrrelevantAccounts(categories, Set.of("takı"));

        assertEquals(Set.of("emlak_hesap"), irrelevant);
    }

    @Test
    void subsectorTokenSetiBossaDavranisBirebirEskisiyleAyni() {
        Map<String, List<String>> categories = new LinkedHashMap<>();
        categories.put("moda_hesap_1", List.of("kadın giyim"));
        categories.put("moda_hesap_2", List.of("erkek giyim"));
        categories.put("emlak_hesap", List.of("gayrimenkul"));

        Set<String> withEmptySet = SectorRelevanceFilter.findIrrelevantAccounts(categories, Set.of());
        Set<String> withNull = SectorRelevanceFilter.findIrrelevantAccounts(categories, null);
        Set<String> legacyOverload = SectorRelevanceFilter.findIrrelevantAccounts(categories);

        assertEquals(Set.of("emlak_hesap"), withEmptySet);
        assertEquals(Set.of("emlak_hesap"), withNull);
        assertEquals(legacyOverload, withEmptySet);
    }

    @Test
    void tokenizeDortKarakterAltiKelimeleriAtlarPaketPrivateErisilebilir() {
        // tokenize artık paket-private (TargetResolver de kullanıyor) — 4+ karakter kuralı korunur.
        Set<String> tokens = SectorRelevanceFilter.tokenize("Ev ve Yaşam Dekorasyon");
        assertTrue(tokens.contains("yaşam"));
        assertTrue(tokens.contains("dekorasyon"));
        assertTrue(!tokens.contains("ve")); // 2 karakter, atlanır
    }
}
