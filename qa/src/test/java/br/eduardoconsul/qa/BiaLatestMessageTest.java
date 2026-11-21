package br.eduardoconsul.qa;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class BiaLatestMessageTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static PostgreSQLContainer pg;
  private static Connection db;
  private static final Map<String,String> queries = new HashMap<>();

  @BeforeAll static void start() throws Exception {
    for (JsonNode node : JSON.readTree(Path.of("../workflows/bia-atendimento.json").toFile()).path("nodes"))
      if (node.path("parameters").has("query"))
        queries.put(node.path("name").asText(), node.path("parameters").path("query").asText());
    pg = new PostgreSQLContainer("postgres:16-alpine")
      .withDatabaseName("bia_qa").withUsername("qa").withPassword("qa-local-only");
    pg.start();
    db = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
    try (var s = db.createStatement()) {
      s.execute(Files.readString(Path.of("../sql/01-schema.sql")));
    }
  }

  @AfterAll static void stop() throws Exception {
    try { if (db != null) db.close(); }
    finally { if (pg != null) pg.stop(); }
  }

  @BeforeEach void messages() throws Exception {
    try (var s = db.createStatement()) { s.execute("TRUNCATE mensagens_whatsapp RESTART IDENTITY"); }
    // Execute the actual writer query too: fixtures must use the role written by the application.
    save("550000000001", "qa-old", "Primeira QA");
    save("550000000001", "qa-latest", "Última QA com 'aspas'");
    save("550000000002", "qa-other", "Outro telefone QA");
    try (var s = db.createStatement()) {
      s.executeUpdate("INSERT INTO mensagens_whatsapp (telefone,role,mensagem,message_id) " +
        "VALUES ('550000000001','assistant','Resposta fictícia','qa-bot')");
    }
  }

  @ParameterizedTest(name="{0}")
  @CsvSource(value={
    "último ID|550000000001|qa-latest|Última QA com 'aspas'|true",
    "ID antigo|550000000001|qa-old|Primeira QA|false",
    "ID desconhecido mesmo texto|550000000001|qa-missing|Última QA com 'aspas'|false",
    "outro telefone|550000000002|qa-other|Outro telefone QA|true",
    "telefone sem histórico|550000000003|qa-latest|Última QA com 'aspas'|false",
    "legado sem ID texto atual|550000000001|<empty>|Última QA com 'aspas'|true",
    "legado sem ID texto antigo|550000000001|<empty>|Primeira QA|false"
  }, delimiter='|', quoteCharacter='"')
  void onlyLatestUserMessagePasses(String scenario, String phone, String id, String message, boolean expected) throws Exception {
    if (id.equals("<empty>")) id = "";
    var input = Map.of("telefone", phone, "message_id_atual", id, "mensagem_atual", message,
      "remote_jid_atual", phone + "@s.whatsapp.net", "nome", "Cliente QA");
    try (var s = db.createStatement(); var rs = s.executeQuery(render("Verificar última mensagem", input))) {
      assertTrue(rs.next(), scenario);
      assertEquals(expected, rs.getBoolean("eh_ultima_mensagem"), scenario);
      assertEquals(phone, rs.getString("numero"));
      assertEquals(id, rs.getString("message_id_atual"));
      assertEquals(message, rs.getString("mensagem_atual"));
      assertEquals(phone + "@s.whatsapp.net", rs.getString("remoteJid"));
      assertEquals("Cliente QA", rs.getString("pushName"));
      assertFalse(rs.next(), "Uma decisão por entrada");
    }
    try (var s = db.createStatement(); var rs = s.executeQuery("SELECT count(*) FROM mensagens_whatsapp")) {
      assertTrue(rs.next()); assertEquals(4, rs.getInt(1), "Verificação não modifica o histórico");
    }
  }

  private static void save(String phone, String id, String text) throws Exception {
    var input = Map.of("telefone", phone, "nome", "Cliente QA", "mensagem_atual", text,
      "message_id_atual", id, "remote_jid_atual", phone + "@s.whatsapp.net", "tipo_mensagem_atual", "conversation");
    try (var s = db.createStatement()) { s.execute(render("Salvar mensagem do cliente", input)); }
  }

  private static String render(String node, Map<String,String> input) throws Exception {
    String query = Objects.requireNonNull(queries.get(node), "Consulta ausente: " + node);
    Process process = new ProcessBuilder("node", "support/render-query.cjs").start();
    try {
      try (var out = process.getOutputStream()) { JSON.writeValue(out, Map.of("query", query, "input", input)); }
      assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Timeout no adaptador SQL isolado");
      assertEquals(0, process.exitValue(), new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));
      return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    } finally { process.destroyForcibly(); }
  }
}
