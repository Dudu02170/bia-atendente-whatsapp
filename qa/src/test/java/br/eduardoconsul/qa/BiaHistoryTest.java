package br.eduardoconsul.qa;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class BiaHistoryTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static PostgreSQLContainer pg;
  private static Connection db;
  private static final Map<String,String> queries = new HashMap<>();
  private static final String PHONE = "550000000001";

  @BeforeAll static void start() throws Exception {
    for (JsonNode node : JSON.readTree(Path.of("../workflows/bia-atendimento.json").toFile()).path("nodes"))
      if (node.path("parameters").has("query"))
        queries.put(node.path("name").asText(),node.path("parameters").path("query").asText());
    pg=new PostgreSQLContainer("postgres:16-alpine").withDatabaseName("bia_qa").withUsername("qa").withPassword("qa-local-only");
    pg.start(); db=DriverManager.getConnection(pg.getJdbcUrl(),pg.getUsername(),pg.getPassword());
    try(var s=db.createStatement()) { s.execute(Files.readString(Path.of("../sql/01-schema.sql"))); }
  }
  @AfterAll static void stop() throws Exception {
    try { if(db!=null) db.close(); } finally { if(pg!=null) pg.stop(); }
  }
  @BeforeEach void reset() throws Exception {
    try(var s=db.createStatement()) {
      s.execute("ALTER TABLE mensagens DROP CONSTRAINT IF EXISTS qa_reject_response");
      s.execute("ALTER TABLE mensagens DROP CONSTRAINT IF EXISTS qa_reject_entry");
      s.execute("ALTER TABLE mensagens_whatsapp DROP CONSTRAINT IF EXISTS qa_reject_assistant");
      s.execute("TRUNCATE mensagens,mensagens_whatsapp,leads RESTART IDENTITY CASCADE");
      s.executeUpdate("INSERT INTO leads (telefone,nome) VALUES ('550000000099','Outro QA')");
      s.executeUpdate("INSERT INTO mensagens (lead_id,telefone,direcao,conteudo) VALUES (1,'550000000099','entrada','Histórico de outro QA')");
      s.executeUpdate("INSERT INTO mensagens_whatsapp (telefone,role,mensagem,message_id) VALUES ('"+PHONE+"','user','Entrada já recebida QA','qa-incoming')");
    }
  }

  @ParameterizedTest(name="histórico: {0}")
  @ValueSource(strings={"simples", "aspas e quebras", "sem lead"})
  void gravaCamposReaisDaRespostaBotSemAlterarOutrosRegistros(String scenario) throws Exception {
    boolean hasLead=!scenario.equals("sem lead");
    String received=scenario.equals("simples")?"Entrada QA":"Projeto D'Ávila QA\nQuero 'orientação'.";
    String response=scenario.equals("simples")?"Resposta QA":"Resposta 'fictícia' QA\nD'Ávila.";
    Integer expectedLead=null;
    if(hasLead) try(var s=db.createStatement();var rs=s.executeQuery("INSERT INTO leads (telefone,nome) VALUES ('"+PHONE+"','Lead QA') RETURNING id")) {
      assertTrue(rs.next()); expectedLead=rs.getInt(1);
    }
    // Execute the single exported writer; its returned row is the real assistant INSERT result.
    var saved=saveBot(response,received);
    assertFalse(saved.containsKey("numero")); assertFalse(saved.containsKey("textoResposta"));
    assertEquals(PHONE,saved.get("telefone")); assertEquals(response,saved.get("mensagem"));
    assertEquals("assistant",saved.get("role")); assertNotNull(saved.get("id"));
    try(var s=db.prepareStatement("SELECT lead_id,direcao,conteudo FROM mensagens WHERE telefone=? ORDER BY id")) {
      s.setString(1,PHONE);
      try(var rs=s.executeQuery()) {
        assertTrue(rs.next()); assertEquals(expectedLead,rs.getObject("lead_id"));
        assertEquals("entrada",rs.getString("direcao")); assertEquals(received,rs.getString("conteudo"));
        assertTrue(rs.next()); assertEquals(expectedLead,rs.getObject("lead_id"));
        assertEquals("saida",rs.getString("direcao")); assertEquals(response,rs.getString("conteudo"));
        assertFalse(rs.next());
      }
    }
    assertMessageCounts(1);
    assertOtherHistory();
  }

  @Test void rejeicaoDaSaidaNaoDeixaEntradaParcialNoHistoricoFinal() throws Exception {
    // Fault injection in this disposable database only; application/schema are not relaxed.
    try(var s=db.createStatement()) {
      s.execute("ALTER TABLE mensagens ADD CONSTRAINT qa_reject_response CHECK (conteudo <> 'Resposta bloqueada QA')");
      SQLException error=assertThrows(SQLException.class,()->saveBot("Resposta bloqueada QA","Entrada QA"));
      assertEquals("23514",error.getSQLState());
      try(var rs=s.executeQuery("SELECT count(*) FROM mensagens WHERE telefone='"+PHONE+"'")) {
        assertTrue(rs.next()); assertEquals(0,rs.getInt(1),"O par entrada/saída pertence à mesma instrução SQL");
      }
      try(var rs=s.executeQuery("SELECT count(*) FROM mensagens_whatsapp WHERE telefone='"+PHONE+"' AND role='assistant'")) {
        assertTrue(rs.next()); assertEquals(0,rs.getInt(1),"A resposta pertence à mesma instrução SQL que o histórico");
      }
      assertMessageCounts(0);
      s.execute("ALTER TABLE mensagens DROP CONSTRAINT qa_reject_response");
    }
    // A failed database statement can be rerun directly without sending any external message.
    saveBot("Resposta bloqueada QA","Entrada QA");
    assertMessageCounts(1);
    try(var s=db.createStatement();var rs=s.executeQuery("SELECT count(*) FROM mensagens WHERE telefone='"+PHONE+"'")) {
      assertTrue(rs.next()); assertEquals(2,rs.getInt(1),"A falha anterior não deixou um histórico duplicado");
    }
    assertOtherHistory();
  }

  @ParameterizedTest(name="falha atômica: {0}")
  @ValueSource(strings={"entrada","assistant"})
  void falhasEmOutrosRegistrosTambemRevertemTodaPersistencia(String stage) throws Exception {
    try(var s=db.createStatement()) {
      s.execute(stage.equals("entrada")
        ? "ALTER TABLE mensagens ADD CONSTRAINT qa_reject_entry CHECK (conteudo <> 'Entrada bloqueada QA')"
        : "ALTER TABLE mensagens_whatsapp ADD CONSTRAINT qa_reject_assistant CHECK (role <> 'assistant')");
      SQLException error=assertThrows(SQLException.class,()->saveBot("Resposta QA","Entrada bloqueada QA"));
      assertEquals("23514",error.getSQLState());
      try(var rs=s.executeQuery("SELECT count(*) FROM mensagens WHERE telefone='"+PHONE+"'")) {
        assertTrue(rs.next()); assertEquals(0,rs.getInt(1),"Não deixa entrada/saída parciais");
      }
    }
    assertMessageCounts(0); assertOtherHistory();
  }

  private static void assertMessageCounts(int assistants) throws Exception {
    try(var s=db.createStatement();var rs=s.executeQuery("SELECT role,mensagem,message_id FROM mensagens_whatsapp WHERE telefone='"+PHONE+"' ORDER BY id")) {
      assertTrue(rs.next()); assertEquals("user",rs.getString("role"));
      assertEquals("Entrada já recebida QA",rs.getString("mensagem")); assertEquals("qa-incoming",rs.getString("message_id"));
      int count=0; while(rs.next()) { assertEquals("assistant",rs.getString("role")); count++; }
      assertEquals(assistants,count,"Preserva a entrada anterior e só confirma assistant após gravar todo o histórico");
    }
  }

  private static Map<String,String> saveBot(String response,String received) throws Exception {
    try(var s=db.createStatement();var rs=s.executeQuery(render("Salvar resposta Bot",Map.of("numero",PHONE,"pushName","Cliente D'Ávila QA","textoResposta",response),received))) {
      assertTrue(rs.next()); Map<String,String> row=new HashMap<>();
      for(int i=1;i<=rs.getMetaData().getColumnCount();i++) row.put(rs.getMetaData().getColumnLabel(i),rs.getString(i));
      assertFalse(rs.next()); return row;
    }
  }
  private static void assertOtherHistory() throws Exception {
    try(var s=db.createStatement();var rs=s.executeQuery("SELECT telefone,direcao,conteudo FROM mensagens WHERE telefone <> '"+PHONE+"'")) {
      assertTrue(rs.next()); assertEquals("550000000099",rs.getString("telefone"));
      assertEquals("entrada",rs.getString("direcao")); assertEquals("Histórico de outro QA",rs.getString("conteudo"));
      assertFalse(rs.next(),"Não cria registros com telefone undefined");
    }
  }
  private static String render(String node,Map<String,String> input,String received) throws Exception {
    Process process=new ProcessBuilder("node","support/render-query.cjs").redirectErrorStream(true).start();
    try {
      try(var stdin=process.getOutputStream()) { stdin.write(JSON.writeValueAsBytes(Map.of("query",Objects.requireNonNull(queries.get(node)),"input",input,"nodes",Map.of("Filtrar e Extrair Dados",Map.of("mensagem",received))))); }
      assertTrue(process.waitFor(10,TimeUnit.SECONDS),"Timeout no adaptador SQL");
      String output=new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
      assertEquals(0,process.exitValue(),output); return output;
    } finally { if(process.isAlive()) process.destroyForcibly(); }
  }
}
