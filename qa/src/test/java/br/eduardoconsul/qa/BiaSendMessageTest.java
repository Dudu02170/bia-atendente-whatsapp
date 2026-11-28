package br.eduardoconsul.qa;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class BiaSendMessageTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static String code;

  @BeforeAll static void loadSender() throws Exception {
    for (JsonNode node : JSON.readTree(Path.of("../workflows/bia-atendimento.json").toFile()).path("nodes"))
      if (node.path("name").asText().equals("Enviar mensagem")) code = node.path("parameters").path("jsCode").asText();
    assertNotNull(code); assertFalse(code.isBlank());
  }

  @Test void sucessoMarcaEnvioEPreservaPayloadNormalizado() throws Exception {
    var input = Map.of("numero", "550000000001", "textoResposta", "  Resposta QA  ", "mensagem", "Entrada QA", "message_id_atual", "qa-1");
    JsonNode out = run(input, Map.of("response", Map.of("key", Map.of("id", "qa-sent"))));
    assertFalse(out.has("error")); assertEquals(1, out.path("result").size());
    JsonNode row = out.path("result").get(0).path("json");
    assertTrue(row.path("enviado").isBoolean()); assertTrue(row.path("enviado").asBoolean());
    assertEquals("Resposta QA", row.path("textoResposta").asText());
    assertEquals("Entrada QA", row.path("mensagem").asText());
    assertEquals("qa-1", row.path("message_id_atual").asText());
    assertEquals("qa-sent", row.path("response").path("key").path("id").asText());
    assertRequest(out);
    assertEquals("Resposta QA", out.path("calls").get(0).path("body").path("text").asText());
  }

  @ParameterizedTest
  @CsvSource({"Serviço indisponível QA,503", "Timeout fictício QA,0"})
  void falhaRetornaFalseEInformacaoParaInspecao(String error, int status) throws Exception {
    JsonNode out = run(Map.of("numero", "550000000001", "textoResposta", "Resposta QA", "message_id_atual", "qa-error"),
      Map.of("error", error, "status", status));
    assertFalse(out.has("error")); assertEquals(1, out.path("result").size());
    JsonNode row = out.path("result").get(0).path("json");
    assertTrue(row.path("enviado").isBoolean()); assertFalse(row.path("enviado").asBoolean());
    assertEquals(error, row.path("erroMensagem").asText());
    if (status != 0) assertEquals(status, row.path("erroStatus").asInt());
    assertEquals("qa-error", row.path("message_id_atual").asText());
    assertEquals("Resposta QA", row.path("textoResposta").asText());
    assertRequest(out);
  }

  @Test void respostaAusenteNaoTentaEnviar() throws Exception {
    JsonNode out = run(Map.of("numero", "550000000001"), Map.of());
    assertEquals(0, out.path("result").size()); assertEquals(0, out.path("calls").size());
  }

  @Test void numeroAusenteFalhaAntesDoHttp() throws Exception {
    JsonNode out = run(Map.of("textoResposta", "Resposta QA"), Map.of());
    assertTrue(out.path("error").path("message").asText().contains("Número não encontrado"));
    assertEquals(0, out.path("calls").size());
  }

  @Test void textoVazioAposLimpezaFalhaAntesDoHttp() throws Exception {
    JsonNode out = run(Map.of("numero", "550000000001", "textoResposta", "   "), Map.of());
    assertEquals("Texto da resposta vazio", out.path("error").path("message").asText());
    assertEquals(0, out.path("calls").size());
  }

  @ParameterizedTest(name="recibo incompatível: {0}")
  @ValueSource(strings={"null","{}","[]","true","\"QA\"","{\"key\":{}}","{\"key\":{\"id\":\"\"}}",
    "{\"key\":{\"id\":\"   \"}}","{\"key\":{\"id\":42}}","{\"key\":[]}",
    "{\"key\":{\"id\":\"qa\"},\"status\":\"ERROR\"}","{\"key\":{\"id\":\"qa\"},\"status\":400}",
    "{\"key\":{\"id\":\"qa\"},\"error\":\"Falha QA\"}","{\"key\":{\"id\":\"qa\"},\"status\":0}"})
  void respostaResolvidaSemConfirmacaoNaoMarcaEnvioComoSucesso(String json) throws Exception {
    JsonNode response=JSON.readTree(json);
    var data=Map.of("numero","550000000001","textoResposta","  Resposta QA  ","message_id_atual","qa-receipt","mensagem_origem_id",7);
    JsonNode out=runEnvelope(Map.of("autorizado",true,"dados_envio",data),Map.of("response",response));
    assertFalse(out.has("error")); assertEquals(1,out.path("result").size()); assertRequest(out);
    JsonNode row=out.path("result").get(0).path("json");
    assertTrue(row.path("enviado").isBoolean()); assertFalse(row.path("enviado").asBoolean());
    assertEquals("indeterminado",row.path("resultadoEnvio").asText());
    assertEquals("Resposta da Evolution sem confirmação válida de envio",row.path("erroMensagem").asText());
    assertEquals(response,row.path("erroBody"),"Preserva corpo recebido para inspeção, sem presumir falha definitiva");
    assertEquals("qa-receipt",row.path("message_id_atual").asText()); assertEquals(7,row.path("mensagem_origem_id").asInt());
    assertEquals("Resposta QA",row.path("textoResposta").asText());
    assertEquals("Resposta QA",out.path("calls").get(0).path("body").path("text").asText());
  }

  @ParameterizedTest(name="recibo com ID e status: {0}")
  @ValueSource(strings={"PENDING","SERVER_ACK","DELIVERY_ACK","READ","PLAYED"})
  void reciboIdentificadoPreservaStatusSemConfundirComEntrega(String status) throws Exception {
    var response=Map.of("key",Map.of("id","qa-sent","fromMe",true,"remoteJid","550000000001@s.whatsapp.net"),"status",status);
    JsonNode out=run(Map.of("numero","550000000001","textoResposta","Resposta QA"),Map.of("response",response));
    assertFalse(out.has("error")); assertRequest(out); assertEquals(1,out.path("result").size());
    JsonNode row=out.path("result").get(0).path("json"); assertTrue(row.path("enviado").asBoolean());
    assertEquals(JSON.valueToTree(response),row.path("response"));
  }

  @ParameterizedTest(name="identidade contraditória do recibo: {0}")
  @ValueSource(strings={
    "{\"id\":\"qa-id\",\"fromMe\":false}",
    "{\"id\":\"qa-id\",\"fromMe\":\"true\"}",
    "{\"id\":\"qa-id\",\"fromMe\":null}",
    "{\"id\":\"qa-id\",\"remoteJid\":\"550000000099@s.whatsapp.net\"}",
    "{\"id\":\"qa-id\",\"remoteJid\":550000000001}",
    "{\"id\":\"qa-id\",\"remoteJid\":null}",
    "{\"id\":\"qa-id\",\"remoteJid\":\"\"}",
    "{\"id\":\"qa-id\",\"remoteJid\":\"550000000001@lid\"}",
    "{\"id\":\"qa-id\",\"remoteJid\":\"550000000001@g.us\"}"
  })
  void reciboComIdentidadeIncompativelNaoConfirmaEnvio(String keyJson) throws Exception {
    JsonNode response=JSON.createObjectNode().set("key",JSON.readTree(keyJson));
    var data=Map.of("numero","550000000001","textoResposta","  Resposta QA  ","mensagem_origem_id",7,"message_id_atual","qa-input");
    JsonNode out=runEnvelope(Map.of("autorizado",true,"dados_envio",data),Map.of("response",response));
    assertFalse(out.has("error")); assertRequest(out); assertEquals(1,out.path("result").size());
    JsonNode row=out.path("result").get(0).path("json");
    assertTrue(row.path("enviado").isBoolean()); assertFalse(row.path("enviado").asBoolean());
    assertEquals("indeterminado",row.path("resultadoEnvio").asText());
    assertEquals("Resposta da Evolution sem confirmação válida de envio",row.path("erroMensagem").asText());
    assertEquals(response,row.path("erroBody"),"Preserva o recibo contraditório para reconciliação, sem retry");
    assertEquals("550000000001",row.path("numero").asText()); assertEquals(7,row.path("mensagem_origem_id").asInt());
    assertEquals("qa-input",row.path("message_id_atual").asText()); assertEquals("Resposta QA",row.path("textoResposta").asText());
    assertFalse(row.has("response"),"Recibo incompatível não entra no campo de resposta confirmada");
  }

  @ParameterizedTest(name="metadado opcional coerente: {0}")
  @ValueSource(strings={"{\"id\":\"qa-id\",\"fromMe\":true}",
    "{\"id\":\"qa-id\",\"remoteJid\":\"550000000001@s.whatsapp.net\"}"})
  void reciboPreservaCompatibilidadeComMetadadosOpcionais(String keyJson) throws Exception {
    JsonNode response=JSON.createObjectNode().set("key",JSON.readTree(keyJson));
    JsonNode out=run(Map.of("numero","550000000001","textoResposta","Resposta QA"),Map.of("response",response));
    assertFalse(out.has("error")); assertRequest(out); assertEquals(1,out.path("result").size());
    JsonNode row=out.path("result").get(0).path("json");
    assertTrue(row.path("enviado").isBoolean()); assertTrue(row.path("enviado").asBoolean());
    assertEquals(response,row.path("response"));
  }

  private static void assertRequest(JsonNode out) {
    assertEquals(1, out.path("calls").size(), "Uma tentativa; o stub não faz rede");
    JsonNode call = out.path("calls").get(0);
    assertEquals("POST", call.path("method").asText());
    assertEquals("http://evolution:8080/message/sendText/qa-isolated", call.path("url").asText());
    assertEquals("qa-fake-key", call.path("headers").path("apikey").asText());
    assertEquals("550000000001", call.path("body").path("number").asText());
    assertTrue(call.path("json").asBoolean());
  }

  private static JsonNode run(Map<String,String> input, Map<String,?> http) throws Exception {
    return runEnvelope(Map.of("autorizado",true,"dados_envio",input),http);
  }

  @ParameterizedTest(name="autorização inválida: {0}")
  @ValueSource(strings={"false","string","missingPayload","array","flat"})
  void ausenciaDeAutorizacaoEstritaNaoTentaHttp(String scenario) throws Exception {
    var data=Map.of("numero","550000000001","textoResposta","Resposta QA");
    Map<String,?> input=switch(scenario) {
      case "false" -> Map.of("autorizado",false,"dados_envio",data);
      case "string" -> Map.of("autorizado","true","dados_envio",data);
      case "missingPayload" -> Map.of("autorizado",true);
      case "array" -> Map.of("autorizado",true,"dados_envio",java.util.List.of(data));
      default -> data;
    };
    JsonNode out=runEnvelope(input,Map.of());
    assertFalse(out.has("error")); assertEquals(0,out.path("result").size()); assertEquals(0,out.path("calls").size());
  }

  private static JsonNode runEnvelope(Map<String,?> input, Map<String,?> http) throws Exception {
    Process process = new ProcessBuilder("node", "support/send-message.cjs").redirectErrorStream(true).start();
    try {
      try (var stdin = process.getOutputStream()) { stdin.write(JSON.writeValueAsBytes(Map.of("code", code, "input", input, "http", http))); }
      assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Timeout no remetente isolado");
      String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      assertEquals(0, process.exitValue(), output);
      return JSON.readTree(output);
    } finally { if (process.isAlive()) process.destroyForcibly(); }
  }
}
