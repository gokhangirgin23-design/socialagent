package com.api.service;

import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.springframework.stereotype.Service;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;

import lombok.extern.slf4j.Slf4j;

/**
 * Markdown rapor içeriğini PDF byte dizisine dönüştürür (FAZ 8 — mail eki).
 * CommonMark ile Markdown→HTML, openhtmltopdf ile HTML→PDF.
 * Spectiqs logolu kapak başlığı eklenir. Service interface yok (CLAUDE.md Madde 1).
 */
@Slf4j
@Service
public class ReportPdfService {

    // Thread-safe: Parser ve HtmlRenderer immutable (builder ile oluşturulur)
    private static final Parser MD_PARSER = Parser.builder().build();
    private static final HtmlRenderer HTML_RENDERER = HtmlRenderer.builder().build();

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    /**
     * Markdown içeriğinden A4 PDF üretir.
     *
     * @param markdownContent rapor içeriği (null veya boş ise basit bir "içerik yok" sayfası)
     * @param reportTitle     rapor başlığı (PDF kapağında gösterilir)
     * @return PDF byte dizisi; üretim başarısız olursa null (log'a yazılır)
     */
    public byte[] generatePdf(String markdownContent, String reportTitle) {
        try {
            // Markdown → HTML
            String md = (markdownContent != null && !markdownContent.isBlank())
                    ? markdownContent
                    : "_Rapor içeriği henüz oluşturulmadı._";
            Node doc = MD_PARSER.parse(md);
            String bodyHtml = HTML_RENDERER.render(doc);

            // Tam HTML sayfası (logo başlık + içerik + alt bilgi)
            String fullHtml = buildHtml(bodyHtml, reportTitle != null ? reportTitle : "Spectiqs Raporu");

            // HTML → PDF
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            builder.withHtmlContent(fullHtml, null);
            builder.toStream(baos);
            builder.run();
            return baos.toByteArray();
        } catch (Exception ex) {
            log.warn("Rapor PDF üretimi başarısız (mail eki atlanacak): {}", ex.getMessage());
            return null;
        }
    }

    /**
     * Spectiqs markalı tam HTML dökümanı üretir.
     * CSS: inline (harici font yok — openhtmltopdf sistem fontlarını kullanır).
     */
    private String buildHtml(String bodyHtml, String reportTitle) {
        String dateStr = LocalDate.now().format(DATE_FMT);
        return """
                <!DOCTYPE html>
                <html>
                <head>
                <meta charset="UTF-8"/>
                <style>
                  body {
                    font-family: Helvetica, Arial, sans-serif;
                    font-size: 12px;
                    color: #0F1729;
                    line-height: 1.7;
                    margin: 40px;
                    background: #FFFFFF;
                  }
                  .header {
                    border-bottom: 2px solid #FFB224;
                    padding-bottom: 14px;
                    margin-bottom: 24px;
                    display: flex;
                    align-items: flex-start;
                  }
                  .logo-mark {
                    width: 38px;
                    height: 38px;
                    border-radius: 10px;
                    background-color: #FFB224;
                    display: inline-block;
                    vertical-align: middle;
                    margin-right: 12px;
                    text-align: center;
                    line-height: 38px;
                    font-weight: 900;
                    font-size: 20px;
                    color: #0F1729;
                  }
                  .brand-block { display: inline-block; vertical-align: middle; }
                  .brand-name {
                    font-size: 22px;
                    font-weight: 700;
                    color: #0F1729;
                    letter-spacing: -0.02em;
                  }
                  .brand-slogan {
                    font-size: 10px;
                    color: #8593AC;
                    margin-top: 2px;
                  }
                  .report-meta {
                    font-size: 10px;
                    color: #8593AC;
                    margin-bottom: 18px;
                    letter-spacing: 0.02em;
                  }
                  h1 {
                    font-size: 19px;
                    font-weight: 700;
                    color: #0F1729;
                    margin: 0 0 8px;
                    line-height: 1.3;
                  }
                  h2 {
                    font-size: 16px;
                    font-weight: 700;
                    color: #0F1729;
                    margin: 22px 0 8px;
                    border-bottom: 1px solid #E0E7F0;
                    padding-bottom: 4px;
                    line-height: 1.3;
                  }
                  h3 {
                    font-size: 14px;
                    font-weight: 700;
                    color: #0F1729;
                    margin: 16px 0 6px;
                  }
                  p { margin: 0 0 9px; }
                  strong { font-weight: 700; }
                  em { font-style: italic; }
                  ul, ol { padding-left: 20px; margin: 6px 0 10px; }
                  li { margin: 3px 0; }
                  table {
                    width: 100%%;
                    border-collapse: collapse;
                    font-size: 11px;
                    margin: 10px 0;
                  }
                  th {
                    background: #EEF2F9;
                    font-weight: 700;
                    padding: 6px 10px;
                    border: 1px solid #CED7E8;
                    text-align: left;
                  }
                  td { padding: 6px 10px; border: 1px solid #CED7E8; }
                  tr:nth-child(even) td { background: #F5F7FC; }
                  blockquote {
                    border-left: 3px solid #FFB224;
                    padding: 7px 12px;
                    margin: 9px 0;
                    background: #FFFBF0;
                    color: #51607A;
                  }
                  hr {
                    border: none;
                    border-top: 1px solid #E0E7F0;
                    margin: 16px 0;
                  }
                  code {
                    background: #F5F7FC;
                    padding: 1px 5px;
                    border-radius: 4px;
                    font-size: 11px;
                    font-family: monospace;
                  }
                  pre {
                    background: #F5F7FC;
                    border: 1px solid #D0D9E8;
                    border-radius: 6px;
                    padding: 9px 13px;
                    margin: 9px 0;
                    font-size: 11px;
                    white-space: pre-wrap;
                    word-break: break-word;
                  }
                  pre code { background: none; padding: 0; }
                  .footer {
                    border-top: 1px solid #E0E7F0;
                    margin-top: 28px;
                    padding-top: 10px;
                    font-size: 10px;
                    color: #8593AC;
                    text-align: center;
                  }
                </style>
                </head>
                <body>
                  <div class="header">
                    <div class="logo-mark">S</div>
                    <div class="brand-block">
                      <div class="brand-name">Spectiqs</div>
                      <div class="brand-slogan">See What Others Miss. — Başkalarının göremediğini gör.</div>
                    </div>
                  </div>
                  <div class="report-meta">%s &nbsp;·&nbsp; %s</div>
                  %s
                  <div class="footer">Spectiqs Analytics &nbsp;·&nbsp; Bu rapor otomatik olarak oluşturulmuştur. &nbsp;·&nbsp; spectiqs.com</div>
                </body>
                </html>
                """.formatted(reportTitle, dateStr, bodyHtml);
    }
}
