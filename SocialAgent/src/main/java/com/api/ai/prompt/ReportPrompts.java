package com.api.ai.prompt;

import java.util.List;

import com.api.dto.ReportPostRow;

/**
 * Rapor üretim prompt template'i (FAZ 7 — CLAUDE.md Bölüm 11).
 * FAZ 6'da post başına üretilen analiz JSON'ları toplanır ve OpenAI'a verilir; model
 * tek bir TÜRKÇE Markdown rapor üretir (analiz JSON'ı değil — rapor metni).
 *
 * Çıktı sözleşmesi: salt Markdown (kod bloğu sarmalaması olmadan). Rapor; kendi/rakip/sektör
 * hesaplarını karşılaştırır, etkileşim/tema/ton/hashtag stratejisini özetler ve uygulanabilir
 * öneriler verir. Analiz JSON'ları toleranslı okunur (FAZ 6 katı şema doğrulaması yapmıyordu).
 */
public final class ReportPrompts {

	// Yardımcı sınıf; örneklenmez
	private ReportPrompts() {
	}

	// Caption gibi uzun alanları prompt'ta sınırlamak için üst sınır
	private static final int CAPTION_MAX = 280;

	// Modelin rolünü ve Markdown çıktı kuralını veren ortak yönerge
	private static final String SYSTEM_RULE = """
			Sen kıdemli bir sosyal medya strateji danışmanısın. Sana bir kullanıcının analiz işine
			ait gönderi analizleri (JSON) veriliyor. Bunları sentezleyerek TEK bir TÜRKÇE rapor üret.

			Çıktı kuralları:
			- SADECE Markdown döndür. Kod bloğu (```), JSON veya ön/son açıklama EKLEME.
			- Profesyonel, okunaklı ve uygulanabilir bir rapor yaz.
			- Aşağıdaki başlık iskeletini kullan (gereksiz başlıkları atlama):

			# Sosyal Medya Analiz Raporu
			## Genel Özet
			## Etkileşim Değerlendirmesi
			## Öne Çıkan Temalar ve Ton
			## Hashtag Stratejisi
			## Kendi Hesap vs. Rakip/Sektör Karşılaştırması
			## Uygulanabilir Öneriler

			Notlar:
			- Veri azsa bunu dürüstçe belirt; uydurma sayı/iddia ekleme.
			- "Kendi Hesap" yoksa karşılaştırma bölümünü sektör/rakip ekseninde yaz.
			""";

	/**
	 * Bir job'ın tüm analizlerinden Markdown rapor prompt'u üretir.
	 *
	 * @param rows post_analysis ⋈ social_post satırları (kaynak etiketi + analiz JSON'ı)
	 * @return OpenAI'a verilecek tek parça prompt metni
	 */
	public static String forJob(List<ReportPostRow> rows) {
		// Her analiz satırını numaralı, okunaklı bir blok olarak prompt'a yaz
		StringBuilder sb = new StringBuilder();
		int i = 1;
		for (ReportPostRow r : rows) {
			sb.append("### Gönderi ").append(i++).append('\n');
			sb.append("- Kaynak: ").append(safe(r.source())).append('\n');
			sb.append("- Medya türü: ").append(safe(r.mediaType())).append('\n');
			sb.append("- Caption: ").append(clip(r.caption())).append('\n');
			sb.append("- Hashtag'ler: ").append(safe(r.hashtags())).append('\n');
			sb.append("- Beğeni: ").append(nz(r.likesCount()))
					.append(", Yorum: ").append(nz(r.commentsCount()))
					.append(", Görüntülenme: ").append(nz(r.viewsCount())).append('\n');
			// FAZ 6 analiz JSON'ı (ham; model toleranslı okur)
			sb.append("- Analiz JSON: ").append(safe(r.analysisJson())).append("\n\n");
		}

		// Yönerge + toplam gönderi sayısı + analiz blokları
		return """
				%s

				Analiz edilen toplam gönderi sayısı: %d
				Aşağıda gönderi bazlı analizler verilmiştir:

				%s
				Şimdi yukarıdaki tüm analizleri sentezleyip Markdown raporu üret.
				""".formatted(SYSTEM_RULE, rows.size(), sb.toString());
	}

	// null Long -> 0 (prompt'ta güvenli sayı)
	private static long nz(Long v) {
		return v != null ? v : 0L;
	}

	// null/blank metin -> "-" (prompt okunaklı kalsın)
	private static String safe(String v) {
		return (v == null || v.isBlank()) ? "-" : v;
	}

	// Uzun caption'ı prompt boyutunu şişirmemek için kısalt
	private static String clip(String v) {
		if (v == null || v.isBlank()) {
			return "-";
		}
		String t = v.strip();
		return t.length() <= CAPTION_MAX ? t : t.substring(0, CAPTION_MAX) + "…";
	}
}
