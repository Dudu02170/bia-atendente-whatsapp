package br.eduardoconsul.qa;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.core.type.TypeReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class BiaEntryIdentityTest {
  private static final ObjectMapper JSON=new ObjectMapper();
  private static final String PHONE="550000000001", OTHER="550000000002", TEXT="Entrada D'Ávila QA\nTexto igual";
  private static final Map<String,String> queries=new HashMap<>();
  private static PostgreSQLContainer pg;
  private static Connection db;
  private static String sender;

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
  @BeforeEach void clear() throws Exception {
    try(var s=db.createStatement()) { s.execute("TRUNCATE mensagens,leads,leads_whatsapp,mensagens_whatsapp RESTART IDENTITY CASCADE"); }
  }

  @ParameterizedTest(name="identidade gravada, ID externo={0}")
  @ValueSource(booleans={true,false})
  void escritorRetornaIdentidadeRealPreservandoMetadados(boolean identified) throws Exception {
    var first=receive(PHONE,identified?"qa'id":"",TEXT);
    assertInstanceOf(Integer.class,first.get("entrada_id")); assertTrue((Integer)first.get("entrada_id")>0);
    assertEquals(PHONE,first.get("telefone")); assertEquals(TEXT,first.get("mensagem_atual"));
    assertEquals("Cliente D'Ávila QA",first.get("nome")); assertEquals(PHONE+"@s.whatsapp.net",first.get("remote_jid_atual"));
    assertEquals(identified?"qa'id":"",first.get("message_id_atual")); assertEquals("tipo'QA",first.get("tipo_mensagem_atual"));
    var second=receive(PHONE,identified?"qa'id":"",TEXT);
    if(identified) assertEquals(first.get("entrada_id"),second.get("entrada_id"));
    else assertNotEquals(first.get("entrada_id"),second.get("entrada_id"),"Cada entrada sem ID recebe identidade própria");
    try(var s=db.createStatement();var rows=s.executeQuery("SELECT count(*) FROM mensagens_whatsapp")) {
      assertTrue(rows.next()); assertEquals(identified?1:2,rows.getInt(1));
    }
    try(var s=db.createStatement();var rows=s.executeQuery("SELECT role,mensagem,message_id FROM mensagens_whatsapp WHERE id="+first.get("entrada_id"))) {
      assertTrue(rows.next()); assertEquals("user",rows.getString("role")); assertEquals(TEXT,rows.getString("mensagem"));
      assertEquals(identified?"qa'id":null,rows.getString("message_id")); assertFalse(rows.next());
    }
  }

  @ParameterizedTest(name="entrada substituída: ID={0}, fase={1}")
  @CsvSource({"true,espera","false,espera","true,envio","false,envio"})
  void identidadeAntigaNaoAssumeNovaEntradaDeTextoIgual(boolean identified,String phase) throws Exception {
    var first=receive(PHONE,identified?"qa-first":"",TEXT);
    receive(OTHER,"qa-other",TEXT); // Other conversations must not affect the decision.
    assertGate(first,true);
    var second=receive(PHONE,identified?"qa-second":"",TEXT);
    assertNotEquals(first.get("entrada_id"),second.get("entrada_id"));
    String before=snapshot();
    if(phase.equals("espera")) assertGate(first,false);
    // The final gate must use the original writer row even if a response payload suggests the new ID.
    var stale=authorize(first,Map.of("textoResposta","Resposta obsoleta QA","entrada_id",second.get("entrada_id"),"mensagem_origem_id",second.get("entrada_id")));
    assertEquals(0,stale.size()); assertEquals(0,send(Map.of()).path("calls").size());
    assertGate(second,true);
    var authorized=authorize(second,Map.of("textoResposta","Resposta atual D'Ávila QA"));
    assertEquals(1,authorized.size());
    JsonNode data=JSON.readTree(authorized.get(0).get("dados_envio").toString());
    assertEquals(second.get("entrada_id"),data.path("mensagem_origem_id").intValue());
    assertEquals(before,snapshot(),"Verificações não escrevem no banco");
    var sent=send(Map.of("autorizado",authorized.get(0).get("autorizado"),"dados_envio",data));
    assertFalse(sent.has("error")); assertEquals(1,sent.path("calls").size());
    assertEquals(PHONE,sent.path("calls").get(0).path("body").path("number").asText());
    Map<String,Object> sentRow=JSON.convertValue(sent.path("result").get(0).path("json"),new TypeReference<Map<String,Object>>() {});
    var persisted=execute("Salvar resposta Bot",sentRow,second);
    assertEquals(1,persisted.size()); assertEquals(second.get("entrada_id"),persisted.get(0).get("mensagem_origem_id"));
    before=snapshot(); assertEquals(0,authorize(second,Map.of("textoResposta","Não repetir QA")).size());
    assertEquals(before,snapshot());
  }

  @ParameterizedTest(name="identidade incompatível: {0}")
  @ValueSource(strings={"outroTelefone","assistant","inexistente","texto","fracao"})
  void identidadeInformadaInvalidaRecusaSemFallback(String scenario) throws Exception {
    var other=receive(OTHER,"qa-other",TEXT);
    var current=receive(PHONE,"",TEXT);
    Object invalid=switch(scenario) {
      case "outroTelefone" -> other.get("entrada_id");
      case "assistant" -> {
        try(var s=db.createStatement();var rows=s.executeQuery("INSERT INTO mensagens_whatsapp(telefone,role,mensagem) VALUES ('"+PHONE+"','assistant','Resposta QA') RETURNING id")) {
          assertTrue(rows.next()); yield rows.getInt(1);
        }
      }
      case "inexistente" -> 999999;
      case "texto" -> current.get("entrada_id").toString();
      default -> 1.5;
    };
    var bad=new HashMap<>(current); bad.put("entrada_id",invalid);
    String before=snapshot(); assertGate(bad,false);
    assertEquals(0,authorize(bad,Map.of("textoResposta","Não enviar QA")).size());
    assertEquals(0,send(Map.of()).path("calls").size()); assertEquals(before,snapshot());
  }

  @ParameterizedTest(name="colisão de ID externo: {0}")
  @ValueSource(strings={"telefone","role","texto"})
  void escritorNaoDevolveIdentidadeDeEventoIncompativel(String scenario) throws Exception {
    if(scenario.equals("role")) {
      try(var s=db.createStatement()) { s.executeUpdate("INSERT INTO mensagens_whatsapp(telefone,role,mensagem,message_id) VALUES ('"+PHONE+"','assistant','Resposta QA','qa-collision')"); }
    } else receive(scenario.equals("telefone")?OTHER:PHONE,"qa-collision",TEXT);
    String before=snapshot();
    var input=Map.of("telefone",PHONE,"nome","Cliente QA","mensagem_atual",scenario.equals("texto")?"Outro texto QA":TEXT,
      "remote_jid_atual",PHONE+"@s.whatsapp.net","message_id_atual","qa-collision","tipo_mensagem_atual","conversation");
    assertEquals(0,execute("Salvar mensagem do cliente",input,Map.of()).size()); assertEquals(before,snapshot());
  }

  private static Map<String,Object> receive(String phone,String id,String text) throws Exception {
    var lead=execute("Criar ou Atualizar Lead",Map.of("numero",phone,"nome","Cliente D'Ávila QA","mensagem",text,
      "remoteJid",phone+"@s.whatsapp.net","messageId",id,"tipoMensagem","tipo'QA"),Map.of());
    assertEquals(1,lead.size());
    var rows=execute("Salvar mensagem do cliente",lead.get(0),Map.of()); assertEquals(1,rows.size()); return rows.get(0);
  }
  private static void assertGate(Map<String,Object> entry,boolean expected) throws Exception {
    var rows=execute("Verificar última mensagem",entry,entry); assertEquals(1,rows.size());
    assertEquals(expected,rows.get(0).get("eh_ultima_mensagem")); assertEquals(Boolean.TRUE,rows.get(0).get("bot_ativo_atual"));
    assertEquals(entry.get("entrada_id") instanceof Integer?entry.get("entrada_id"):null,rows.get(0).get("entrada_id"));
  }
  private static List<Map<String,Object>> authorize(Map<String,Object> entry,Map<String,?> response) throws Exception {
    return execute("Autorizar envio atual",response,entry);
  }
  private static List<Map<String,Object>> execute(String name,Map<String,?> input,Map<String,?> entry) throws Exception {
    var filter=Map.of("numero",(entry.containsKey("telefone")?entry.get("telefone"):PHONE),"messageId",(entry.containsKey("message_id_atual")?entry.get("message_id_atual"):""),"mensagem",(entry.containsKey("mensagem_atual")?entry.get("mensagem_atual"):TEXT));
    String sql=process("support/render-query.cjs",Map.of("query",queries.get(name),"input",input,
      "nodes",Map.of("Filtrar e Extrair Dados",filter,"Salvar mensagem do cliente",entry)));
    try(var s=db.createStatement();var rs=s.executeQuery(sql)) {
      List<Map<String,Object>> rows=new ArrayList<>();var meta=rs.getMetaData();
      while(rs.next()) {
        Map<String,Object> row=new HashMap<>();
        for(int i=1;i<=meta.getColumnCount();i++) row.put(meta.getColumnLabel(i),rs.getObject(i)); rows.add(row);
      }
      return rows;
    }
  }
  private static JsonNode send(Map<String,?> input) throws Exception {
    return JSON.readTree(process("support/send-message.cjs",Map.of("code",sender,"input",input,"http",Map.of("response",Map.of("key",Map.of("id","qa-sent"))))));
  }
  private static String snapshot() throws Exception {
    StringBuilder result=new StringBuilder();
    for(String table:List.of("leads","leads_whatsapp","mensagens_whatsapp","mensagens"))
      try(var s=db.createStatement();var rs=s.executeQuery("SELECT COALESCE(jsonb_agg(to_jsonb(t) ORDER BY id),'[]'::jsonb)::text FROM "+table+" t")) {
        assertTrue(rs.next()); result.append(table).append(rs.getString(1));
      }
    return result.toString();
  }
  private static String process(String script,Map<String,?> request) throws Exception {
    Process p=new ProcessBuilder("node",script).redirectErrorStream(true).start();
    try {
      try(var out=p.getOutputStream()) { out.write(JSON.writeValueAsBytes(request)); }
      assertTrue(p.waitFor(10,TimeUnit.SECONDS));
      String result=new String(p.getInputStream().readAllBytes(),StandardCharsets.UTF_8); assertEquals(0,p.exitValue(),result); return result;
    } finally { if(p.isAlive()) p.destroyForcibly(); }
  }
}
