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

class BiaResponseLimitTest {
  private static final ObjectMapper JSON=new ObjectMapper();
  private static final String SITE="www.suaempresa.com.br", URL="https://www.suaempresa.com.br/item_a_b?origem=qa#guia";
  private static final Map<String,String> code=new HashMap<>();

  @BeforeAll static void load() throws Exception {
    for(JsonNode node:JSON.readTree(Path.of("../workflows/bia-atendimento.json").toFile()).path("nodes"))
      if(node.path("parameters").has("jsCode")) code.put(node.path("name").asText(),node.path("parameters").path("jsCode").asText());
    for(String name:List.of("Chamar IA","Limpar resposta final","Validador final","Enviar mensagem")) assertNotNull(code.get(name));
  }

  @ParameterizedTest(name="limite: {0}, tipo={1}, máximo={2}")
  @CsvSource({"Chamar IA,normal,650","Limpar resposta final,normal,650","Validador final,normal,750",
    "Limpar resposta final,catalogo_produtos,1200","Validador final,catalogo_produtos,1200"})
  void respeitaLimiteSemEnviarUrlOuUnicodeParcial(String name,String type,int limit) throws Exception {
    String huge="https://www.suaempresa.com.br/item?src="+"b".repeat(limit+20);
    assertEquals(SITE,limited(name,type,huge,limit));
    assertEquals("Veja "+SITE,limited(name,type,"Veja "+huge,limit));
    String prefix="a".repeat(limit-10);
    assertEquals(prefix,limited(name,type,prefix+" "+URL,limit),"Corte no interior da URL deve recuar antes dela");
    String boundary="a".repeat(limit-1);
    assertEquals(boundary,limited(name,type,boundary+" "+URL,limit),"URL iniciando no limite fica inteira fora do trecho");

    String before="a".repeat(300)+" ";
    String dotted="https://www.suaempresa.com.br/v1.2/top_a_b?x=qa#guia";
    String complete=limited(name,type,before+dotted+" "+"c".repeat(limit),limit);
    assertTrue(complete.contains(dotted),"Pontuação interna de URL completa não é fim de frase");
    assertTrue(complete.startsWith(before+dotted));

    String exactBase="https://www.suaempresa.com.br/item?src=";
    String exact=exactBase+"b".repeat(limit-exactBase.length());
    assertEquals(exact,limited(name,type,exact,limit),"URL que ocupa exatamente o limite continua inteira");
    String atEnd=exactBase+"b".repeat(limit-before.length()-exactBase.length());
    assertEquals(before+atEnd,limited(name,type,before+atEnd+" texto excedente",limit),"Não recua quando a URL termina exatamente no corte");

    String unicodePrefix="a".repeat(limit-1);
    assertEquals(unicodePrefix,limited(name,type,unicodePrefix+"\uD83D\uDE00cauda",limit),"Não deixa metade do emoji");
    assertEquals("Resposta QA.",limited(name,type,"Resposta QA.",limit));
  }

  @Test void urlMuitoLongaSegueComoSiteAteRemetenteSimulado() throws Exception {
    String text="Veja https://www.suaempresa.com.br/item?src="+"b".repeat(1500);
    Map<String,Object> input=input("normal",text);
    for(String name:List.of("Chamar IA","Limpar resposta final","Validador final")) {
      JsonNode out=run(name,input,text); assertFalse(out.has("error")); assertEquals(1,out.path("result").size());
      assertEquals(name.equals("Chamar IA")?1:0,out.path("calls").size());
      input=JSON.convertValue(out.path("result").get(0).path("json"),new TypeReference<Map<String,Object>>() {});
      assertEquals("Veja "+SITE,input.get("textoResposta")); assertEquals(7,input.get("mensagem_origem_id"));
      assertEquals("qa-limit",input.get("message_id_atual"));
    }
    // Authorization is fictitious; exported Code only, with HTTP stub and no n8n runtime.
    JsonNode sent=run("Enviar mensagem",Map.of("autorizado",true,"dados_envio",input),text);
    assertFalse(sent.has("error")); assertEquals(1,sent.path("calls").size());
    assertEquals("Veja "+SITE,sent.path("calls").get(0).path("body").path("text").asText());
    assertEquals("550000000001",sent.path("calls").get(0).path("body").path("number").asText());
    assertTrue(sent.path("result").get(0).path("json").path("enviado").asBoolean());
  }

  private static String limited(String name,String type,String text,int limit) throws Exception {
    JsonNode out=run(name,input(type,text),text); assertFalse(out.has("error")); assertEquals(1,out.path("result").size());
    assertEquals(name.equals("Chamar IA")?1:0,out.path("calls").size());
    JsonNode row=out.path("result").get(0).path("json"); String result=row.path("textoResposta").asText();
    assertFalse(result.isBlank()); assertTrue(result.length()<=limit); assertFalse(result.contains("\uE000LINK"));
    assertEquals("550000000001",row.path("numero").asText()); assertEquals(7,row.path("mensagem_origem_id").asInt());
    assertEquals("qa-limit",row.path("message_id_atual").asText()); return result;
  }
  private static Map<String,Object> input(String type,String text) {
    return new HashMap<>(Map.of("numero","550000000001","intencao","duvida","mensagem","Entrada QA","tipoResposta",type,
      "textoResposta",text,"siteOficial",SITE,"message_id_atual","qa-limit","mensagem_origem_id",7));
  }
  private static JsonNode run(String name,Map<String,?> input,String text) throws Exception {
    var response=name.equals("Chamar IA")?Map.of("content",List.of(Map.of("text",text))):Map.of("key",Map.of("id","qa-sent"));
    Process p=new ProcessBuilder("node","support/send-message.cjs").redirectErrorStream(true).start();
    try {
      try(var stdin=p.getOutputStream()) { stdin.write(JSON.writeValueAsBytes(Map.of("code",code.get(name),"input",input,"http",Map.of("response",response)))); }
      assertTrue(p.waitFor(10,TimeUnit.SECONDS)); String output=new String(p.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
      assertEquals(0,p.exitValue(),output);return JSON.readTree(output);
    } finally { if(p.isAlive()) p.destroyForcibly(); }
  }
}
