package br.eduardoconsul.qa;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.core.type.TypeReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class BiaResponseNameTest {
  private static final ObjectMapper JSON=new ObjectMapper();
  private static final String FALLBACK="Perfeito. Me conta só mais um detalhe para eu te orientar certinho.";
  private static final Map<String,String> code=new HashMap<>();

  @BeforeAll static void load() throws Exception {
    for(JsonNode node:JSON.readTree(Path.of("../workflows/bia-atendimento.json").toFile()).path("nodes"))
      if(node.path("parameters").has("jsCode")) code.put(node.path("name").asText(),node.path("parameters").path("jsCode").asText());
    for(String name:List.of("Limpar resposta final","Validador final","Enviar mensagem")) assertNotNull(code.get(name));
  }

  @ParameterizedTest(name="nome livre não executa regex: {0}")
  @ValueSource(strings={"(",")","*","[","]","?","\\",".*"})
  void metacaracteresNaoInterrompemNemApagamResposta(String name) throws Exception {
    assertResponse(Map.of("pushName",name),"Olá! Vamos conversar.","Olá! Vamos conversar.");
  }

  @ParameterizedTest(name="nome literal na saudação: {0}")
  @CsvSource(value={"A.B|Olá. Vamos conversar.","Ana+|Olá. Vamos conversar.","Ana(qa)|Olá. Vamos conversar.",
    "Ana[qa]|Olá. Vamos conversar.","D'Ávila|Olá. Vamos conversar."},delimiter='|',quoteCharacter='"')
  void removeSomenteNomeLiteralNaSaudacao(String name,String expected) throws Exception {
    assertResponse(Map.of("pushName",name),"Olá "+name+"! Vamos conversar.",expected);
  }

  @ParameterizedTest(name="não confunde nome: {0}")
  @CsvSource(value={"A.B|AxB! Vamos conversar.","Ana|Anabela! Vamos conversar.","Ana|Olá Anabela! Vamos conversar."},delimiter='|',quoteCharacter='"')
  void preservaTextoQueNaoContemONomeCompleto(String name,String text) throws Exception {
    assertResponse(Map.of("pushName",name),text,text);
  }

  @Test void nomeComAlternanciaNaoRemoveOutroNome() throws Exception {
    assertResponse(Map.of("pushName","Ana|Bia"),"Bia! Vamos conversar.","Bia! Vamos conversar.");
  }
  @Test void nomeComumMantemComportamentoAnterior() throws Exception {
    assertResponse(Map.of("pushName","Cliente QA"),"Olá Cliente! Vamos conversar.","Olá. Vamos conversar.");
  }
  @Test void respostaSomenteNomeUsaFallbackExistente() throws Exception {
    assertResponse(Map.of("pushName","Ana"),"Ana!",FALLBACK);
  }
  @Test void textoVazioAposLimpezaMantemFallback() throws Exception {
    assertResponse(Map.of("pushName","Ana"),"   ",FALLBACK);
  }
  @Test void nomeLegadoDoClienteTambemELiteral() throws Exception {
    assertResponse(Map.of("cliente",Map.of("nome","Ana(qa) Silva")),"Ana(qa)! Vamos conversar.","Vamos conversar.");
  }

  private static void assertResponse(Map<String,?> names,String text,String expected) throws Exception {
    Map<String,Object> input=new HashMap<>(Map.of("numero","550000000001","textoResposta",text,"message_id_atual","qa-name",
      "mensagem_origem_id",7,"extraQA",List.of("metadado",2)));
    input.putAll(names);
    JsonNode cleaned=run("Limpar resposta final",input);
    assertFalse(cleaned.has("error")); assertEquals(0,cleaned.path("calls").size()); assertEquals(1,cleaned.path("result").size());
    JsonNode row=cleaned.path("result").get(0).path("json");
    assertEquals(expected,row.path("textoResposta").asText()); assertFalse(row.path("textoResposta").asText().isBlank());
    assertEquals(text,row.path("textoOriginal").asText());
    for(String field:List.of("numero","message_id_atual","mensagem_origem_id","extraQA")) assertEquals(JSON.valueToTree(input.get(field)),row.path(field));
    for(var field:names.entrySet()) assertEquals(JSON.valueToTree(field.getValue()),row.path(field.getKey()));
    Map<String,Object> cleanedRow=JSON.convertValue(row,new TypeReference<Map<String,Object>>() {});
    JsonNode validated=run("Validador final",cleanedRow);
    assertFalse(validated.has("error")); assertEquals(0,validated.path("calls").size()); assertEquals(1,validated.path("result").size());
    JsonNode finalRow=validated.path("result").get(0).path("json"); assertEquals(expected,finalRow.path("textoResposta").asText());
    assertTrue(finalRow.path("validado").asBoolean());
    Map<String,Object> payload=JSON.convertValue(finalRow,new TypeReference<Map<String,Object>>() {});
    // Authorization is an explicit fixture; this does not execute SQL or n8n node linking.
    JsonNode sent=run("Enviar mensagem",Map.of("autorizado",true,"dados_envio",payload));
    assertFalse(sent.has("error")); assertEquals(1,sent.path("calls").size()); assertEquals(1,sent.path("result").size());
    assertEquals(expected,sent.path("calls").get(0).path("body").path("text").asText());
    assertEquals("550000000001",sent.path("calls").get(0).path("body").path("number").asText());
    assertEquals(7,sent.path("result").get(0).path("json").path("mensagem_origem_id").asInt());
    assertTrue(sent.path("result").get(0).path("json").path("enviado").asBoolean());
  }
  private static JsonNode run(String name,Map<String,?> input) throws Exception {
    Process p=new ProcessBuilder("node","support/send-message.cjs").redirectErrorStream(true).start();
    try {
      try(var stdin=p.getOutputStream()) { stdin.write(JSON.writeValueAsBytes(Map.of("code",code.get(name),"input",input,
        "http",Map.of("response",Map.of("key",Map.of("id","qa-sent")))))); }
      assertTrue(p.waitFor(10,TimeUnit.SECONDS));
      String output=new String(p.getInputStream().readAllBytes(),StandardCharsets.UTF_8); assertEquals(0,p.exitValue(),output);return JSON.readTree(output);
    } finally { if(p.isAlive()) p.destroyForcibly(); }
  }
}
