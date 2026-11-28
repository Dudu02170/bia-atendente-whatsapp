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
    try (var s = db.createStatement()) { s.execute("TRUNCATE mensagens_whatsapp RESTART IDENTITY CASCADE"); }
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
    "sem ID não assume entrada identificada|550000000001|<empty>|Última QA com 'aspas'|false",
    "sem ID não assume outro telefone identificado|550000000002|<empty>|Outro telefone QA|false",
    "legado sem ID texto atual|550000000001|<empty>|Última QA com 'aspas'|true",
    "legado sem ID texto antigo|550000000001|<empty>|Primeira QA|false"
  }, delimiter='|', quoteCharacter='"')
  void onlyLatestUserMessagePasses(String scenario, String phone, String id, String message, boolean expected) throws Exception {
    if (id.equals("<empty>")) id = "";
    if(scenario.equals("legado sem ID texto atual")) {
      // This positive fallback fixture must actually represent a row without an external ID.
      try(var statement=db.createStatement()) {
        assertEquals(1,statement.executeUpdate("UPDATE mensagens_whatsapp SET message_id=NULL WHERE message_id='qa-latest'"));
      }
    }
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

  @ParameterizedTest(name="metadados: {0}")
  @CsvSource(value={
    "ID com apóstrofo|qa'id|conversation",
    "tipo com apóstrofo|qa-type|tipo'QA",
    "ambos com apóstrofos|qa''id|tipo''QA",
    "defaults legados|<empty>|<empty>"
  }, delimiter='|', quoteCharacter='"')
  void metadataSurvivesLeadAndMessageQueries(String scenario, String id, String type) throws Exception {
    if (id.equals("<empty>")) id = "";
    if (type.equals("<empty>")) type = "";
    String phone = "550000000004", name = "Cliente D'Ávila QA", message = "Mensagem 'fictícia' QA";
    var input = Map.of("numero", phone, "nome", name, "pushName", name,
      "mensagem", message, "remoteJid", phone + "@s.whatsapp.net", "messageId", id,
      "tipoMensagem", type, "dataHora", "2026-11-21T12:00:00.000Z");
    var expected = Map.of("telefone", phone, "nome", name, "mensagem_atual", message,
      "remote_jid_atual", phone + "@s.whatsapp.net", "message_id_atual", id, "tipo_mensagem_atual", type);

    var lead = output("Criar ou Atualizar Lead", input);
    assertEquals(expected, lead, scenario + ": retorno do lead preserva os valores originais");
    assertEquals(expected, output("Salvar mensagem do cliente", lead), scenario + ": retorno da gravação");
    assertEquals(expected, output("Salvar mensagem do cliente", lead), scenario + ": reenvio do mesmo ID");

    try (var s = db.prepareStatement("SELECT nome,role,mensagem,message_id,tipo_mensagem FROM mensagens_whatsapp WHERE telefone=? ORDER BY id")) {
      s.setString(1, phone);
      try (var rs = s.executeQuery()) {
        int count = 0;
        while (rs.next()) {
          count++;
          assertEquals(name, rs.getString("nome")); assertEquals("user", rs.getString("role"));
          assertEquals(message, rs.getString("mensagem"));
          assertEquals(id.isEmpty() ? null : id, rs.getString("message_id"));
          assertEquals(type, rs.getString("tipo_mensagem"));
        }
        assertEquals(id.isEmpty() ? 2 : 1, count,
          "Preserva unicidade de IDs e conversão do ID vazio para NULL; não comprova idempotência do fluxo");
      }
    }
    try (var s = db.createStatement(); var rs = s.executeQuery("SELECT count(*) FROM mensagens_whatsapp WHERE telefone <> '550000000004'")) {
      assertTrue(rs.next()); assertEquals(4, rs.getInt(1), "Histórico dos outros telefones preservado");
    }
  }

  @ParameterizedTest(name="estado atual: antes={0}, depois={1}")
  @CsvSource({"true,pausado,false", "false,ativo,true", "true,ativo,true", "true,nulo,false", "true,excluido,false"})
  void gateRecebeEstadoAtualDoLeadEmVezDaCopiaAnterior(boolean before, String current, boolean expected) throws Exception {
    String phone="550000000001", other="550000000002";
    try(var s=db.createStatement()) {
      s.executeUpdate("DELETE FROM leads_whatsapp WHERE telefone IN ('"+phone+"','"+other+"')");
    }
    try(var s=db.prepareStatement("INSERT INTO leads_whatsapp (telefone,nome,bot_ativo) VALUES (?,'Cliente QA',?),(?,'Outro QA',true)")) {
      s.setString(1,phone); s.setBoolean(2,before); s.setString(3,other); s.executeUpdate();
    }
    Map<String,String> input=Map.of("numero",phone,"nome","Cliente QA","mensagem","Última QA com 'aspas'",
      "remoteJid",phone+"@s.whatsapp.net","messageId","qa-latest","tipoMensagem","conversation");
    Map<String,String> captured=new HashMap<>();
    try(var s=db.createStatement();var rs=s.executeQuery(render("Criar ou Atualizar Lead",input))) {
      assertTrue(rs.next()); assertEquals(before,rs.getBoolean("bot_ativo"),"Estado capturado antes da espera");
      for(String key:List.of("telefone","nome","mensagem_atual","remote_jid_atual","message_id_atual","tipo_mensagem_atual"))
        captured.put(key,rs.getString(key));
      assertFalse(rs.next());
    }
    // Emulate an existing administrative database change between the actual lead query and the gate query.
    // No sleep, n8n runtime or external messaging is involved.
    try(var s=db.prepareStatement(current.equals("excluido")
        ? "DELETE FROM leads_whatsapp WHERE telefone=?"
        : "UPDATE leads_whatsapp SET bot_ativo="+(current.equals("nulo")?"NULL":current.equals("ativo")?"true":"false")+" WHERE telefone=?")) {
      s.setString(1,phone); assertEquals(1,s.executeUpdate());
    }
    String leadsBefore=snapshot("leads_whatsapp"), messagesBefore=snapshot("mensagens_whatsapp");
    try(var s=db.createStatement();var rs=s.executeQuery(render("Verificar última mensagem",captured))) {
      assertTrue(rs.next());
      assertEquals(expected,rs.getObject("bot_ativo_atual"),"Booleano atual, sem usar o estado capturado");
      assertEquals(Boolean.TRUE,rs.getObject("eh_ultima_mensagem"),"A comparação da última mensagem é preservada");
      assertEquals(phone,rs.getString("numero")); assertEquals("qa-latest",rs.getString("message_id_atual"));
      assertEquals("Última QA com 'aspas'",rs.getString("mensagem_atual"));
      assertEquals(phone+"@s.whatsapp.net",rs.getString("remoteJid")); assertFalse(rs.next());
    }
    assertEquals(leadsBefore,snapshot("leads_whatsapp"),"Consulta não modifica leads, inclusive o outro telefone");
    assertEquals(messagesBefore,snapshot("mensagens_whatsapp"),"Histórico completo permanece intacto");
  }

  private static String snapshot(String table) throws Exception {
    try(var s=db.createStatement();var rs=s.executeQuery("SELECT COALESCE(jsonb_agg(to_jsonb(t) ORDER BY id),'[]'::jsonb)::text FROM "+table+" t")) {
      assertTrue(rs.next()); return rs.getString(1);
    }
  }

  // JDBC consumes the INSERT count then the actual SELECT result from the exported writer.
  private static Map<String,String> output(String node, Map<String,String> input) throws Exception {
    try (var s = db.createStatement()) {
      boolean result = s.execute(render(node, input));
      while (!result) {
        assertNotEquals(-1, s.getUpdateCount(), "Consulta deve produzir uma saída: " + node);
        result = s.getMoreResults();
      }
      try (var rs = s.getResultSet()) {
        assertTrue(rs.next());
        Map<String,String> row = new LinkedHashMap<>();
        for (String key : List.of("telefone", "nome", "mensagem_atual", "remote_jid_atual", "message_id_atual", "tipo_mensagem_atual"))
          row.put(key, rs.getString(key));
        assertFalse(rs.next());
        return row;
      }
    }
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
