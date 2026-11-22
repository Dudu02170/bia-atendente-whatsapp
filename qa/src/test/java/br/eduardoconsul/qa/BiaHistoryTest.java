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
      s.execute("TRUNCATE mensagens,mensagens_whatsapp,leads RESTART IDENTITY CASCADE");
      s.executeUpdate("INSERT INTO leads (telefone,nome) VALUES ('550000000099','Outro QA')");
      s.executeUpdate("INSERT INTO mensagens (lead_id,telefone,direcao,conteudo) VALUES (1,'550000000099','entrada','Histórico de outro QA')");
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
    // The input to the final query is the actual JDBC row returned by the preceding query.
    var saved=saveBot(response);
    assertFalse(saved.containsKey("numero")); assertFalse(saved.containsKey("textoResposta"));
    assertEquals(PHONE,saved.get("telefone")); assertEquals(response,saved.get("mensagem"));
    try(var s=db.createStatement()) { assertEquals(2,s.executeUpdate(render("Salvar mensagem",saved,received))); }
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
    assertOtherHistory();
  }

  @Test void rejeicaoDaSaidaNaoDeixaEntradaParcialNoHistoricoFinal() throws Exception {
    var saved=saveBot("Resposta bloqueada QA");
    // Fault injection in this disposable database only; application/schema are not relaxed.
    try(var s=db.createStatement()) {
      s.execute("ALTER TABLE mensagens ADD CONSTRAINT qa_reject_response CHECK (conteudo <> 'Resposta bloqueada QA')");
      SQLException error=assertThrows(SQLException.class,()->s.executeUpdate(render("Salvar mensagem",saved,"Entrada QA")));
      assertEquals("23514",error.getSQLState());
      try(var rs=s.executeQuery("SELECT count(*) FROM mensagens WHERE telefone='"+PHONE+"'")) {
        assertTrue(rs.next()); assertEquals(0,rs.getInt(1),"O par entrada/saída pertence à mesma instrução SQL");
      }
      try(var rs=s.executeQuery("SELECT count(*) FROM mensagens_whatsapp WHERE telefone='"+PHONE+"' AND role='assistant'")) {
        assertTrue(rs.next()); assertEquals(1,rs.getInt(1),"Não há atomicidade entre os dois nós SQL distintos");
      }
    }
    assertOtherHistory();
  }

  private static Map<String,String> saveBot(String response) throws Exception {
    try(var s=db.createStatement();var rs=s.executeQuery(render("Salvar resposta Bot",Map.of("numero",PHONE,"pushName","Cliente D'Ávila QA","textoResposta",response),""))) {
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
