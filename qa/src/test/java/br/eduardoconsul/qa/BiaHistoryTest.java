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

  @ParameterizedTest(name="origem da resposta: {0}")
  @ValueSource(strings={"correta","outroTelefone","assistant","ausente","inexistente"})
  void associaSomenteOrigemUserDoMesmoTelefone(String scenario) throws Exception {
    int actualOrigin=originId();
    Integer requested=scenario.equals("ausente")?null:scenario.equals("inexistente")?999999:actualOrigin;
    if(scenario.equals("outroTelefone")||scenario.equals("assistant")) {
      String phone=scenario.equals("outroTelefone")?"550000000002":PHONE;
      String role=scenario.equals("assistant")?"assistant":"user";
      try(var s=db.createStatement();var rs=s.executeQuery("INSERT INTO mensagens_whatsapp (telefone,role,mensagem) VALUES ('"+phone+"','"+role+"','Origem incompatível QA') RETURNING id")) {
        assertTrue(rs.next()); requested=rs.getInt(1);
      }
    }
    var saved=saveBot("Resposta correlacionada QA","Entrada já recebida QA",requested);
    Integer expected=scenario.equals("correta")?actualOrigin:null;
    assertEquals(expected==null?null:expected.toString(),saved.get("mensagem_origem_id"));
    try(var s=db.prepareStatement("SELECT mensagem_origem_id,mensagem,role FROM mensagens_whatsapp WHERE id=?")) {
      s.setInt(1,Integer.parseInt(saved.get("id")));
      try(var rs=s.executeQuery()) {
        assertTrue(rs.next()); assertEquals(expected,rs.getObject("mensagem_origem_id"));
        assertEquals("Resposta correlacionada QA",rs.getString("mensagem")); assertEquals("assistant",rs.getString("role"));
      }
    }
    try(var s=db.createStatement();var rs=s.executeQuery("SELECT mensagem,message_id FROM mensagens_whatsapp WHERE id="+actualOrigin)) {
      assertTrue(rs.next()); assertEquals("Entrada já recebida QA",rs.getString("mensagem")); assertEquals("qa-incoming",rs.getString("message_id"));
    }
    assertOtherHistory();
  }

  @Test void migracaoRepetidaPreservaRegistrosLegadosEPermiteNovaCorrelacao() throws Exception {
    int origin=originId();
    try(var s=db.createStatement()) {
      // Recreate the preceding schema shape by removing only the newly introduced column/index/FK.
      s.execute("ALTER TABLE mensagens_whatsapp DROP COLUMN mensagem_origem_id CASCADE");
      s.executeUpdate("INSERT INTO mensagens_whatsapp (telefone,role,mensagem) VALUES ('"+PHONE+"','assistant','Resposta legada QA')");
    }
    String before=messagesWithoutOrigin();
    try(var s=db.createStatement()) {
      String migration=Files.readString(Path.of("../sql/migrations/002-resposta-origem.sql"));
      s.execute(migration); s.execute(migration);
      try(var rs=s.executeQuery("SELECT count(*) FROM mensagens_whatsapp WHERE mensagem_origem_id IS NOT NULL")) {
        assertTrue(rs.next()); assertEquals(0,rs.getInt(1),"Migração não inventa vínculos antigos");
      }
      try(var rs=s.executeQuery("SELECT count(*) FROM pg_indexes WHERE tablename='mensagens_whatsapp' AND indexname='idx_mensagens_whatsapp_origem'")) {
        assertTrue(rs.next()); assertEquals(1,rs.getInt(1));
      }
      try(var rs=s.executeQuery("SELECT count(*) FROM pg_constraint WHERE conrelid='mensagens_whatsapp'::regclass AND conname='mensagens_whatsapp_mensagem_origem_fkey' AND confrelid=conrelid AND confdeltype='n'")) {
        assertTrue(rs.next()); assertEquals(1,rs.getInt(1),"Migração instala FK para a mesma tabela com SET NULL");
      }
    }
    assertEquals(before,messagesWithoutOrigin(),"IDs, conteúdo e metadados antigos preservados");
    var saved=saveBot("Nova resposta QA","Entrada já recebida QA",origin);
    assertEquals(Integer.toString(origin),saved.get("mensagem_origem_id"));
    assertTrue(Integer.parseInt(saved.get("id"))>2,"Sequência existente não é reiniciada"); assertOtherHistory();
  }

  @Test void referenciaInexistenteERecusadaEExcluirOrigemPreservaResposta() throws Exception {
    int origin=originId();
    try(var s=db.createStatement()) {
      SQLException error=assertThrows(SQLException.class,()->s.executeUpdate("INSERT INTO mensagens_whatsapp (telefone,role,mensagem,mensagem_origem_id) VALUES ('"+PHONE+"','assistant','Inválida QA',999999)"));
      assertEquals("23503",error.getSQLState());
    }
    var saved=saveBot("Resposta preservada QA","Entrada já recebida QA",origin);
    try(var s=db.createStatement()) {
      assertEquals(1,s.executeUpdate("DELETE FROM mensagens_whatsapp WHERE id="+origin));
      try(var rs=s.executeQuery("SELECT mensagem,mensagem_origem_id FROM mensagens_whatsapp WHERE id="+saved.get("id"))) {
        assertTrue(rs.next()); assertEquals("Resposta preservada QA",rs.getString("mensagem")); assertNull(rs.getObject("mensagem_origem_id"));
      }
      try(var rs=s.executeQuery("SELECT count(*) FROM mensagens WHERE telefone='"+PHONE+"'")) {
        assertTrue(rs.next()); assertEquals(2,rs.getInt(1),"Excluir a origem não apaga o histórico final");
      }
    }
    assertOtherHistory();
  }

  private static int originId() throws Exception {
    try(var s=db.createStatement();var rs=s.executeQuery("SELECT id FROM mensagens_whatsapp WHERE telefone='"+PHONE+"' AND role='user'")) {
      assertTrue(rs.next()); return rs.getInt(1);
    }
  }
  private static String messagesWithoutOrigin() throws Exception {
    try(var s=db.createStatement();var rs=s.executeQuery("SELECT jsonb_agg(to_jsonb(t)-'mensagem_origem_id' ORDER BY id)::text FROM mensagens_whatsapp t")) {
      assertTrue(rs.next()); return rs.getString(1);
    }
  }

  private static Map<String,String> saveBot(String response,String received) throws Exception {
    return saveBot(response,received,null);
  }
  private static Map<String,String> saveBot(String response,String received,Integer origin) throws Exception {
    Map<String,Object> input=new HashMap<>(Map.of("numero",PHONE,"pushName","Cliente D'Ávila QA","textoResposta",response));
    if(origin!=null) input.put("mensagem_origem_id",origin);
    try(var s=db.createStatement();var rs=s.executeQuery(render("Salvar resposta Bot",input,received))) {
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
  private static String render(String node,Map<String,?> input,String received) throws Exception {
    Process process=new ProcessBuilder("node","support/render-query.cjs").redirectErrorStream(true).start();
    try {
      try(var stdin=process.getOutputStream()) { stdin.write(JSON.writeValueAsBytes(Map.of("query",Objects.requireNonNull(queries.get(node)),"input",input,"nodes",Map.of("Filtrar e Extrair Dados",Map.of("mensagem",received))))); }
      assertTrue(process.waitFor(10,TimeUnit.SECONDS),"Timeout no adaptador SQL");
      String output=new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
      assertEquals(0,process.exitValue(),output); return output;
    } finally { if(process.isAlive()) process.destroyForcibly(); }
  }
}
