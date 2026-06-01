package com.rft.chat_sribiddi.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rft.chat_sribiddi.tools.api.ChatTool;
import com.rft.chat_sribiddi.tools.api.ToolResult;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.w3c.dom.NodeList;

public class SpreadsheetAnalysisTool implements ChatTool {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final int MAX_XLSX_ENTRY_BYTES = 2_000_000;

  @Override
  public String getName() {
    return "spreadsheet-analysis";
  }

  @Override
  public String getDisplayName() {
    return "Spreadsheet Analysis";
  }

  @Override
  public ToolResult execute(String userId, Map<String, Object> parameters) {
    String filename = stringValue(parameters, "filename");
    String content = stringValue(parameters, "content");
    String base64 = stringValue(parameters, "base64");
    return ToolResult.success(analyzeSpreadsheet(filename, content, base64));
  }

  @Tool(description = "Analyze CSV, TSV, or XLSX spreadsheet data and return schema, basic aggregates, and chart-ready series.")
  public String analyzeSpreadsheet(
      @ToolParam(description = "Filename with extension, e.g. data.csv or report.xlsx") String filename,
      @ToolParam(description = "CSV or TSV text content. Leave empty for XLSX base64.") String content,
      @ToolParam(description = "Base64-encoded XLSX file content, optional") String base64) {
    try {
      List<List<String>> rows =
          filename != null && filename.toLowerCase().endsWith(".xlsx")
              ? parseXlsx(base64)
              : parseDelimited(content, delimiter(filename, content));
      if (rows.isEmpty()) {
        return json(Map.of("type", "spreadsheet", "errorCode", "EMPTY_SPREADSHEET"));
      }
      List<String> headers = headers(rows);
      List<Map<String, Object>> columns = describeColumns(headers, rows.subList(1, rows.size()));
      return json(
          Map.of(
              "type", "spreadsheet",
              "filename", filename == null ? "spreadsheet" : filename,
              "rows", Math.max(0, rows.size() - 1),
              "columns", headers.size(),
              "schema", columns,
              "chartSeries", chartSeries(headers, rows.subList(1, rows.size()))));
    } catch (RuntimeException e) {
      return json(Map.of("type", "spreadsheet", "errorCode", "SPREADSHEET_PARSE_FAILED", "message", e.getMessage()));
    }
  }

  private static List<List<String>> parseDelimited(String content, char delimiter) {
    if (content == null || content.isBlank()) {
      return List.of();
    }
    List<List<String>> rows = new ArrayList<>();
    for (String line : content.replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
      if (!line.isBlank()) {
        rows.add(parseLine(line, delimiter));
      }
    }
    return rows;
  }

  private static List<String> parseLine(String line, char delimiter) {
    List<String> values = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    boolean quoted = false;
    for (int i = 0; i < line.length(); i++) {
      char c = line.charAt(i);
      if (c == '"') {
        if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
          current.append('"');
          i++;
        } else {
          quoted = !quoted;
        }
      } else if (c == delimiter && !quoted) {
        values.add(current.toString().trim());
        current.setLength(0);
      } else {
        current.append(c);
      }
    }
    values.add(current.toString().trim());
    return values;
  }

  private static List<List<String>> parseXlsx(String base64) {
    if (base64 == null || base64.isBlank()) {
      throw new IllegalArgumentException("XLSX base64 content is required");
    }
    byte[] bytes = Base64.getDecoder().decode(base64);
    Map<String, byte[]> entries = new LinkedHashMap<>();
    try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
      ZipEntry entry;
      while ((entry = zip.getNextEntry()) != null) {
        if (!entry.isDirectory()
            && (entry.getName().equals("xl/sharedStrings.xml")
                || entry.getName().equals("xl/worksheets/sheet1.xml"))) {
          entries.put(entry.getName(), readEntry(zip, MAX_XLSX_ENTRY_BYTES));
        }
      }
    } catch (IOException e) {
      throw new IllegalArgumentException("Invalid XLSX file", e);
    }
    List<String> sharedStrings = sharedStrings(entries.get("xl/sharedStrings.xml"));
    return sheetRows(entries.get("xl/worksheets/sheet1.xml"), sharedStrings);
  }

  private static byte[] readEntry(ZipInputStream zip, int maxBytes) throws IOException {
    byte[] buffer = new byte[8192];
    int total = 0;
    try (var output = new java.io.ByteArrayOutputStream()) {
      int read;
      while ((read = zip.read(buffer)) != -1) {
        total += read;
        if (total > maxBytes) {
          throw new IllegalArgumentException("XLSX entry is too large");
        }
        output.write(buffer, 0, read);
      }
      return output.toByteArray();
    }
  }

  private static List<String> sharedStrings(byte[] xml) {
    if (xml == null) {
      return List.of();
    }
    try {
      NodeList nodes =
          secureDocumentBuilder()
              .parse(new ByteArrayInputStream(xml))
              .getElementsByTagName("t");
      List<String> values = new ArrayList<>();
      for (int i = 0; i < nodes.getLength(); i++) {
        values.add(nodes.item(i).getTextContent());
      }
      return values;
    } catch (Exception e) {
      return List.of();
    }
  }

  private static List<List<String>> sheetRows(byte[] xml, List<String> sharedStrings) {
    if (xml == null) {
      return List.of();
    }
    try {
      var doc =
          secureDocumentBuilder()
              .parse(new ByteArrayInputStream(xml));
      NodeList rowNodes = doc.getElementsByTagName("row");
      List<List<String>> rows = new ArrayList<>();
      for (int i = 0; i < rowNodes.getLength(); i++) {
        NodeList cells = rowNodes.item(i).getChildNodes();
        List<String> row = new ArrayList<>();
        for (int j = 0; j < cells.getLength(); j++) {
          if (!"c".equals(cells.item(j).getNodeName())) {
            continue;
          }
          String type = attr(cells.item(j), "t");
          NodeList values = cells.item(j).getChildNodes();
          String raw = "";
          for (int k = 0; k < values.getLength(); k++) {
            if ("v".equals(values.item(k).getNodeName())) {
              raw = values.item(k).getTextContent();
            }
          }
          if ("s".equals(type)) {
            int index = Integer.parseInt(raw);
            row.add(index >= 0 && index < sharedStrings.size() ? sharedStrings.get(index) : "");
          } else {
            row.add(raw);
          }
        }
        rows.add(row);
      }
      return rows;
    } catch (Exception e) {
      throw new IllegalArgumentException("Could not read XLSX worksheet", e);
    }
  }

  private static DocumentBuilder secureDocumentBuilder() throws ParserConfigurationException {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
    factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
    factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
    factory.setXIncludeAware(false);
    factory.setExpandEntityReferences(false);
    return factory.newDocumentBuilder();
  }

  private static String attr(org.w3c.dom.Node node, String name) {
    var attrs = node.getAttributes();
    var attr = attrs == null ? null : attrs.getNamedItem(name);
    return attr == null ? "" : attr.getTextContent();
  }

  private static List<String> headers(List<List<String>> rows) {
    int width = rows.stream().mapToInt(List::size).max().orElse(0);
    List<String> headers = new ArrayList<>();
    List<String> first = rows.getFirst();
    for (int i = 0; i < width; i++) {
      String value = i < first.size() ? first.get(i) : "";
      headers.add(value == null || value.isBlank() ? "Column " + (i + 1) : value);
    }
    return headers;
  }

  private static List<Map<String, Object>> describeColumns(List<String> headers, List<List<String>> dataRows) {
    List<Map<String, Object>> columns = new ArrayList<>();
    for (int column = 0; column < headers.size(); column++) {
      int missing = 0;
      int numeric = 0;
      double sum = 0;
      for (List<String> row : dataRows) {
        String value = column < row.size() ? row.get(column) : "";
        if (value == null || value.isBlank()) {
          missing++;
        } else if (number(value) != null) {
          numeric++;
          sum += number(value);
        }
      }
      Map<String, Object> description = new LinkedHashMap<>();
      description.put("name", headers.get(column));
      description.put("type", numeric > Math.max(0, dataRows.size() - missing) / 2 ? "number" : "text");
      description.put("missing", missing);
      if (numeric > 0) {
        description.put("sum", sum);
        description.put("average", sum / numeric);
      }
      columns.add(description);
    }
    return columns;
  }

  private static Map<String, Object> chartSeries(List<String> headers, List<List<String>> dataRows) {
    if (headers.size() < 2) {
      return Map.of();
    }
    int numericColumn = -1;
    for (int i = 1; i < headers.size(); i++) {
      int numericCount = 0;
      for (List<String> row : dataRows) {
        if (i < row.size() && number(row.get(i)) != null) {
          numericCount++;
        }
      }
      if (numericCount > 0) {
        numericColumn = i;
        break;
      }
    }
    if (numericColumn < 0) {
      return Map.of();
    }
    List<String> labels = new ArrayList<>();
    List<Double> values = new ArrayList<>();
    for (List<String> row : dataRows.stream().limit(25).toList()) {
      labels.add(row.isEmpty() ? "" : row.getFirst());
      values.add(numericColumn < row.size() && number(row.get(numericColumn)) != null ? number(row.get(numericColumn)) : 0);
    }
    return Map.of("labelColumn", headers.getFirst(), "valueColumn", headers.get(numericColumn), "labels", labels, "values", values);
  }

  private static Double number(String value) {
    try {
      return Double.parseDouble(value.replace(",", "."));
    } catch (RuntimeException e) {
      return null;
    }
  }

  private static char delimiter(String filename, String content) {
    if (filename != null && filename.toLowerCase().endsWith(".tsv")) {
      return '\t';
    }
    return content != null && content.indexOf('\t') >= 0 ? '\t' : ',';
  }

  private static String json(Object value) {
    try {
      return MAPPER.writeValueAsString(value);
    } catch (IOException e) {
      return "{}";
    }
  }

  private static String stringValue(Map<String, Object> parameters, String key) {
    Object value = parameters == null ? null : parameters.get(key);
    return value == null ? null : value.toString();
  }
}
