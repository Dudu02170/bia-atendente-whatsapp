package br.eduardoconsul.qa;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.core.type.TypeReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class BiaResponseTypeTest {
  private static final ObjectMapper JSON=new ObjectMapper();
  private static final Map<String,String> code=new HashMap<>();
  private static final List<String> OUTPUT_NODES=List.of("Limpar resposta final","Validador final","Enviar mensagem");

  @BeforeAll static void load() throws Exception {
    for(JsonNode node:JSON.readTree(Path.of("../workflows/bia-atendimento.json").toFile()).path("nodes"))
      if(node.path("parameters").has("jsCode")) code.put(node.path("name").asText(),node.path("parameters").path("jsCode").asText());
    for(String name:OUTPUT_NODES) assertNotNull(code.get(name)); assertNotNull(code.get("Chamar IA"));
  }

  @ParameterizedTest(name="textoResposta não textual: {0}")
  @ValueSource(strings={"true","42","1.5","{}","[]","[\"QA\"]","{\"texto\":\"QA\"}","null","false","0"})
  void tiposInvalidosNaoSaoConvertidosNemEnviados(String json) throws Exception {
    Map<String,Object> data=base(); data.put("textoResposta",JSON.readTree(json));
    for(String name:OUTPUT_NODES) {
      JsonNode out=run(name,name.equals("Enviar mensagem")?Map.of("autorizado",true,"dados_envio",data):data,sendResponse());
      assertFalse(out.has("error"),name); assertEquals(0,out.path("result").size(),name);
      assertEquals(0,out.path("calls").size(),name+": nenhum HTTP para conteúdo inválido");
    }
  }

  @Test void ausenciaEStringVaziaMantemRecusaAnterior() throws Exception {
    for(boolean missing:List.of(true,false)) {
      Map<String,Object> data=base(); if(!missing) data.put("textoResposta","");
      for(String name:OUTPUT_NODES) {
        JsonNode out=run(name,name.equals("Enviar mensagem")?Map.of("autorizado",true,"dados_envio",data):data,sendResponse());
        assertFalse(out.has("error")); assertEquals(0,out.path("result").size()); assertEquals(0,out.path("calls").size());
      }
    }
  }

  @ParameterizedTest(name="texto de IA inválido: {0}")
  @ValueSource(strings={"true","{}","[]","42","null"})
  void iaRecusaTipoInvalidoComErroExplicitoSemEnvio(String json) throws Exception {
    var response=Map.of("content",List.of(Map.of("text",JSON.readTree(json))));
    JsonNode out=run("Chamar IA",base(),response);
    assertEquals("Resposta da IA sem texto válido",out.path("error").path("message").asText());
    assertFalse(out.has("result")); assertEquals(1,out.path("calls").size(),"Somente requisição IA simulada, sem WhatsApp");
    assertIaRequest(out);
  }

  @ParameterizedTest(name="string válida: {0}")
  @ValueSource(strings={"Resposta QA","  Resposta QA  ","**Resposta QA**","0"})
  void stringsValidasSeguemDaIaAteRemetentePreservandoMetadados(String text) throws Exception {
    JsonNode ai=run("Chamar IA",base(),Map.of("content",List.of(Map.of("text",text))));
    assertFalse(ai.has("error")); assertEquals(1,ai.path("result").size()); assertIaRequest(ai);
    String expected=text.replace("*","").trim();
    JsonNode row=ai.path("result").get(0).path("json"); assertEquals(expected,row.path("textoResposta").asText());
    for(String name:List.of("Limpar resposta final","Validador final")) {
      JsonNode out=run(name,JSON.convertValue(row,new TypeReference<Map<String,Object>>() {}),sendResponse());
      assertFalse(out.has("error")); assertEquals(0,out.path("calls").size()); assertEquals(1,out.path("result").size());
      row=out.path("result").get(0).path("json"); assertEquals(expected,row.path("textoResposta").asText());
    }
    assertEquals(7,row.path("mensagem_origem_id").asInt()); assertEquals("qa-types",row.path("message_id_atual").asText());
    assertEquals(JSON.valueToTree(List.of("metadado",2)),row.path("extraQA"));
    JsonNode sent=run("Enviar mensagem",Map.of("autorizado",true,"dados_envio",JSON.convertValue(row,new TypeReference<Map<String,Object>>() {})),sendResponse());
    assertFalse(sent.has("error")); assertEquals(1,sent.path("calls").size()); assertEquals(1,sent.path("result").size());
    assertEquals(expected,sent.path("calls").get(0).path("body").path("text").asText());
    assertEquals("550000000001",sent.path("calls").get(0).path("body").path("number").asText());
    assertTrue(sent.path("result").get(0).path("json").path("enviado").asBoolean());
  }

  private static Map<String,Object> base() {
    return new HashMap<>(Map.of("numero","550000000001","intencao","duvida","mensagem","Entrada QA","pushName","Cliente QA",
      "message_id_atual","qa-types","mensagem_origem_id",7,"extraQA",List.of("metadado",2)));
  }
  private static Map<String,?> sendResponse() { return Map.of("key",Map.of("id","qa-sent")); }
  private static void assertIaRequest(JsonNode out) {
    assertEquals(1,out.path("calls").size()); JsonNode call=out.path("calls").get(0);
    assertEquals("POST",call.path("method").asText()); assertEquals("https://api.anthropic.com/v1/messages",call.path("url").asText());
    assertEquals("qa-fake-anthropic-key",call.path("headers").path("x-api-key").asText());
  }
  private static JsonNode run(String name,Map<String,?> input,Map<String,?> response) throws Exception {
    Process p=new ProcessBuilder("node","support/send-message.cjs").redirectErrorStream(true).start();
    try {
      try(var stdin=p.getOutputStream()) { stdin.write(JSON.writeValueAsBytes(Map.of("code",code.get(name),"input",input,
        "http",Map.of("response",response)))); }
      assertTrue(p.waitFor(10,TimeUnit.SECONDS));
      String output=new String(p.getInputStream().readAllBytes(),StandardCharsets.UTF_8); assertEquals(0,p.exitValue(),output);return JSON.readTree(output);
    } finally { if(p.isAlive()) p.destroyForcibly(); }
  }
}
