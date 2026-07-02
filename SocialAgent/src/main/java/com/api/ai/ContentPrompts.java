package com.api.ai;

/**
 * İçerik üretimi için AI prompt'ları.
 * Brand DNA, görsel ve caption üretiminde kullanılan prompt şablonları.
 */
public final class ContentPrompts {

    private ContentPrompts() {
    }

    /**
     * Brand DNA üretimi için OpenAI prompt'u.
     * Son N posttaki metrikleri + rapor içeriğini + görsel analiz verilerini alır;
     * markanın kişiliğini ve görsel kimliğini JSON olarak çıkarır.
     *
     * @param postsContext   son 10 postun caption özeti
     * @param reportContent  rapor Markdown içeriği
     * @param visualPatterns görsel analizden çıkarılan ürün/stil özeti (null olabilir)
     * @param sectorContext  kullanıcının güncel sektör/alt-sektör adı — mainProductOrService için sert kısıt
     */
    public static String forBrandDna(String postsContext, String reportContent,
                                     String visualPatterns, String sectorContext) {
        // Sektör bilgisi varsa en başa mutlak kısıt olarak yaz
        String sectorBlock = (sectorContext != null && !sectorContext.isBlank())
                ? "!!! MUTLAK KISIT — SEKTÖR BİLGİSİ !!!\n"
                  + "Bu kullanıcı şu sektörde faaliyet gösteriyor: " + sectorContext + "\n"
                  + "mainProductOrService alanı bu sektöre uygun bir ürün/hizmet OLMAK ZORUNDA.\n"
                  + "Görsellerde başka sektörden ürün ASLA gösterilmemelidir.\n\n"
                : "";
        String posts = (postsContext != null && !postsContext.isBlank())
                ? "Kullanıcının son paylaşım caption'ları:\n" + postsContext + "\n\n"
                : "";
        String visuals = (visualPatterns != null && !visualPatterns.isBlank())
                ? "Görsellerin analiz verisi (ürün kategorisi, atmosfer, renkler, çekim stili):\n" + visualPatterns
                  + "\n[KENDİ] etiketli görseller markanın kendi kimliğidir ve DNA'nın ana kaynağıdır; "
                  + "[RAKİP]/[SEKTÖR] etiketliler yalnızca sektör bağlamı ve ilham içindir, markanın kimliği olarak KOPYALANMAZ.\n\n"
                : "";
        return """
                %s%s%sBu analiz raporunu incele:

                %s

                Yukarıdaki tüm verilerden yola çıkarak bu markanın Brand DNA'sını çıkar ve JSON formatında üret.

                Aşağıdaki alanları oluştur:
                - mainProductOrService: Markanın SATIŞ YAPTIĞI ANA ürün veya hizmet (spesifik olmalı; ör: "Adana kebap, döner ve ızgara yemekleri" veya "kadın spor ayakkabısı"). Sektör kısıtına uygun olmalı. Bu alan görsel üretimde kritik öneme sahiptir.
                - brandPersonality: Markanın kişilik özellikleri (3-5 madde)
                - toneOfVoice: Ses tonu ve iletişim stili
                - visualStyle: Görsel kimlik — renk, ışık, atmosfer, çekim stili (görsel analizden çıkar; detaylı olmalı)
                - typicalBackground: Markaya özgü arka plan tercihleri (ör: ahşap masa, beyaz fon, dış mekan — görselden çıkar)
                - typicalAtmosphere: Markaya özgü atmosfer (ör: sıcak ve rustik, modern ve minimal, canlı ve renkli)
                - colorPalette: Ana marka renkleri (3-5 renk, isimleriyle)
                - compositionRules: Kompozisyon kuralları (close-up mu, flat-lay mi, lifestyle mı?)
                - propsAndDecorStyle: Sıkça kullanılan aksesuar/dekor (ör: tahta kesme tahtası, çiçek, kumaş)
                - designRules: Tasarım kuralları
                - preferredContentTypes: Tercih edilen içerik türleri
                - avoid: Kesinlikle KAÇINILMASI GEREKENLER — yanlış ürün, yanlış renk, yanlış ortam gibi
                - improvementGoals: Gelişim hedefleri

                ÖNEMLI: mainProductOrService ve visualStyle alanları görsel üretim için kullanılacak; mümkün olduğunca spesifik ve detaylı doldur.
                Eksik bilgi varsa analiz raporundan ve görsel verilerden çıkarım yap.
                JSON dışında açıklama yazma.
                """.formatted(sectorBlock, posts, visuals, reportContent);
    }

    /**
     * Görsel üretimi için prompt.
     * Brand DNA'daki mainProductOrService ve visualStyle alanları ürün tutarlılığını sağlar.
     * editInstruction varsa görsel üretimde en yüksek öncelikle uygulanır.
     * sectorContext varsa sektör kısıtı brand DNA'dan önce en güçlü biçimde enjekte edilir.
     *
     * @param brandDnaJson     Brand DNA JSON (null ise markalamadan bağımsız üretilir)
     * @param reportContent    rapor içeriği (gelişim önerileri alınır)
     * @param contentType      POST|STORY|CAROUSEL|REEL|ALL
     * @param slideIndex       carousel için slayt numarası (0 = tek görsel)
     * @param slideRole        carousel için slayt rolü (HOOK|CONTENT|CTA)
     * @param includeText      görselde metin olsun mu
     * @param editInstruction  kullanıcının düzenleme talimatı (null ise ilk üretim)
     * @param sectorContext    kullanıcının sektör/alt sektörü (null olabilir)
     * @param productContext   Gemini Vision'ın ürün görselinden çıkardığı JSON (productType, idealBackground, avoidBackground)
     */
    public static String forVisual(String brandDnaJson, String reportContent,
                                   String contentType, int slideIndex, String slideRole,
                                   boolean includeText, String editInstruction,
                                   String sectorContext, String productContext) {
        StringBuilder sb = new StringBuilder();

        // Düzenleme talimatı varsa en üste ve en güçlü biçimde yaz — AI bunu görmezden gelemez
        if (editInstruction != null && !editInstruction.isBlank()) {
            sb.append("=== ZORUNLU DEĞİŞİKLİK TALİMATI (EN YÜKSEK ÖNCELİK) ===\n");
            sb.append("Kullanıcı şu değişikliği kesinlikle istiyor:\n");
            sb.append(editInstruction).append("\n");
            sb.append("Bu talimatı tam olarak uygula. Bir önceki görselin bu yönü KABUL EDİLMEDİ.\n");
            sb.append("=== ZORUNLU DEĞİŞİKLİK SONU ===\n\n");
        }

        // Ürün görseli yüklendiyse: ürünü koru, yalnızca arka planı değiştir
        if (productContext != null && !productContext.isBlank()) {
            sb.append("=== REFERANS ÜRÜN KORUMA TALİMATI ===\n");
            sb.append("Kullanıcı kendi ürününün fotoğrafını referans olarak yükledi.\n");
            sb.append("ZORUNLU KURALLAR:\n");
            sb.append("1. Referans görseldeki ÜRÜNÜN KENDİSİ (şekil, renk, model, marka) AYNEN korunacak.\n");
            sb.append("2. YALNIZCA arka plan / ortam değiştirilecek — ürüne dokunulmayacak.\n");
            sb.append("3. Referans görseldeki üründen FARKLI başka hiçbir ürün, nesne veya öğe eklenmeyecek.\n");
            sb.append("4. Ürünü kaldırıp yerine başka bir şey koymak KESİNLİKLE YASAK.\n");
            sb.append("=== REFERANS ÜRÜN KORUMA SONU ===\n\n");
        }

        // Kullanıcının gerçek sektörü — brand DNA'dan daha güvenilir; sert kısıt
        if (sectorContext != null && !sectorContext.isBlank()) {
            sb.append("=== SEKTÖR KISITI (MUTLAK ÖNCELİK) ===\n");
            sb.append("Bu kullanıcının faaliyet gösterdiği sektör: ").append(sectorContext).append("\n");
            sb.append("Görselde SADECE bu sektöre uygun ürün, hizmet veya ortam göster.\n");
            sb.append("Başka sektörden ürün, yiyecek veya nesne ASLA yer almasın.\n");
            sb.append("=== SEKTÖR KISITI SONU ===\n\n");
        }

        // Ürün görseli analizi: Gemini Vision'ın belirlediği ürün tipi ve arka plan kısıtları
        if (productContext != null && !productContext.isBlank()) {
            String productType = extractJsonField(productContext, "productType");
            String idealBackground = extractJsonField(productContext, "idealBackground");
            String avoidBackground = extractJsonField(productContext, "avoidBackground");
            sb.append("=== YÜKLENEN ÜRÜN GÖRSELİ ANALİZİ ===\n");
            if (productType != null)     sb.append("Ürün: ").append(productType).append("\n");
            if (idealBackground != null) {
                // avoidBackground listedeki yasak kelimeler ayıklanarak rastgele seçilir
                String selected = pickRandom(idealBackground, avoidBackground);
                sb.append("Bu üretim için arka plan: ").append(selected).append("\n");
            }
            if (avoidBackground != null) sb.append("KESINLIKLE KULLANMA: ").append(avoidBackground).append("\n");
            sb.append("Referans görseldeki ürünü bu arka plan ile sun.\n");
            sb.append("=== ÜRÜN GÖRSELİ ANALİZİ SONU ===\n\n");
        }

        if (brandDnaJson != null && !brandDnaJson.isBlank()) {
            sb.append("=== MARKA DNA'SI (SADIK KAL) ===\n");
            sb.append(brandDnaJson).append("\n\n");
            // Ürün görseli yoksa brand DNA'daki ürün tanımıyla görsel yönlendirilir.
            // Ürün görseli VARSA referans görsel otoritedir — mainProductOrService talimatı atlanır
            // (aksi hâlde model brand DNA ürününü üretip referans ürünü görmezden gelebilir).
            if (productContext == null || productContext.isBlank()) {
                String mainProduct = extractJsonField(brandDnaJson, "mainProductOrService");
                if (mainProduct != null && !mainProduct.isBlank()) {
                    sb.append("KRİTİK — ÜRÜN TUTARLILIĞI:\n");
                    sb.append("Bu marka '").append(mainProduct).append("' satar/sunar.\n");
                    sb.append("Görselde SADECE bu markanın ürünlerini göster. ");
                    sb.append("Asla başka ürün, yiyecek veya kategori gösterme.\n");
                    sb.append("Ürün görseli verilmemiş olsa bile sadece bu ürün kategorisini yansıt.\n\n");
                }
            }

            // GÖRSEL KİMLİK: DNA'dan görsel stil alanlarını çıkar (productContext olsa da olmasa da)
            String visualStyle = extractJsonField(brandDnaJson, "visualStyle");
            String colorPalette = extractJsonField(brandDnaJson, "colorPalette");
            String typicalBackground = extractJsonField(brandDnaJson, "typicalBackground");
            String typicalAtmosphere = extractJsonField(brandDnaJson, "typicalAtmosphere");
            if (visualStyle != null || colorPalette != null || typicalBackground != null || typicalAtmosphere != null) {
                sb.append("=== GÖRSEL KİMLİK ===\n");
                if (visualStyle != null) sb.append("Görsel stil: ").append(visualStyle).append("\n");
                if (colorPalette != null) sb.append("Renk paleti: ").append(colorPalette).append("\n");
                if (typicalBackground != null) sb.append("Tipik arka plan: ").append(typicalBackground).append("\n");
                if (typicalAtmosphere != null) sb.append("Tipik atmosfer: ").append(typicalAtmosphere).append("\n");
                sb.append("=== GÖRSEL KİMLİK SONU ===\n\n");
            }
        }

        if (reportContent != null && !reportContent.isBlank()) {
            String reportSnippet = reportContent.length() > 1000
                    ? reportContent.substring(0, 1000) + "..."
                    : reportContent;
            sb.append("Gelişim raporundan uygulanacak öneriler:\n").append(reportSnippet).append("\n\n");
        }

        // Format bazlı talimat
        String formatLabel = switch (contentType.toUpperCase()) {
            case "STORY" -> "Instagram Story (9:16 dikey format)";
            case "CAROUSEL" -> "Instagram Carousel slaydı (" + slideRole + " — slayt " + (slideIndex + 1) + ")";
            case "REEL" -> "Instagram Reel kapak görseli (9:16 dikey format — portrait)";
            default -> "Instagram Post (kare veya 4:5 format)";
        };
        sb.append("Instagram için premium, yüksek kaliteli bir ").append(formatLabel).append(" oluştur.\n\n");
        sb.append("Modern ve estetik görünüm oluştur. Marka DNA'sındaki renk, atmosfer ve stil tercihlerine uy.\n");
        if (!includeText) {
            sb.append("Görselde metin, yazı veya slogan OLMAYACAK.\n");
        }
        sb.append("Görselde platform adları (reels, story, spectiqs, instagram gibi) OLMAYACAK.\n");

        return sb.toString();
    }

    /**
     * Virgülle ayrılmış listeden rastgele bir seçenek döner.
     * avoidList verilirse o listedeki kelimelerden herhangi birini içeren seçenekler elenir.
     * Her üretim çağrısında farklı arka plan / ortam seçilmesini sağlar.
     */
    private static String pickRandom(String commaSeparated, String avoidList) {
        if (commaSeparated == null || commaSeparated.isBlank()) return commaSeparated;
        String[] options = commaSeparated.split(",");

        // avoidList'teki anahtar kelimeleri içeren seçenekleri elenmiş liste dışında bırak
        String[] avoidTokens = (avoidList != null && !avoidList.isBlank())
                ? avoidList.toLowerCase().split(",") : new String[0];

        java.util.List<String> valid = new java.util.ArrayList<>();
        for (String opt : options) {
            String lower = opt.toLowerCase();
            boolean blocked = false;
            for (String avoid : avoidTokens) {
                String token = avoid.trim();
                if (!token.isBlank() && lower.contains(token)) { blocked = true; break; }
            }
            if (!blocked) valid.add(opt.trim());
        }

        // Geçerli seçenek yoksa filtreden bağımsız rastgele seç
        String[] pool = valid.isEmpty() ? options : valid.toArray(new String[0]);
        return pool[(int) (Math.random() * pool.length)].trim();
    }

    /**
     * Brand DNA JSON'undan belirtilen alanı basit string eşleşmesiyle çeker.
     * Parse bağımlılığı olmadan hızlı alan okuma için.
     */
    private static String extractJsonField(String json, String fieldName) {
        if (json == null || json.isBlank()) return null;
        String key = "\"" + fieldName + "\"";
        int idx = json.indexOf(key);
        if (idx < 0) return null;
        int colon = json.indexOf(':', idx + key.length());
        if (colon < 0) return null;
        int valueStart = colon + 1;
        while (valueStart < json.length() && Character.isWhitespace(json.charAt(valueStart))) valueStart++;
        if (valueStart >= json.length()) return null;
        char first = json.charAt(valueStart);
        if (first == '"') {
            int end = json.indexOf('"', valueStart + 1);
            return end > valueStart ? json.substring(valueStart + 1, end) : null;
        }
        // Array veya nesne ise ilk 100 karakteri al
        int end = Math.min(valueStart + 100, json.length());
        return json.substring(valueStart, end).replaceAll("[\\n\\r]", " ").trim();
    }

    /**
     * Instagram Reel / video üretimi için prompt.
     * editInstruction varsa en yüksek öncelikle uygulanır.
     * sectorContext varsa sektör kısıtı brand DNA'dan önce enjekte edilir.
     *
     * @param brandDnaJson    Brand DNA JSON (null olabilir)
     * @param reportContent   rapor içeriği (gelişim önerileri alınır)
     * @param editInstruction kullanıcının düzenleme talimatı (null ise ilk üretim)
     * @param sectorContext   kullanıcının sektör/alt sektörü (null olabilir)
     * @param productContext  Gemini Vision'ın ürün görselinden çıkardığı JSON (null olabilir)
     */
    public static String forVideo(String brandDnaJson, String reportContent,
                                  String editInstruction, String sectorContext, String productContext) {
        StringBuilder sb = new StringBuilder();

        // Düzenleme talimatı varsa en üste ve en güçlü biçimde yaz
        if (editInstruction != null && !editInstruction.isBlank()) {
            sb.append("=== ZORUNLU DEĞİŞİKLİK TALİMATI (EN YÜKSEK ÖNCELİK) ===\n");
            sb.append("Kullanıcı şu değişikliği kesinlikle istiyor:\n");
            sb.append(editInstruction).append("\n");
            sb.append("Bu talimatı tam olarak uygula.\n");
            sb.append("=== ZORUNLU DEĞİŞİKLİK SONU ===\n\n");
        }

        // Ürün görseli yüklendiyse: ürünü koru, yalnızca arka planı değiştir
        if (productContext != null && !productContext.isBlank()) {
            sb.append("=== REFERANS ÜRÜN KORUMA TALİMATI ===\n");
            sb.append("Kullanıcı kendi ürününün fotoğrafını referans olarak yükledi.\n");
            sb.append("ZORUNLU KURALLAR:\n");
            sb.append("1. Referans görseldeki ÜRÜNÜN KENDİSİ (şekil, renk, model, marka) AYNEN korunacak.\n");
            sb.append("2. YALNIZCA arka plan / ortam değiştirilecek — ürüne dokunulmayacak.\n");
            sb.append("3. Referans görseldeki üründen FARKLI başka hiçbir ürün veya nesne eklenmeyecek.\n");
            sb.append("=== REFERANS ÜRÜN KORUMA SONU ===\n\n");
        }

        // Kullanıcının gerçek sektörü — brand DNA'dan daha güvenilir; sert kısıt
        if (sectorContext != null && !sectorContext.isBlank()) {
            sb.append("=== SEKTÖR KISITI (MUTLAK ÖNCELİK) ===\n");
            sb.append("Bu kullanıcının faaliyet gösterdiği sektör: ").append(sectorContext).append("\n");
            sb.append("Videoda SADECE bu sektöre uygun ürün, hizmet veya ortam göster.\n");
            sb.append("Başka sektörden ürün veya nesne ASLA yer almasın.\n");
            sb.append("=== SEKTÖR KISITI SONU ===\n\n");
        }

        // Ürün görseli analizi: ürün tipine göre arka plan kısıtı
        if (productContext != null && !productContext.isBlank()) {
            String productType = extractJsonField(productContext, "productType");
            String idealBackground = extractJsonField(productContext, "idealBackground");
            String avoidBackground = extractJsonField(productContext, "avoidBackground");
            sb.append("=== YÜKLENEN ÜRÜN GÖRSELİ ANALİZİ ===\n");
            if (productType != null)     sb.append("Ürün: ").append(productType).append("\n");
            if (idealBackground != null) {
                // avoidBackground listedeki yasak kelimeler ayıklanarak rastgele seçilir
                String selected = pickRandom(idealBackground, avoidBackground);
                sb.append("Bu üretim için ortam/arka plan: ").append(selected).append("\n");
            }
            if (avoidBackground != null) sb.append("KESİNLİKLE KULLANMA: ").append(avoidBackground).append("\n");
            sb.append("=== ÜRÜN GÖRSELİ ANALİZİ SONU ===\n\n");
        }

        if (brandDnaJson != null && !brandDnaJson.isBlank()) {
            sb.append("Bu markanın Brand DNA'sına sadık kal:\n").append(brandDnaJson).append("\n\n");
            String mainProduct = extractJsonField(brandDnaJson, "mainProductOrService");
            if (mainProduct != null && !mainProduct.isBlank()) {
                sb.append("Bu marka '").append(mainProduct).append("' satar. Videoda sadece bu ürünü göster.\n\n");
            }
        }

        if (reportContent != null && !reportContent.isBlank()) {
            String snippet = reportContent.length() > 800
                    ? reportContent.substring(0, 800) + "..."
                    : reportContent;
            sb.append("Gelişim raporundaki önerileri uygula. Rapor özeti:\n").append(snippet).append("\n\n");
        }

        sb.append("Instagram Reel için 9:16 dikey formatta, maksimum 30 saniyelik kısa bir ürün tanıtım videosu oluştur.\n");
        sb.append("Video akıcı, dinamik ve estetik olmalı; modern geçiş efektleri kullanılabilir.\n");
        sb.append("Ürünü veya marka kimliğini görsel olarak güçlü biçimde vurgula.\n");
        sb.append("Görsellerde platform adları (reels, story, spectiqs vb.) OLMAYACAK.\n");
        sb.append("Gerçek kişilerin, ünlülerin veya tanınmış kişiliklerin isim ya da görüntülerine yer verme.\n");
        sb.append("Sadece ürün, mekân, soyut görsel ve animasyon kullan.\n");
        sb.append("Videonun içinde hiçbir şekilde yazı olmayacak.\n");

        return sb.toString();
    }

    /**
     * Caption + hashtag + CTA üretimi için OpenAI prompt'u.
     * JSON çıktı döner.
     *
     * @param brandDnaJson Brand DNA JSON (null olabilir)
     * @param reportSnippet rapor özeti
     * @param contentType içerik tipi
     */
    public static String forContentMetadata(String brandDnaJson, String reportSnippet, String contentType) {
        String dnaSection = (brandDnaJson != null && !brandDnaJson.isBlank())
                ? "Markanın Brand DNA'sı:\n" + brandDnaJson + "\n\n"
                : "";
        return """
                %sGelişim raporu özeti:
                %s

                Instagram %s içeriği için aşağıdaki alanları JSON formatında üret:
                - caption: Dikkat çekici, marka kimliğine uygun Instagram caption (max 2200 karakter)
                - hashtags: 15-20 ilgili hashtag (#ile birlikte, boşlukla ayrılmış)
                - cta: Etkili bir call-to-action metni
                - firstComment: İlk yorum önerisi (caption'dan farklı)
                - suggestedPostTime: Önerilen paylaşım günü ve saati (ör. "Salı 09:00-11:00")

                JSON dışında açıklama yazma.
                """.formatted(dnaSection, reportSnippet, contentType);
    }
}
