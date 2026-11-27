package br.eduardoconsul.qa;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.core.type.TypeReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class BiaResponseLinksTest {
  private static final ObjectMapper JSON=new ObjectMapper();
  private static final String OFFICIAL="www.suaempresa.com.br";
  private static String validator,sender,ai,cleaner;

  @BeforeAll static void load() throws Exception {
    for(JsonNode node:JSON.readTree(Path.of("../workflows/bia-atendimento.json").toFile()).path("nodes")) {
      if(node.path("name").asText().equals("Validador final")) validator=node.path("parameters").path("jsCode").asText();
      if(node.path("name").asText().equals("Enviar mensagem")) sender=node.path("parameters").path("jsCode").asText();
      if(node.path("name").asText().equals("Chamar IA")) ai=node.path("parameters").path("jsCode").asText();
      if(node.path("name").asText().equals("Limpar resposta final")) cleaner=node.path("parameters").path("jsCode").asText();
    }
    assertNotNull(validator); assertNotNull(sender); assertNotNull(ai); assertNotNull(cleaner);
  }

  @ParameterizedTest(name="link de resposta: {0}")
  @CsvSource(value={
    "www.suaempresa.com.br|www.suaempresa.com.br",
    "suaempresa.com.br|suaempresa.com.br",
    "https://www.suaempresa.com.br|https://www.suaempresa.com.br",
    "http://suaempresa.com.br/catalogo?origem=qa#resina|http://suaempresa.com.br/catalogo?origem=qa#resina",
    "HTTPS://WWW.SUAEMPRESA.COM.BR/produtos/resina|HTTPS://WWW.SUAEMPRESA.COM.BR/produtos/resina",
    "https://suaempresa.com.br/colecao|https://suaempresa.com.br/colecao",
    "https://suaempresa.store/catalogo|www.suaempresa.com.br",
    "www.suaempresa.store|www.suaempresa.com.br",
    "suaempresa.store|www.suaempresa.com.br",
    "https://suaempresa.com/item|www.suaempresa.com.br",
    "suaempresa.com|www.suaempresa.com.br",
    "loja.suaempresa.com.br/pedido|www.suaempresa.com.br",
    "https://example.invalid/suaempresa.com.br|www.suaempresa.com.br",
    "https://suaempresa.com.br.example.invalid/path|www.suaempresa.com.br",
    "https://suaempresa.com.br@exemplo.invalid|www.suaempresa.com.br",
    "https://exemplo.invalid/?site=suaempresa.com.br|www.suaempresa.com.br",
    "https://sub.suaempresa.com.br|www.suaempresa.com.br",
    "www.suaempresa.com.br.exemplo.invalid|www.suaempresa.com.br"
  },delimiter='|',quoteCharacter='"')
  void preservaAutoridadeOficialECorrigeLinksSemCascata(String link,String expected) throws Exception {
    assertResponse("Acesse "+link,"Acesse "+expected);
  }

  @Test void processaVariosLinksSemReprocessarSubstituicao() throws Exception {
    assertResponse("Confira https://www.suaempresa.com.br/produtos e https://suaempresa.store. Depois www.example.invalid!",
      "Confira https://www.suaempresa.com.br/produtos e "+OFFICIAL+". Depois "+OFFICIAL+"!");
  }

  @Test void preservaPontuacaoParagrafosETextoSemLinks() throws Exception {
    assertResponse("Veja (https://suaempresa.store), ou https://www.suaempresa.com.br/catalogo.\n\nQual material você pretende usar?",
      "Veja ("+OFFICIAL+"), ou https://www.suaempresa.com.br/catalogo.\n\nQual material você pretende usar?");
  }

  private static void assertResponse(String text,String expected) throws Exception {
    var input=Map.of("textoResposta",text,"numero","550000000001","message_id_atual","qa-links",
      "mensagem_origem_id",7,"pushName","Cliente D'Ávila QA","extraQA",List.of("metadado",2));
    JsonNode out=run(validator,input);
    assertFalse(out.has("error")); assertEquals(0,out.path("calls").size(),"Validador isolado não faz HTTP");
    assertEquals(1,out.path("result").size()); JsonNode row=out.path("result").get(0).path("json");
    assertEquals(expected,row.path("textoResposta").asText()); assertEquals(text,row.path("textoOriginalValidacao").asText());
    assertTrue(row.path("validado").asBoolean()); assertTrue(row.path("dominioValidado").asBoolean());
    assertEquals(OFFICIAL,row.path("siteOficial").asText()); assertEquals("validador_final",row.path("origemValidacao").asText());
    for(String field:List.of("numero","message_id_atual","mensagem_origem_id","pushName","extraQA"))
      assertEquals(JSON.valueToTree(input.get(field)),row.path(field),"Preserva "+field);
    Map<String,Object> validated=JSON.convertValue(row,new TypeReference<Map<String,Object>>() {});
    JsonNode repeated=run(validator,validated);
    assertFalse(repeated.has("error")); assertEquals(0,repeated.path("calls").size());
    assertEquals(expected,repeated.path("result").get(0).path("json").path("textoResposta").asText(),"Revalidação não corrompe domínio");
    // Authorization is a fixture here; only exported Code bodies and sender HTTP stub run.
    JsonNode sent=run(sender,Map.of("autorizado",true,"dados_envio",validated));
    assertFalse(sent.has("error")); assertEquals(1,sent.path("calls").size());
    assertEquals(expected,sent.path("calls").get(0).path("body").path("text").asText());
    assertEquals("550000000001",sent.path("calls").get(0).path("body").path("number").asText());
    assertTrue(sent.path("result").get(0).path("json").path("enviado").asBoolean());
    assertEquals(7,sent.path("result").get(0).path("json").path("mensagem_origem_id").asInt());
    // Extend every existing vector through the AI response path, retaining all direct validator assertions.
    for(boolean ongoing:List.of(false,true)) assertAiPath(input,text,expected,ongoing);

  }

  private static void assertAiPath(Map<String,?> original,String text,String expected,boolean ongoing) throws Exception {
    Map<String,Object> input=new HashMap<>(original); input.remove("textoResposta");
    input.put("intencao","duvida"); input.put("mensagem","Entrada QA"); input.put("conversaEmAndamento",ongoing);
    input.put("historico_recente",ongoing?List.of(Map.of("role","user","mensagem","Histórico fictício QA")):List.of());
    JsonNode aiOut=run(ai,input,Map.of("content",List.of(Map.of("text",text))));
    assertFalse(aiOut.has("error")); assertEquals(1,aiOut.path("calls").size()); assertEquals(1,aiOut.path("result").size());
    JsonNode request=aiOut.path("calls").get(0);
    assertEquals("https://api.anthropic.com/v1/messages",request.path("url").asText());
    assertEquals("qa-fake-anthropic-key",request.path("headers").path("x-api-key").asText());
    JsonNode row=aiOut.path("result").get(0).path("json");
    assertEquals(expected,row.path("textoResposta").asText(),"IA preserva link antes das etapas posteriores");
    assertEquals(ongoing,row.path("conversaEmAndamento").asBoolean()); assertEquals("chamar_ia",row.path("origemResposta").asText());
    for(String step:List.of(cleaner,validator)) {
      JsonNode out=run(step,JSON.convertValue(row,new TypeReference<Map<String,Object>>() {}));
      assertFalse(out.has("error")); assertEquals(0,out.path("calls").size()); assertEquals(1,out.path("result").size());
      row=out.path("result").get(0).path("json"); assertEquals(expected,row.path("textoResposta").asText());
    }
    for(String field:List.of("numero","message_id_atual","mensagem_origem_id","pushName","extraQA"))
      assertEquals(JSON.valueToTree(original.get(field)),row.path(field));
    JsonNode sent=run(sender,Map.of("autorizado",true,"dados_envio",JSON.convertValue(row,new TypeReference<Map<String,Object>>() {})));
    assertFalse(sent.has("error")); assertEquals(1,sent.path("calls").size()); assertEquals(1,sent.path("result").size());
    assertEquals(expected,sent.path("calls").get(0).path("body").path("text").asText());
    assertEquals("550000000001",sent.path("calls").get(0).path("body").path("number").asText());
    assertTrue(sent.path("result").get(0).path("json").path("enviado").asBoolean());
    assertEquals(7,sent.path("result").get(0).path("json").path("mensagem_origem_id").asInt());
  }

  private static JsonNode run(String code,Map<String,?> input) throws Exception {
    return run(code,input,Map.of("key",Map.of("id","qa-sent")));
  }
  private static JsonNode run(String code,Map<String,?> input,Map<String,?> response) throws Exception {
    Process p=new ProcessBuilder("node","support/send-message.cjs").redirectErrorStream(true).start();
    try {
      try(var stdin=p.getOutputStream()) { stdin.write(JSON.writeValueAsBytes(Map.of("code",code,"input",input,
        "http",Map.of("response",response)))); }
      assertTrue(p.waitFor(10,TimeUnit.SECONDS));
      String result=new String(p.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
      assertEquals(0,p.exitValue(),result); return JSON.readTree(result);
    } finally { if(p.isAlive()) p.destroyForcibly(); }
  }
}
