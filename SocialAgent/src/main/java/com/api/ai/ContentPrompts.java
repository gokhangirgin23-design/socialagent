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
     * Son N posttaki metrikleri + rapor içeriğini alır; markanın kişiliğini JSON olarak çıkarır.
     *
     * @param postsContext son 10 postun caption + metrik özeti
     * @param reportContent rapor Markdown içeriği
     */
    public static String forBrandDna(String postsContext, String reportContent) {
        String posts = (postsContext != null && !postsContext.isBlank())
                ? "Kullanıcının son paylaşımları:\n" + postsContext + "\n\n"
                : "";
        return """
                %sBu analiz raporunu incele:

                %s

                Bu markanın Brand DNA'sını çıkar ve JSON formatında üret.

                Aşağıdaki alanları oluştur:
                - brandPersonality: Markanın kişilik özellikleri
                - toneOfVoice: Ses tonu ve iletişim stili
                - visualStyle: Görsel kimlik özellikleri
                - designStyle: Tasarım yaklaşımı
                - colorPalette: Ana renkler
                - compositionRules: Kompozisyon kuralları
                - designRules: Tasarım kuralları
                - preferredContentTypes: Tercih edilen içerik türleri
                - strengths: Güçlü yönler
                - weaknesses: Zayıf yönler
                - avoid: Kaçınılması gerekenler
                - improvementGoals: Gelişim hedefleri

                Eksik bilgi varsa analiz raporundan çıkarım yap.
                JSON dışında açıklama yazma.
                """.formatted(posts, reportContent);
    }

    /**
     * Görsel üretimi için Gemini prompt'u.
     *
     * @param brandDnaJson   Brand DNA JSON (null ise markalamadan bağımsız üretilir)
     * @param reportContent  rapor içeriği (gelişim önerileri alınır)
     * @param contentType    POST|STORY|CAROUSEL|REEL|ALL
     * @param slideIndex     carousel için slayt numarası (0 = tek görsel)
     * @param slideRole      carousel için slayt rolü (HOOK|CONTENT|CTA)
     * @param includeText    görselde metin olsun mu
     */
    public static String forVisual(String brandDnaJson, String reportContent,
                                   String contentType, int slideIndex, String slideRole,
                                   boolean includeText) {
        StringBuilder sb = new StringBuilder();

        if (brandDnaJson != null && !brandDnaJson.isBlank()) {
            sb.append("Bu markanın Brand DNA'sına sadık kal:\n").append(brandDnaJson).append("\n\n");
        }

        sb.append("Gelişim raporundaki önerileri uygula. Rapor özeti:\n");
        // Rapor uzunsa sadece ilk 1500 karakteri al
        String reportSnippet = reportContent != null && reportContent.length() > 1500
                ? reportContent.substring(0, 1500) + "..."
                : reportContent;
        sb.append(reportSnippet).append("\n\n");

        // Format bazlı talimat
        String formatLabel = switch (contentType.toUpperCase()) {
            case "STORY" -> "Instagram Story (9:16 dikey format)";
            case "CAROUSEL" -> "Instagram Carousel slaydı (" + slideRole + " — slayt " + (slideIndex + 1) + ")";
            case "REEL" -> "Instagram Reel kapak görseli (9:16 dikey format — portrait)";
            default -> "Instagram Post (kare veya 4:5 format)";
        };
        sb.append("Instagram için premium bir ").append(formatLabel).append(" oluştur.\n\n");
        sb.append("Modern ve estetik görünüm oluştur.\n");
        sb.append("Ürünü değiştirme.\n");
        if (!includeText) {
            sb.append("Görselde metin, yazı veya slogan OLMAYACAK.\n");
        }
        sb.append("Görsellerde rapordaki ifadelerin (reels, video, paylaşım takvimi, story, spectiqs gibi) hiçbiri olmayacak.\n");

        return sb.toString();
    }

    /**
     * Sora ile Instagram Reel video üretimi için prompt.
     * Statik görsel değil, video sahnesini tanımlar.
     *
     * @param brandDnaJson Brand DNA JSON (null olabilir)
     * @param reportContent rapor içeriği (gelişim önerileri alınır)
     */
    public static String forVideo(String brandDnaJson, String reportContent) {
        StringBuilder sb = new StringBuilder();

        if (brandDnaJson != null && !brandDnaJson.isBlank()) {
            sb.append("Bu markanın Brand DNA'sına sadık kal:\n").append(brandDnaJson).append("\n\n");
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
