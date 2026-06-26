package com.api.ai.prompt;

import java.util.List;

import com.api.dto.AccountReportRow;

/**
 * Rapor üretim prompt template'i (FAZ 7 — WorkerPrompt revizyonu, CLAUDE.md Bölüm 11).
 *
 * OpenAI'ya ham post satırları yerine hesap bazlı özetler gönderilir:
 *   → Token verimliliği (150 post satırı yerine 5-10 hesap özeti)
 *   → Daha yapısal ve karşılaştırılabilir veri
 *
 * analysisMode'a göre iki rapor tipi:
 *   - OWN_ONLY / BOTH → Karşılaştırmalı: kendi hesabı vs. rakip/sektör
 *   - NONE / COMPETITOR_ONLY → Başarı faktörü: nerede ve neden başarılı
 *
 * Başlıklar: Özet | İçerik Önerileri | Hashtag Önerileri | Paylaşım Takvimi |
 *            Rakiplerden Öğren | Aksiyon Planı
 */
public final class ReportPrompts {

	private ReportPrompts() {
	}

	private static final String COMPARISON_RULE = """
			Sen kıdemli bir sosyal medya strateji danışmanısın. Sana bir kullanıcının Instagram analiz
			işine ait hesap bazlı istatistikler veriliyor (her hesap için: ortalama beğeni, içerik tipi
			dağılımı, reel oranı, insan/model kullanımı, ürün odaklılık, açıklama tonu, görsel temalar vb.).

			Veriler; kullanıcının KENDİ hesabı ile rakip/sektör hesaplarını içermektedir.
			Karşılaştırmalı bir Türkçe rapor üret.

			Çıktı kuralları:
			- SADECE Markdown döndür. Kod bloğu (```), JSON veya ön/son açıklama EKLEME.
			- Profesyonel, okunaklı ve uygulanabilir bir rapor yaz.
			- Her ana başlık önüne uygun bir emoji ikon koy (📊 📝 💡 📅 🎯 🚀 🔥 ✨ vb.).
			- Etkileşim karşılaştırma verilerini mutlaka bir Markdown tablosu olarak göster.
			- İçerik önerilerinde her format (Reel, Fotoğraf, Story) için somut görsel/sahne/yazı fikirleri ver:
			  * Hangi tür ortam/sahne çekimi (doğa, ofis, ürün close-up, lifestyle vb.)
			  * Görselin üzerindeki yazı/mesaj önerileri (viral yazı formatları, CTA'lar)
			  * Müzik/ses tonu önerileri (enerjik, duygusal, eğlenceli vb.) varsa
			- Paylaşım açıklaması (caption) yazım önerileri: ton, CTA, anahtar cümleler.
			- Aşağıdaki başlık iskeletini kullan:

			# 📱 Sosyal Medya Analiz Raporu
			## 📊 Özet
			## 📈 Etkileşim Karşılaştırması
			(tablo: Hesap | Ort. Beğeni | Ort. Yorum | Ort. Görüntülenme | Reel Oranı | İçerik Tipi)
			## 💡 İçerik Önerileri
			### 🎬 Reel / Video
			### 📷 Fotoğraf
			### 📖 Story
			## ✍️ Açıklama (Caption) Stratejisi
			## #️⃣ Hashtag Önerileri
			## 📅 Paylaşım Takvimi
			## 🔍 Rakiplerden Öğren
			## 🚀 Aksiyon Planı

			Notlar:
			- Takipçi sayısı yoksa ortalama beğeni + yorum sayısı etkileşim karşılaştırma metriği olarak kullan.
			- Veri azsa bunu dürüstçe belirt; uydurma sayı/iddia ekleme.
			- İçerik fikirleri gerçekçi ve sektöre özgü olsun (genel klişelerden kaçın).
			""";

	private static final String SUCCESS_FACTOR_RULE = """
			Sen kıdemli bir sosyal medya strateji danışmanısın. Sana bir Instagram analiz işine ait
			hesap bazlı istatistikler veriliyor (sektördeki başarılı hesaplar veya rakipler).

			Bu hesapların nerelerde ve neden başarılı olduğunu analiz eden bir Türkçe rapor üret.

			Çıktı kuralları:
			- SADECE Markdown döndür. Kod bloğu (```), JSON veya ön/son açıklama EKLEME.
			- Profesyonel, okunaklı ve uygulanabilir bir rapor yaz.
			- Her ana başlık önüne uygun bir emoji ikon koy (📊 📝 💡 📅 🎯 🚀 🔥 ✨ vb.).
			- Başarılı hesapların metriklerini mutlaka bir Markdown tablosu olarak göster.
			- İçerik önerilerinde her format (Reel, Fotoğraf, Story) için somut görsel/sahne/yazı fikirleri ver:
			  * Hangi tür ortam/sahne çekimi (doğa, ofis, ürün close-up, lifestyle vb.)
			  * Görselin üzerindeki yazı/mesaj önerileri (viral yazı formatları, CTA'lar)
			  * Müzik/ses tonu önerileri varsa
			- Paylaşım açıklaması (caption) yazım önerileri: ton, CTA, anahtar cümleler.
			- Aşağıdaki başlık iskeletini kullan:

			# 📱 Sosyal Medya Sektör Analiz Raporu
			## 📊 Özet
			## 🏆 Başarılı Hesapların Karşılaştırması
			(tablo: Hesap | Ort. Beğeni | Ort. Yorum | Ort. Görüntülenme | Reel Oranı | İçerik Tipi)
			## 🔑 Başarı Faktörleri
			## 💡 İçerik Önerileri
			### 🎬 Reel / Video
			### 📷 Fotoğraf
			### 📖 Story
			## ✍️ Açıklama (Caption) Stratejisi
			## #️⃣ Hashtag Önerileri
			## 📅 Paylaşım Takvimi
			## 🔍 Rakiplerden Öğren
			## 🚀 Aksiyon Planı

			Notlar:
			- Takipçi sayısı yoksa ortalama beğeni + yorum sayısı etkileşim metriği olarak kullan.
			- Veri azsa bunu dürüstçe belirt; uydurma sayı/iddia ekleme.
			- İçerik fikirleri gerçekçi ve sektöre özgü olsun (genel klişelerden kaçın).
			""";

	/**
	 * Hesap bazlı özetlerden Markdown rapor prompt'u üretir.
	 *
	 * @param summaries hesap başına aggregate istatistikler (AccountReportRow)
	 * @param analysisMode job'ın analiz modu
	 * @return OpenAI'a verilecek prompt
	 */
	public static String forJob(List<AccountReportRow> summaries, String analysisMode) {
		boolean isComparison = "OWN_ONLY".equals(analysisMode) || "BOTH".equals(analysisMode);
		String systemRule = isComparison ? COMPARISON_RULE : SUCCESS_FACTOR_RULE;

		StringBuilder sb = new StringBuilder();
		for (AccountReportRow r : summaries) {
			sb.append("### ").append(r.source()).append(" — @").append(r.accountName()).append('\n');
			sb.append("- Analiz edilen gönderi sayısı: ").append(r.postCount()).append('\n');
			sb.append("- Ortalama beğeni: ").append(r.avgLikes()).append('\n');
			sb.append("- Ortalama yorum: ").append(r.avgComments()).append('\n');
			sb.append("- Ortalama görüntülenme: ").append(r.avgViews()).append('\n');

			// İçerik tipi dağılımı
			sb.append("- İçerik tipi dağılımı: ")
					.append("Fotoğraf=").append(r.imageCount())
					.append(", Video=").append(r.videoCount())
					.append(", Carousel=").append(r.carouselCount())
					.append(", Reel=").append(r.reelCount()).append('\n');

			// Görsel analiz metrikleri (Gemini Vision'dan)
			long total = r.postCount() > 0 ? r.postCount() : 1;
			sb.append("- İnsan/model içeren gönderi: ")
					.append(r.humanCount()).append(" / ").append(total)
					.append(" (Model: ").append(r.modelCount()).append(')').append('\n');
			sb.append("- Ürün odaklı gönderi: ")
					.append(r.productFocusedCount()).append(" / ").append(total).append('\n');
			sb.append('\n');
		}

		return """
				%s

				Analiz modu: %s
				Analiz edilen hesap sayısı: %d

				Aşağıda hesap bazlı istatistikler verilmiştir:

				%s
				Şimdi yukarıdaki verileri sentezleyip Markdown raporu üret.
				""".formatted(systemRule, safe(analysisMode), summaries.size(), sb.toString());
	}

	/**
	 * Hesap bazlı özetlerden dashboard structured insight JSON prompt'u üretir.
	 * Çıktı: {"topInsight":"...","competitorFinding":"...","recommendation":"...","actionPlan":["..."]}
	 */
	public static String forInsight(List<AccountReportRow> summaries, String analysisMode) {
		StringBuilder sb = new StringBuilder();
		for (AccountReportRow r : summaries) {
			sb.append(r.source()).append(" @").append(r.accountName())
					.append(": ort.beğeni=").append(r.avgLikes())
					.append(" ort.yorum=").append(r.avgComments())
					.append(" gönderi=").append(r.postCount())
					.append(" reel=").append(r.reelCount())
					.append('\n');
		}
		return """
				Sen kıdemli bir sosyal medya strateji danışmanısın.
				Aşağıdaki Instagram analiz istatistiklerine dayanarak kullanıcıya kısa ve yapısal bir içgörü özeti üret.

				SADECE aşağıdaki JSON formatında yanıt ver (Markdown, açıklama veya kod bloğu EKLEME):
				{"topInsight":"En önemli tek bulgu (1-2 cümle)","competitorFinding":"Rakip/sektör karşılaştırmasından kritik bulgu (1-2 cümle)","recommendation":"En öncelikli uygulama önerisi (1-2 cümle)","actionPlan":["Aksiyon 1","Aksiyon 2","Aksiyon 3"]}

				Analiz modu: %s
				Veriler:
				%s
				""".formatted(safe(analysisMode), sb.toString());
	}

	private static String safe(String v) {
		return (v == null || v.isBlank()) ? "-" : v;
	}
}
