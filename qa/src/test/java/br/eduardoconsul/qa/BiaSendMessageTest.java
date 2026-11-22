package br.eduardoconsul.qa;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
