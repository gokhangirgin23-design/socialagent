package com.api.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Apify'ın sektör keyword aramasıyla bulduğu SECTOR hesaplarından, gerçekte sektörle
 * alakasız olanları (ör. "Lüks Moda" aramasında kullanıcı adında "moda"/"luks" geçen bir
 * emlak hesabının yanlışlıkla eşleşmesi) tespit eder.
 *
 * Yöntem: her SECTOR hesabının post_analysis.visual.productCategory değerlerini kelimelere
 * ayırır; bir hesabın kelimeleri DİĞER hiçbir SECTOR hesabıyla örtüşmüyorsa (tamamen izole),
 * o hesap "alakasız" sayılır. Ekstra AI çağrısı yapılmaz — zaten hesaplanmış analiz verisi
 * kullanılır. En az 3 farklı SECTOR hesabı yoksa (yeterli karşılaştırma emsali yok) hiçbir
 * hesap elenmez — yanlış pozitif riskini azaltmak için.
 */
final class SectorRelevanceFilter {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SectorRelevanceFilter() {
    }

    /** analysis_json içindeki visual.productCategory alanını çeker (yoksa/bozuksa null). */
    static String extractProductCategory(String analysisJson) {
        if (analysisJson == null || analysisJson.isBlank()) {
            return null;
        }
        try {
            JsonNode category = MAPPER.readTree(analysisJson).path("visual").path("productCategory");
            return category.isTextual() ? category.asText() : null;
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * @param categoriesByAccount SECTOR hesap adı → o hesabın postlarındaki productCategory değerleri
     * @return alakasız bulunan hesap adları (boş olabilir)
     */
    static Set<String> findIrrelevantAccounts(Map<String, List<String>> categoriesByAccount) {
        if (categoriesByAccount.size() < 3) {
            return Set.of();
        }

        Map<String, Set<String>> tokensByAccount = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : categoriesByAccount.entrySet()) {
            Set<String> tokens = new HashSet<>();
            for (String category : entry.getValue()) {
                tokens.addAll(tokenize(category));
            }
            tokensByAccount.put(entry.getKey(), tokens);
        }

        Set<String> irrelevant = new HashSet<>();
        for (Map.Entry<String, Set<String>> entry : tokensByAccount.entrySet()) {
            String account = entry.getKey();
            Set<String> ownTokens = entry.getValue();
            if (ownTokens.isEmpty()) {
                continue; // kategori verisi yoksa yargılama, yanlış pozitif riski
            }
            boolean overlapsWithAnyOther = tokensByAccount.entrySet().stream()
                    .filter(e -> !e.getKey().equals(account))
                    .anyMatch(e -> !Collections.disjoint(e.getValue(), ownTokens));
            if (!overlapsWithAnyOther) {
                irrelevant.add(account);
            }
        }
        return irrelevant;
    }

    /** Türkçe metni küçük harfe çevirip kelimelere ayırır; 4 karakterden kısa (bağlaç vb.) kelimeler atlanır. */
    private static Set<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }
        String[] words = text.toLowerCase(Locale.forLanguageTag("tr")).split("[^\\p{L}]+");
        Set<String> tokens = new HashSet<>();
        for (String w : words) {
            if (w.length() >= 4) {
                tokens.add(w);
            }
        }
        return tokens;
    }

    // Test kolaylığı için (paket-private erişim testte kullanılıyor)
    static List<String> tokenizeForTest(String text) {
        return new ArrayList<>(tokenize(text));
    }
}
