package com.api.ai;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.api.entity.VisualStyle;

/**
 * ContentPrompts için birim testleri.
 *
 * Doğrulanan davranış: Brand DNA JSON'undaki humanPresence alanı, görsel üretim
 * prompt'unda ayrı ve belirgin bir blok olarak yer almalı — raporun rekabet analizinden
 * çıkan "insan/model kullanımı etkileşimi artırıyor" gibi somut bulgular, kalabalık bir
 * "görsel stil" notunun içinde kaybolmasın diye.
 */
class ContentPromptsTest {

    @Test
    void humanPresenceAlaniGorselPromptundaAyriBlokOlarakYerAlir() {
        String brandDna = """
                {"mainProductOrService":"Hukuk danışmanlığı",
                 "visualStyle":{"colorPalette":["Kahverengi"]},
                 "humanPresence":"Fotoğraflarda gerçek insan/model bulunmalı — avukat-müvekkil görüşmesi sahneleri gösterilmeli."}
                """;

        String prompt = ContentPrompts.forVisual(brandDna, "POST", 0, null,
                false, null, null, null, VisualStyle.PREMIUM);

        assertTrue(prompt.contains("İNSAN/MODEL KULLANIMI"));
        assertTrue(prompt.contains("avukat-müvekkil görüşmesi sahneleri"));
    }

    @Test
    void humanPresenceAlaniBosseGorselPromptundaBlokEklenmez() {
        String brandDna = """
                {"mainProductOrService":"Hukuk danışmanlığı"}
                """;

        String prompt = ContentPrompts.forVisual(brandDna, "POST", 0, null,
                false, null, null, null, VisualStyle.PREMIUM);

        assertTrue(!prompt.contains("İNSAN/MODEL KULLANIMI"));
    }

    @Test
    void brandDnaPromptuHumanPresenceAlaniniIster() {
        String prompt = ContentPrompts.forBrandDna(null, null, null);

        assertTrue(prompt.contains("humanPresence"));
    }

    @Test
    void brandDnaPromptuSignatureBackgroundAlaniniIster() {
        String prompt = ContentPrompts.forBrandDna(null, null, null);

        assertTrue(prompt.contains("signatureBackground"));
    }

    @Test
    void visualStyleIcIceNesneOlsaBileTumAltAlanlarKaybolmadanCikar() {
        // Eski "ilk 100 ham karakteri al" yöntemi girintili/çok satırlı nesnelerde
        // lighting gibi sonradan gelen alt alanları kesip kaybediyordu.
        String brandDna = """
                {"mainProductOrService":"Restoran",
                 "visualStyle":{
                     "colorPalette":"mavi, beyaz",
                     "atmosphere":"sakin ve ferah",
                     "shootingStyle":"editorial ve lifestyle",
                     "lighting":"doğal ışık"
                 }}
                """;

        String prompt = ContentPrompts.forVisual(brandDna, "POST", 0, null,
                false, null, null, null, VisualStyle.PREMIUM);

        assertTrue(prompt.contains("lighting: doğal ışık"),
                "lighting alanı prompt'a ulaşmalı: " + prompt);
    }

    @Test
    void signatureBackgroundYokDiyorsaHicbirZamanKullanilmaz() {
        String brandDna = """
                {"mainProductOrService":"Restoran",
                 "typicalBackground":"iç mekan",
                 "signatureBackground":"belirgin bir imza arka plan yok"}
                """;

        for (int i = 0; i < 30; i++) {
            String prompt = ContentPrompts.forVisual(brandDna, "POST", 0, null,
                    false, null, null, null, VisualStyle.PREMIUM);
            assertTrue(!prompt.contains("imza varyasyon"));
            assertTrue(prompt.contains("Tipik arka plan: iç mekan"));
        }
    }

    @Test
    void signatureBackgroundVarsaBazenTypicalBackgroundYerineKullanilir() {
        String brandDna = """
                {"mainProductOrService":"Restoran",
                 "typicalBackground":"iç mekan",
                 "signatureBackground":"Boğaz manzaralı teras"}
                """;

        boolean sawSignature = false;
        for (int i = 0; i < 50 && !sawSignature; i++) {
            String prompt = ContentPrompts.forVisual(brandDna, "POST", 0, null,
                    false, null, null, null, VisualStyle.PREMIUM);
            if (prompt.contains("imza varyasyon") && prompt.contains("Boğaz manzaralı teras")) {
                sawSignature = true;
            }
        }
        assertTrue(sawSignature, "50 denemede en az 1 kez imza arka plan kullanılmalıydı (~%28 olasılık)");
    }

    // ============================================================
    // istek2.md — görsel stil seçimi (Premium/Doğal Üret)
    // ============================================================

    @Test
    void naturalStilSeciminiPromptaDogalBlokEklenir() {
        String prompt = ContentPrompts.forVisual(null, "POST", 0, null,
                false, null, null, null, VisualStyle.NATURAL);

        assertTrue(prompt.contains("GÖRSEL STİL: DOĞAL"));
        assertTrue(prompt.contains("UGC"));
        assertFalse(prompt.contains("GÖRSEL STİL: PREMIUM"));
    }

    @Test
    void premiumStilSeciminiPromptaPremiumBlokEklenir() {
        String prompt = ContentPrompts.forVisual(null, "POST", 0, null,
                false, null, null, null, VisualStyle.PREMIUM);

        assertTrue(prompt.contains("GÖRSEL STİL: PREMIUM"));
        assertTrue(prompt.contains("stüdyo kalitesinde"));
        assertFalse(prompt.contains("GÖRSEL STİL: DOĞAL"));
    }

    @Test
    void visualStyleNullIsePremiumVarsayilanKullanilir() {
        // ContentRequestService.parseVisualStyle zaten null'ı PREMIUM'a çevirir; burada
        // ContentPrompts tarafının da null'da PREMIUM davranışına düştüğü ayrıca doğrulanır.
        String prompt = ContentPrompts.forVisual(null, "POST", 0, null,
                false, null, null, null, null);

        assertTrue(prompt.contains("GÖRSEL STİL: PREMIUM"));
    }
}
