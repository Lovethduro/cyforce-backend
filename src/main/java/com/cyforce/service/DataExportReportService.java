package com.cyforce.service;

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
import java.util.Map;

@Service
public class DataExportReportService {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    private static final int PDF_CELL_LIMIT = 120;
    private static final int PDF_ROW_LIMIT = 150;

    public byte[] toCsv(List<Map<String, Object>> users,
                        List<Map<String, Object>> tickets,
                        List<Map<String, Object>> leads) {
        StringBuilder csv = new StringBuilder();
        csv.append("CyForce Data Export\n");
        csv.append("Generated,").append(LocalDateTime.now().format(ISO)).append('\n');
        csv.append('\n');

        appendSection(csv, "Users", users,
                List.of("id", "fullName", "email", "phone", "role", "companyName", "active", "createdAt"));
        appendSection(csv, "Tickets", tickets,
                List.of("id", "subject", "customerName", "customerEmail", "status", "priority", "category", "assigneeName", "createdAt"));
        appendSection(csv, "Leads", leads,
                List.of("id", "name", "email", "phone", "company", "source", "status", "ownerName", "createdAt"));

        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    public byte[] toPdf(List<Map<String, Object>> users,
                        List<Map<String, Object>> tickets,
                        List<Map<String, Object>> leads) {
        Font titleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 16);
        Font metaFont = FontFactory.getFont(FontFactory.HELVETICA, 10);
        Font sectionFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12);
        Font headerFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8, Font.NORMAL, Color.WHITE);
        Font cellFont = FontFactory.getFont(FontFactory.HELVETICA, 7);

        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Document document = new Document(PageSize.A4.rotate(), 36, 36, 36, 36);
            PdfWriter.getInstance(document, output);
            document.open();

            document.add(new Paragraph("CyForce Data Export", titleFont));
            document.add(new Paragraph("Generated: " + LocalDateTime.now().format(ISO), metaFont));
            document.add(new Paragraph(
                    "Users: " + users.size() + " · Tickets: " + tickets.size() + " · Leads: " + leads.size(),
                    metaFont));
            document.add(new Paragraph(" "));

            addTableSection(document, sectionFont, headerFont, cellFont, "Users", users,
                    List.of("Name", "Email", "Role", "Company", "Active", "Created"),
                    row -> new String[]{
                            str(row.get("fullName")),
                            str(row.get("email")),
                            str(row.get("role")),
                            str(row.get("companyName")),
                            str(row.get("active")),
                            str(row.get("createdAt"))
                    });

            addTableSection(document, sectionFont, headerFont, cellFont, "Support Tickets", tickets,
                    List.of("Subject", "Customer", "Status", "Priority", "Assignee", "Created"),
                    row -> new String[]{
                            str(row.get("subject")),
                            str(row.get("customerName")),
                            str(row.get("status")),
                            str(row.get("priority")),
                            str(row.get("assigneeName")),
                            str(row.get("createdAt"))
                    });

            addTableSection(document, sectionFont, headerFont, cellFont, "Leads & Customers", leads,
                    List.of("Name", "Email", "Company", "Source", "Status", "Owner", "Created"),
                    row -> new String[]{
                            str(row.get("name")),
                            str(row.get("email")),
                            str(row.get("company")),
                            str(row.get("source")),
                            str(row.get("status")),
                            str(row.get("ownerName")),
                            str(row.get("createdAt"))
                    });

            document.close();
            return output.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate PDF export: " + e.getMessage(), e);
        }
    }

    private void appendSection(StringBuilder csv,
                               String title,
                               List<Map<String, Object>> rows,
                               List<String> columns) {
        csv.append(title).append('\n');
        csv.append(String.join(",", columns)).append('\n');
        for (Map<String, Object> row : rows) {
            for (int i = 0; i < columns.size(); i++) {
                if (i > 0) {
                    csv.append(',');
                }
                csv.append(csvCell(str(row.get(columns.get(i)))));
            }
            csv.append('\n');
        }
        csv.append('\n');
    }

    private void addTableSection(Document document,
                                 Font sectionFont,
                                 Font headerFont,
                                 Font cellFont,
                                 String title,
                                 List<Map<String, Object>> rows,
                                 List<String> headers,
                                 java.util.function.Function<Map<String, Object>, String[]> mapper) throws Exception {
        document.add(new Paragraph(title + " (" + rows.size() + ")", sectionFont));
        document.add(new Paragraph(" "));

        PdfPTable table = new PdfPTable(headers.size());
        table.setWidthPercentage(100);
        table.setHeaderRows(1);

        for (String header : headers) {
            addHeaderCell(table, header, headerFont);
        }

        if (rows.isEmpty()) {
            PdfPCell empty = new PdfPCell(new Phrase("No records.", cellFont));
            empty.setColspan(headers.size());
            empty.setPadding(8);
            table.addCell(empty);
        } else {
            int limit = Math.min(rows.size(), PDF_ROW_LIMIT);
            for (int i = 0; i < limit; i++) {
                for (String value : mapper.apply(rows.get(i))) {
                    addBodyCell(table, value, cellFont);
                }
            }
            if (rows.size() > PDF_ROW_LIMIT) {
                PdfPCell note = new PdfPCell(new Phrase(
                        "Showing first " + PDF_ROW_LIMIT + " of " + rows.size() + " records.",
                        cellFont));
                note.setColspan(headers.size());
                note.setPadding(6);
                table.addCell(note);
            }
        }

        document.add(table);
        document.add(new Paragraph(" "));
    }

    private void addHeaderCell(PdfPTable table, String text, Font font) {
        PdfPCell cell = new PdfPCell(new Phrase(text, font));
        cell.setBackgroundColor(new Color(43, 92, 230));
        cell.setPadding(5);
        table.addCell(cell);
    }

    private void addBodyCell(PdfPTable table, String text, Font font) {
        PdfPCell cell = new PdfPCell(new Phrase(truncate(text), font));
        cell.setPadding(4);
        cell.setVerticalAlignment(Element.ALIGN_TOP);
        table.addCell(cell);
    }

    private String str(Object value) {
        return value == null ? "" : value.toString();
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
}
