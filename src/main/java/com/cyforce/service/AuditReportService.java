package com.cyforce.service;

import com.cyforce.model.AuditLog;
import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
public class AuditReportService {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    private static final int PDF_CELL_LIMIT = 180;

    public byte[] toCsv(List<AuditLog> logs, String title) {
        StringBuilder csv = new StringBuilder();
        csv.append(title).append('\n');
        csv.append("Generated,").append(LocalDateTime.now().format(ISO)).append('\n');
        csv.append("Total Events,").append(logs.size()).append('\n');
        csv.append('\n');
        csv.append("Timestamp,Action,Module,User Email,IP Address,Details\n");
        for (AuditLog log : logs) {
            csv.append(csvCell(formatTimestamp(log.getCreatedAt()))).append(',');
            csv.append(csvCell(log.getAction())).append(',');
            csv.append(csvCell(log.getModule())).append(',');
            csv.append(csvCell(log.getUserEmail())).append(',');
            csv.append(csvCell(log.getClientIp())).append(',');
            csv.append(csvCell(log.getDetails())).append('\n');
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    public byte[] toPdf(List<AuditLog> logs, String title) {
        Font titleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 16);
        Font metaFont = FontFactory.getFont(FontFactory.HELVETICA, 10);
        Font headerFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8, Font.NORMAL, Color.WHITE);
        Font cellFont = FontFactory.getFont(FontFactory.HELVETICA, 7);

        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Document document = new Document(PageSize.A4.rotate(), 36, 36, 36, 36);
            PdfWriter.getInstance(document, output);
            document.open();

            document.add(new Paragraph(title, titleFont));
            document.add(new Paragraph("Generated: " + LocalDateTime.now().format(ISO), metaFont));
            document.add(new Paragraph("Total events: " + logs.size(), metaFont));
            document.add(new Paragraph(" "));

            PdfPTable table = new PdfPTable(6);
            table.setWidthPercentage(100);
            table.setWidths(new float[]{2.4f, 1.5f, 1.5f, 2.2f, 1.3f, 3.1f});
            table.setHeaderRows(1);

            addHeaderCell(table, "Timestamp", headerFont);
            addHeaderCell(table, "Action", headerFont);
            addHeaderCell(table, "Module", headerFont);
            addHeaderCell(table, "User Email", headerFont);
            addHeaderCell(table, "IP Address", headerFont);
            addHeaderCell(table, "Details", headerFont);

            if (logs.isEmpty()) {
                PdfPCell empty = new PdfPCell(new Phrase("No audit events recorded.", cellFont));
                empty.setColspan(6);
                empty.setPadding(8);
                table.addCell(empty);
            } else {
                for (AuditLog log : logs) {
                    addBodyCell(table, formatTimestamp(log.getCreatedAt()), cellFont);
                    addBodyCell(table, log.getAction(), cellFont);
                    addBodyCell(table, log.getModule(), cellFont);
                    addBodyCell(table, log.getUserEmail(), cellFont);
                    addBodyCell(table, log.getClientIp(), cellFont);
                    addBodyCell(table, log.getDetails(), cellFont);
                }
            }

            document.add(table);
            document.close();
            return output.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate PDF report: " + e.getMessage(), e);
        }
    }

    private void addHeaderCell(PdfPTable table, String text, Font font) {
        PdfPCell cell = new PdfPCell(new Phrase(text, font));
        cell.setBackgroundColor(new Color(43, 92, 230));
        cell.setPadding(5);
        cell.setHorizontalAlignment(Element.ALIGN_LEFT);
        table.addCell(cell);
    }

    private void addBodyCell(PdfPTable table, String text, Font font) {
        PdfPCell cell = new PdfPCell(new Phrase(truncate(text), font));
        cell.setPadding(4);
        cell.setVerticalAlignment(Element.ALIGN_TOP);
        table.addCell(cell);
    }

    private String formatTimestamp(LocalDateTime value) {
        return value == null ? "" : value.format(ISO);
    }

    private String truncate(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.length() <= PDF_CELL_LIMIT) {
            return trimmed;
        }
        return trimmed.substring(0, PDF_CELL_LIMIT - 3) + "...";
    }

    private String csvCell(String value) {
        if (value == null) {
            return "";
        }
        String escaped = value.replace("\"", "\"\"");
        if (escaped.contains(",") || escaped.contains("\"") || escaped.contains("\n") || escaped.contains("\r")) {
            return "\"" + escaped + "\"";
        }
        return escaped;
    }

    public byte[] toTableCsv(String title, String[] headers, List<String[]> rows) {
        StringBuilder csv = new StringBuilder();
        csv.append(title).append('\n');
        csv.append("Generated,").append(LocalDateTime.now().format(ISO)).append('\n');
        csv.append("Total Records,").append(rows.size()).append('\n');
        csv.append('\n');
        csv.append(String.join(",", headers)).append('\n');
        for (String[] row : rows) {
            for (int i = 0; i < headers.length; i++) {
                if (i > 0) {
                    csv.append(',');
                }
                csv.append(csvCell(i < row.length ? row[i] : ""));
            }
            csv.append('\n');
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    public byte[] toTablePdf(String title, String[] headers, List<String[]> rows) {
        Font titleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 16);
        Font metaFont = FontFactory.getFont(FontFactory.HELVETICA, 10);
        Font headerFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8, Font.NORMAL, Color.WHITE);
        Font cellFont = FontFactory.getFont(FontFactory.HELVETICA, 8);

        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Document document = new Document(PageSize.A4.rotate(), 36, 36, 36, 36);
            PdfWriter.getInstance(document, output);
            document.open();

            document.add(new Paragraph(title, titleFont));
            document.add(new Paragraph("Generated: " + LocalDateTime.now().format(ISO), metaFont));
            document.add(new Paragraph("Total records: " + rows.size(), metaFont));
            document.add(new Paragraph(" "));

            PdfPTable table = new PdfPTable(headers.length);
            table.setWidthPercentage(100);
            table.setHeaderRows(1);

            for (String header : headers) {
                addHeaderCell(table, header, headerFont);
            }

            if (rows.isEmpty()) {
                PdfPCell empty = new PdfPCell(new Phrase("No records found.", cellFont));
                empty.setColspan(headers.length);
                empty.setPadding(8);
                table.addCell(empty);
            } else {
                for (String[] row : rows) {
                    for (int i = 0; i < headers.length; i++) {
                        addBodyCell(table, i < row.length ? row[i] : "", cellFont);
                    }
                }
            }

            document.add(table);
            document.close();
            return output.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate PDF report: " + e.getMessage(), e);
        }
    }
}
