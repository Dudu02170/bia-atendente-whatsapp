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

class BiaSendAuthorizationTest {
  private static final ObjectMapper JSON=new ObjectMapper();
  private static final String PHONE="550000000001", MESSAGE="Entrada D'Ávila QA\nCom 'aspas'.", ID="qa'id";
  private static final Map<String,String> queries=new HashMap<>();
  private static PostgreSQLContainer pg;
  private static Connection db;
  private static String sender;
  private Map<String,String> captured;

  @BeforeAll static void start() throws Exception {
    for(JsonNode node:JSON.readTree(Path.of("../workflows/bia-atendimento.json").toFile()).path("nodes")) {
      if(node.path("parameters").has("query")) queries.put(node.path("name").asText(),node.path("parameters").path("query").asText());
      if(node.path("name").asText().equals("Enviar mensagem")) sender=node.path("parameters").path("jsCode").asText();
    }
    pg=new PostgreSQLContainer("postgres:16-alpine").withDatabaseName("bia_qa").withUsername("qa").withPassword("qa-local-only");
    pg.start(); db=DriverManager.getConnection(pg.getJdbcUrl(),pg.getUsername(),pg.getPassword());
    try(var s=db.createStatement()) { s.execute(Files.readString(Path.of("../sql/01-schema.sql"))); }
  }
  @AfterAll static void stop() throws Exception {
    try { if(db!=null) db.close(); } finally { if(pg!=null) pg.stop(); }
  }
  @BeforeEach void prepare() throws Exception {
    try(var s=db.createStatement()) { s.execute("TRUNCATE leads_whatsapp,mensagens_whatsapp RESTART IDENTITY"); }
    receive(PHONE,"qa-older","Entrada mais antiga QA");
    captured=receive(PHONE,ID,MESSAGE);
    receive("550000000002","qa-other","Outro telefone QA");
    try(var s=db.createStatement()) {
      s.executeUpdate("INSERT INTO mensagens_whatsapp (telefone,role,mensagem) VALUES ('"+PHONE+"','assistant','Resposta anterior QA')");
    }
    // The initial gate really permitted this entry before the simulated context/AI delay.
    try(var s=db.createStatement();var rs=s.executeQuery(render("Verificar última mensagem",captured,Map.of()))) {
      assertTrue(rs.next()); assertTrue(rs.getBoolean("bot_ativo_atual")); assertTrue(rs.getBoolean("eh_ultima_mensagem"));
    }
  }

  @ParameterizedTest(name="antes do envio: {0}")
  @CsvSource({"atual,true","pausado,false","nulo,false","excluido,false","nova,false","semHistorico,false","legadoAtual,true","legadoAntigo,false"})
  void autorizaSomenteEstadoAtualEPreservaPayloadSemGravar(String scenario,boolean expected) throws Exception {
    Map<String,String> filter=new HashMap<>(Map.of("numero",PHONE,"messageId",ID,"mensagem",MESSAGE));
    try(var s=db.createStatement()) {
      switch(scenario) {
        case "pausado" -> s.executeUpdate("UPDATE leads_whatsapp SET bot_ativo=false WHERE telefone='"+PHONE+"'");
        case "nulo" -> s.executeUpdate("UPDATE leads_whatsapp SET bot_ativo=NULL WHERE telefone='"+PHONE+"'");
        case "excluido" -> s.executeUpdate("DELETE FROM leads_whatsapp WHERE telefone='"+PHONE+"'");
        case "nova" -> save(PHONE,"qa-new",MESSAGE); // Same text, distinct ID must supersede the old entry.
        case "semHistorico" -> s.executeUpdate("DELETE FROM mensagens_whatsapp WHERE telefone='"+PHONE+"'");
        case "legadoAtual" -> { filter.put("messageId",""); save(PHONE,"",MESSAGE); }
        case "legadoAntigo" -> { filter.put("messageId",""); filter.put("mensagem","Texto antigo QA"); }
        default -> { }
      }
    }
    var payload=Map.of("numero","550000000099","textoResposta","Resposta D'Ávila QA\nCaminho \\QA",
      "mensagem",MESSAGE,"message_id_atual",ID,"remoteJid",PHONE+"@s.whatsapp.net",
      "extraQA",Map.of("lista",List.of("aspas 'QA'",2,true)),"mensagem_origem_id",999999);
    String leads=snapshot("leads_whatsapp"),messages=snapshot("mensagens_whatsapp");
    Integer expectedOrigin=null;
    try(var s=db.createStatement();var rs=s.executeQuery("SELECT id FROM mensagens_whatsapp WHERE telefone='"+PHONE+"' AND role='user' ORDER BY id DESC LIMIT 1")) {
      if(rs.next()) expectedOrigin=rs.getInt(1);
    }
    Map<String,Object> authorization=new HashMap<>();
    try(var s=db.createStatement();var rs=s.executeQuery(render("Autorizar envio atual",payload,filter))) {
      assertEquals(expected,rs.next(),"Somente entrada autorizada produz um item para envio");
      if(expected) {
        assertEquals(Boolean.TRUE,rs.getObject("autorizado"));
        JsonNode data=JSON.readTree(rs.getString("dados_envio"));
        var expectedPayload=JSON.valueToTree(payload); ((com.fasterxml.jackson.databind.node.ObjectNode)expectedPayload).put("numero",PHONE).put("mensagem_origem_id",expectedOrigin);
        assertEquals(expectedPayload,data,"JSON preservado; destinatário vem da entrada canônica, não do payload de resposta");
        authorization.put("autorizado",rs.getObject("autorizado")); authorization.put("dados_envio",data);
        assertFalse(rs.next());
      }
    }
    // Feed the real JDBC authorization row to the real sender code with HTTP stub only.
    // An empty result also exercises the sender's defensive rejection of a missing authorization.
    JsonNode sent=runSender(authorization);
    assertFalse(sent.has("error")); assertEquals(expected?1:0,sent.path("calls").size());
    assertEquals(expected?1:0,sent.path("result").size());
    if(expected) {
      assertEquals(PHONE,sent.path("calls").get(0).path("body").path("number").asText());
      assertEquals(payload.get("textoResposta"),sent.path("calls").get(0).path("body").path("text").asText());
      assertEquals(JSON.valueToTree(payload.get("extraQA")),sent.path("result").get(0).path("json").path("extraQA"));
      assertTrue(sent.path("result").get(0).path("json").path("enviado").asBoolean());
    }
    assertEquals(leads,snapshot("leads_whatsapp")); assertEquals(messages,snapshot("mensagens_whatsapp"));
    if(expected) {
      Map<String,Object> sentRow=JSON.convertValue(sent.path("result").get(0).path("json"),
        new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>() {});
      try(var s=db.createStatement();var rs=s.executeQuery(render("Salvar resposta Bot",sentRow,filter))) {
        assertTrue(rs.next()); assertEquals(expectedOrigin,rs.getObject("mensagem_origem_id"));
        assertEquals("assistant",rs.getString("role")); assertFalse(rs.next());
      }
      // A sequential replay after the confirmed response is persisted must not send again.
      String recordedMessages=snapshot("mensagens_whatsapp"),history=snapshot("mensagens");
      try(var statement=db.createStatement();var replay=statement.executeQuery(render("Autorizar envio atual",payload,filter))) {
        assertFalse(replay.next(),"Resposta já vinculada impede nova autorização da mesma entrada");
      }
      JsonNode replaySender=runSender(Map.of());
      assertFalse(replaySender.has("error")); assertEquals(0,replaySender.path("calls").size());
      assertEquals(0,replaySender.path("result").size());
      assertEquals(recordedMessages,snapshot("mensagens_whatsapp")); assertEquals(history,snapshot("mensagens"));
      assertEquals(leads,snapshot("leads_whatsapp"));
    }
  }

  @ParameterizedTest(name="resposta prévia: {0}")
  @CsvSource({"mesmaOrigem,false","outraOrigem,true","semVinculo,true","outroPapel,true","outroTelefone,true"})
  void bloqueiaSomenteRespostaAssistantVinculadaAoMesmoTelefone(String scenario,boolean expected) throws Exception {
    int current,older;
    try(var statement=db.createStatement();var rows=statement.executeQuery(
        "SELECT id,message_id FROM mensagens_whatsapp WHERE telefone='"+PHONE+"' AND role='user' ORDER BY id")) {
      assertTrue(rows.next()); older=rows.getInt("id"); assertEquals("qa-older",rows.getString("message_id"));
      assertTrue(rows.next()); current=rows.getInt("id"); assertEquals(ID,rows.getString("message_id")); assertFalse(rows.next());
    }
    Map<String,String> filter=Map.of("numero",PHONE,"messageId",ID,"mensagem",MESSAGE);
    if(scenario.equals("outroPapel")) {
      // Keep this user row older than the current entry, so only role controls the result.
      try(var statement=db.createStatement()) { statement.executeUpdate(
        "UPDATE mensagens_whatsapp SET mensagem_origem_id="+current+" WHERE id="+older); }
    } else if(scenario.equals("outroTelefone")) {
      // Deliberately inconsistent legacy fixture; the production writer rejects this association.
      try(var statement=db.createStatement()) { statement.executeUpdate(
        "INSERT INTO mensagens_whatsapp(telefone,role,mensagem,mensagem_origem_id) VALUES ('550000000002','assistant','Outro telefone QA',"+current+")"); }
    } else {
      Map<String,Object> response=new HashMap<>(Map.of("numero",PHONE,"pushName","Cliente QA","textoResposta","Resposta registrada QA"));
      if(!scenario.equals("semVinculo")) response.put("mensagem_origem_id",scenario.equals("outraOrigem")?older:current);
      try(var statement=db.createStatement();var rows=statement.executeQuery(render("Salvar resposta Bot",response,filter))) {
        assertTrue(rows.next());
        assertEquals(scenario.equals("semVinculo")?null:scenario.equals("outraOrigem")?older:current,rows.getObject("mensagem_origem_id"));
        assertFalse(rows.next());
      }
    }
    String leads=snapshot("leads_whatsapp"),messages=snapshot("mensagens_whatsapp"),history=snapshot("mensagens");
    Map<String,Object> authorization=new HashMap<>();
    try(var statement=db.createStatement();var rows=statement.executeQuery(render("Autorizar envio atual",Map.of("textoResposta","Nova resposta QA"),filter))) {
      assertEquals(expected,rows.next());
      if(expected) {
        assertEquals(Boolean.TRUE,rows.getObject("autorizado"));
        JsonNode data=JSON.readTree(rows.getString("dados_envio"));
        assertEquals(current,data.path("mensagem_origem_id").asInt()); assertEquals(PHONE,data.path("numero").asText());
        authorization.put("autorizado",rows.getObject("autorizado")); authorization.put("dados_envio",data); assertFalse(rows.next());
      }
    }
    JsonNode sent=runSender(authorization);
    assertFalse(sent.has("error")); assertEquals(expected?1:0,sent.path("calls").size()); assertEquals(expected?1:0,sent.path("result").size());
    assertEquals(leads,snapshot("leads_whatsapp")); assertEquals(messages,snapshot("mensagens_whatsapp")); assertEquals(history,snapshot("mensagens"));
  }

  private static Map<String,String> receive(String phone,String id,String message) throws Exception {
    Map<String,String> input=Map.of("numero",phone,"nome","Cliente D'Ávila QA","mensagem",message,
      "remoteJid",phone+"@s.whatsapp.net","messageId",id,"tipoMensagem","conversation");
    Map<String,String> row=new HashMap<>();
    try(var s=db.createStatement();var rs=s.executeQuery(render("Criar ou Atualizar Lead",input,Map.of()))) {
      assertTrue(rs.next());
      for(String key:List.of("telefone","nome","mensagem_atual","remote_jid_atual","message_id_atual","tipo_mensagem_atual")) row.put(key,rs.getString(key));
      assertFalse(rs.next());
    }
    try(var s=db.createStatement()) { s.execute(render("Salvar mensagem do cliente",row,Map.of())); }
    return row;
  }
  private static void save(String phone,String id,String message) throws Exception {
    try(var s=db.createStatement()) { s.execute(render("Salvar mensagem do cliente",Map.of("telefone",phone,"nome","Cliente QA",
      "mensagem_atual",message,"message_id_atual",id,"remote_jid_atual",phone+"@s.whatsapp.net","tipo_mensagem_atual","conversation"),Map.of())); }
  }
  private static String snapshot(String table) throws Exception {
    try(var s=db.createStatement();var rs=s.executeQuery("SELECT COALESCE(jsonb_agg(to_jsonb(t) ORDER BY id),'[]'::jsonb)::text FROM "+table+" t")) {
      assertTrue(rs.next()); return rs.getString(1);
    }
  }
  private static String render(String name,Map<String,?> input,Map<String,?> filter) throws Exception {
    return process("support/render-query.cjs",Map.of("query",Objects.requireNonNull(queries.get(name)),"input",input,
      "nodes",Map.of("Filtrar e Extrair Dados",filter)));
  }
  private static JsonNode runSender(Map<String,?> authorization) throws Exception {
    return JSON.readTree(process("support/send-message.cjs",Map.of("code",sender,"input",authorization,
      "http",Map.of("response",Map.of("key",Map.of("id","qa-sent"))))));
  }
  private static String process(String script,Map<String,?> request) throws Exception {
    Process p=new ProcessBuilder("node",script).redirectErrorStream(true).start();
    try {
      try(var out=p.getOutputStream()) { out.write(JSON.writeValueAsBytes(request)); }
      assertTrue(p.waitFor(10,TimeUnit.SECONDS),"Timeout no adaptador isolado");
      String result=new String(p.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
      assertEquals(0,p.exitValue(),result); return result;
    } finally { if(p.isAlive()) p.destroyForcibly(); }
  }
}
