package com.api.service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import org.commonmark.node.BlockQuote;
import org.commonmark.node.BulletList;
import org.commonmark.node.Code;
import org.commonmark.node.Emphasis;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Heading;
import org.commonmark.node.Image;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Link;
import org.commonmark.node.ListItem;
import org.commonmark.node.Node;
import org.commonmark.node.OrderedList;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.StrongEmphasis;
import org.commonmark.node.Text;
import org.commonmark.node.ThematicBreak;
import org.commonmark.parser.Parser;
import org.springframework.stereotype.Service;

import com.lowagie.text.Chunk;
import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;

import lombok.extern.slf4j.Slf4j;

/**
 * Markdown rapor içeriğini PDF byte dizisine dönüştürür (FAZ 8 — mail eki).
 * CommonMark ile Markdown AST ayrıştırılır; OpenPDF (librepdf) ile PDF oluşturulur.
 * Spectiqs logolu başlık ve altbilgi dahildir. Service interface yok (CLAUDE.md Madde 1).
 */
@Slf4j
@Service
public class ReportPdfService {

    // Thread-safe: Parser immutable
    private static final Parser MD_PARSER = Parser.builder().build();
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    // Renk sabitleri
    private static final Color C_ACCENT  = new Color(255, 178, 36);
    private static final Color C_TEXT    = new Color(15,  23,  41);
    private static final Color C_MUTED   = new Color(81,  96,  122);
    private static final Color C_DIM     = new Color(133, 147, 172);
    private static final Color C_BORDER  = new Color(224, 231, 240);
    private static final Color C_SURFACE = new Color(238, 242, 249);

    /**
     * Markdown içeriğinden Spectiqs markalı A4 PDF üretir.
     *
     * @param markdownContent rapor Markdown metni
     * @param reportTitle     başlık (kapak satırında gösterilir)
     * @return PDF byte dizisi; üretim başarısızsa null (log'a yazılır)
     */
    public byte[] generatePdf(String markdownContent, String reportTitle) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            // A4; sol/sağ/üst/alt kenar boşlukları (puan)
            Document doc = new Document(PageSize.A4, 50, 50, 60, 55);
            PdfWriter.getInstance(doc, baos);
            doc.open();

            addBrandHeader(doc, reportTitle != null ? reportTitle : "Spectiqs Raporu");

            String md = (markdownContent != null && !markdownContent.isBlank())
                    ? markdownContent
                    : "_Rapor içeriği henüz oluşturulmadı._";
            Node root = MD_PARSER.parse(md);
            renderChildren(doc, root);

            addFooter(doc);
            doc.close();
            return baos.toByteArray();
        } catch (Exception ex) {
            log.warn("Rapor PDF üretimi başarısız (mail eki atlanacak): {}", ex.getMessage());
            return null;
        }
    }

    // ============================================================
    // Başlık + altbilgi
    // ============================================================

    private void addBrandHeader(Document doc, String reportTitle) throws DocumentException {
        // Logo ("S" harfi) + marka adı yanyana — 2 kolonlu tablo
        PdfPTable headerTbl = new PdfPTable(new float[]{42f, 450f});
        headerTbl.setWidthPercentage(100);

        // Logo kare (altın sarısı arka plan)
        Font logoFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 22, Color.BLACK);
        PdfPCell logoCell = new PdfPCell(new Phrase("S", logoFont));
        logoCell.setBackgroundColor(C_ACCENT);
        logoCell.setBorder(Rectangle.NO_BORDER);
        logoCell.setPadding(8);
        logoCell.setHorizontalAlignment(Element.ALIGN_CENTER);
        logoCell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        headerTbl.addCell(logoCell);

        // Marka adı + slogan
        Paragraph brandPara = new Paragraph();
        brandPara.add(new Chunk("Spectiqs",
                FontFactory.getFont(FontFactory.HELVETICA_BOLD, 19, C_TEXT)));
        brandPara.add(Chunk.NEWLINE);
        brandPara.add(new Chunk("See What Others Miss. — Başkalarının göremediğini gör.",
                FontFactory.getFont(FontFactory.HELVETICA, 9, C_DIM)));
        PdfPCell brandCell = new PdfPCell(brandPara);
        brandCell.setBorder(Rectangle.NO_BORDER);
        brandCell.setPaddingLeft(12);
        brandCell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        headerTbl.addCell(brandCell);

        doc.add(headerTbl);

        // Altın çizgi ayırıcı
        doc.add(hLine(C_ACCENT, 2f, 8, 6));

        // Rapor başlığı + tarih
        String dateStr = LocalDate.now().format(DATE_FMT);
        Paragraph meta = new Paragraph(reportTitle + "  ·  " + dateStr,
                FontFactory.getFont(FontFactory.HELVETICA, 10, C_MUTED));
        meta.setSpacingAfter(18);
        doc.add(meta);
    }

    private void addFooter(Document doc) throws DocumentException {
        doc.add(hLine(C_BORDER, 0.5f, 20, 6));
        Paragraph footer = new Paragraph(
                "Spectiqs Analytics  ·  spectiqs.com  ·  Bu rapor otomatik olarak oluşturulmuştur.",
                FontFactory.getFont(FontFactory.HELVETICA, 9, C_DIM));
        footer.setAlignment(Element.ALIGN_CENTER);
        doc.add(footer);
    }

    // ============================================================
    // AST yürüyüşü
    // ============================================================

    private void renderChildren(Document doc, Node parent) throws DocumentException {
        Node child = parent.getFirstChild();
        while (child != null) {
            renderBlock(doc, child);
            child = child.getNext();
        }
    }

    private void renderBlock(Document doc, Node node) throws DocumentException {
        if (node instanceof Heading h) {
            Font f = h.getLevel() == 1 ? fH1() : h.getLevel() == 2 ? fH2() : fH3();
            Paragraph p = new Paragraph(extractText(h), f);
            p.setSpacingBefore(h.getLevel() == 1 ? 0 : 14);
            p.setSpacingAfter(5);
            doc.add(p);
            if (h.getLevel() <= 2) {
                doc.add(hLine(C_BORDER, 0.5f, 2, 8));
            }

        } else if (node instanceof org.commonmark.node.Paragraph) {
            Paragraph p = buildInline(node, fBody());
            p.setSpacingAfter(9);
            p.setLeading(18);
            doc.add(p);

        } else if (node instanceof BulletList) {
            renderList(doc, node, false, 0);

        } else if (node instanceof OrderedList) {
            renderList(doc, node, true, 0);

        } else if (node instanceof BlockQuote bq) {
            // Soldan çizgili blok alıntı
            PdfPTable qt = new PdfPTable(new float[]{4f, 450f});
            qt.setWidthPercentage(100);
            qt.setSpacingBefore(6);
            qt.setSpacingAfter(6);
            PdfPCell bar = new PdfPCell();
            bar.setBackgroundColor(C_ACCENT);
            bar.setBorder(Rectangle.NO_BORDER);
            bar.setPadding(0);
            qt.addCell(bar);
            Paragraph qp = buildInline(bq.getFirstChild(), fMuted());
            qp.setLeading(16);
            PdfPCell qc = new PdfPCell(qp);
            qc.setBackgroundColor(C_SURFACE);
            qc.setBorder(Rectangle.NO_BORDER);
            qc.setPadding(8);
            qt.addCell(qc);
            doc.add(qt);

        } else if (node instanceof ThematicBreak) {
            doc.add(hLine(C_BORDER, 0.5f, 10, 10));

        } else if (node instanceof FencedCodeBlock fc) {
            Paragraph p = new Paragraph(fc.getLiteral().stripTrailing(),
                    FontFactory.getFont(FontFactory.COURIER, 10, C_TEXT));
            p.setIndentationLeft(12);
            p.setSpacingBefore(6);
            p.setSpacingAfter(6);
            doc.add(p);

        } else if (node instanceof IndentedCodeBlock ic) {
            Paragraph p = new Paragraph(ic.getLiteral().stripTrailing(),
                    FontFactory.getFont(FontFactory.COURIER, 10, C_TEXT));
            p.setIndentationLeft(12);
            p.setSpacingBefore(4);
            p.setSpacingAfter(4);
            doc.add(p);
        }
        // HtmlBlock, Image vb. — sessizce atlanır
    }

    private void renderList(Document doc, Node listNode, boolean ordered, int depth)
            throws DocumentException {
        int counter = 1;
        Node item = listNode.getFirstChild();
        while (item != null) {
            if (item instanceof ListItem li) {
                String bullet = ordered ? (counter++ + ". ") : "• ";
                Paragraph p = new Paragraph();
                p.add(new Chunk(bullet, fBold()));
                Node firstChild = li.getFirstChild();
                if (firstChild instanceof org.commonmark.node.Paragraph) {
                    appendInline(p, firstChild, fBody());
                } else if (firstChild != null) {
                    p.add(new Chunk(extractText(firstChild), fBody()));
                }
                p.setIndentationLeft(14 + depth * 12f);
                p.setSpacingAfter(3);
                p.setLeading(16);
                doc.add(p);

                // Alt listeler
                Node sub = firstChild != null ? firstChild.getNext() : null;
                while (sub != null) {
                    if (sub instanceof BulletList)  renderList(doc, sub, false, depth + 1);
                    if (sub instanceof OrderedList) renderList(doc, sub, true,  depth + 1);
                    sub = sub.getNext();
                }
            }
            item = item.getNext();
        }
    }

    // ============================================================
    // Inline içerik oluşturucular
    // ============================================================

    private Paragraph buildInline(Node parent, Font defaultFont) {
        Paragraph p = new Paragraph();
        p.setFont(defaultFont);
        appendInline(p, parent, defaultFont);
        return p;
    }

    private void appendInline(Paragraph p, Node parent, Font defaultFont) {
        if (parent == null) return;
        Node child = parent.getFirstChild();
        while (child != null) {
            if (child instanceof Text t) {
                p.add(new Chunk(t.getLiteral(), defaultFont));
            } else if (child instanceof StrongEmphasis) {
                appendInline(p, child, fBold());
            } else if (child instanceof Emphasis) {
                appendInline(p, child, fItalic());
            } else if (child instanceof Code c) {
                p.add(new Chunk(c.getLiteral(), FontFactory.getFont(FontFactory.COURIER, 10, C_TEXT)));
            } else if (child instanceof SoftLineBreak || child instanceof HardLineBreak) {
                p.add(Chunk.NEWLINE);
            } else if (child instanceof Link l) {
                // Link metnini göster (URL atlanır)
                appendInline(p, l, defaultFont);
            } else if (child instanceof Image) {
                // Görseller PDF'te desteklenmiyor — atlan
            } else {
                // Diğer inline node'lar: özyinelemeli olarak işle
                appendInline(p, child, defaultFont);
            }
            child = child.getNext();
        }
    }

    private String extractText(Node node) {
        StringBuilder sb = new StringBuilder();
        extractTextInto(node, sb);
        return sb.toString();
    }

    private void extractTextInto(Node node, StringBuilder sb) {
        if (node instanceof Text t) { sb.append(t.getLiteral()); return; }
        Node child = node.getFirstChild();
        while (child != null) { extractTextInto(child, sb); child = child.getNext(); }
    }

    // ============================================================
    // Yardımcılar
    // ============================================================

    /** Yatay çizgi ayırıcı (PdfPTable ile — LineSeparator bağımlılığı yok). */
    private PdfPTable hLine(Color color, float width, float spaceBefore, float spaceAfter) {
        PdfPTable t = new PdfPTable(1);
        t.setWidthPercentage(100);
        t.setSpacingBefore(spaceBefore);
        t.setSpacingAfter(spaceAfter);
        PdfPCell c = new PdfPCell();
        c.setBorder(Rectangle.BOTTOM);
        c.setBorderColor(color);
        c.setBorderWidthBottom(width);
        c.setFixedHeight(width);
        c.setPadding(0);
        t.addCell(c);
        return t;
    }

    // Font yardımcıları (her çağrıda yeni instance — OpenPDF thread-safety için)
    private Font fH1()    { return FontFactory.getFont(FontFactory.HELVETICA_BOLD,    20, C_TEXT); }
    private Font fH2()    { return FontFactory.getFont(FontFactory.HELVETICA_BOLD,    16, C_TEXT); }
    private Font fH3()    { return FontFactory.getFont(FontFactory.HELVETICA_BOLD,    14, C_TEXT); }
    private Font fBody()  { return FontFactory.getFont(FontFactory.HELVETICA,         12, C_TEXT); }
    private Font fBold()  { return FontFactory.getFont(FontFactory.HELVETICA_BOLD,    12, C_TEXT); }
    private Font fItalic(){ return FontFactory.getFont(FontFactory.HELVETICA_OBLIQUE, 12, C_TEXT); }
    private Font fMuted() { return FontFactory.getFont(FontFactory.HELVETICA,         11, C_MUTED); }
}
