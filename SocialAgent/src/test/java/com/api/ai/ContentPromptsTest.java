package com.api.ai;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

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

        String prompt = ContentPrompts.forVisual(brandDna, "# Rapor", "POST", 0, null,
                false, null, null, null);

        assertTrue(prompt.contains("İNSAN/MODEL KULLANIMI"));
        assertTrue(prompt.contains("avukat-müvekkil görüşmesi sahneleri"));
    }

    @Test
    void humanPresenceAlaniBosseGorselPromptundaBlokEklenmez() {
        String brandDna = """
                {"mainProductOrService":"Hukuk danışmanlığı"}
                """;

        String prompt = ContentPrompts.forVisual(brandDna, "# Rapor", "POST", 0, null,
                false, null, null, null);

        assertTrue(!prompt.contains("İNSAN/MODEL KULLANIMI"));
    }

    @Test
    void brandDnaPromptuHumanPresenceAlaniniIster() {
        String prompt = ContentPrompts.forBrandDna(null, "# Rapor", null, null);

        assertTrue(prompt.contains("humanPresence"));
    }
}
