package br.eduardoconsul.qa;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

class BiaInputFilterTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static String code;

  @BeforeAll static void loadExportedFilter() throws Exception {
    JsonNode workflow = JSON.readTree(Files.readString(Path.of("../workflows/bia-atendimento.json")));
    for (JsonNode node : workflow.path("nodes")) {
      if ("Filtrar e Extrair Dados".equals(node.path("name").asText())) {
        assertEquals("n8n-nodes-base.code", node.path("type").asText());
        code = node.path("parameters").path("jsCode").asText();
      }
    }
    assertNotNull(code, "Filtro de entrada ausente no workflow");
    assertFalse(code.isBlank(), "Código do filtro vazio");
  }

  @ParameterizedTest(name = "extrai texto de {0}")
  @ValueSource(strings = {"conversation", "extended", "image", "video"})
  void extraiTextoEMetadadosDoPayloadReal(String channel) throws Exception {
    ObjectNode payload = validEvent();
    ObjectNode message = (ObjectNode) payload.path("data").path("message");
    message.removeAll();
    switch (channel) {
      case "conversation" -> message.put("conversation", "  Olá QA  ");
      case "extended" -> message.putObject("extendedTextMessage").put("text", "  Olá QA  ");
      case "image" -> message.putObject("imageMessage").put("caption", "  Olá QA  ");
      case "video" -> message.putObject("videoMessage").put("caption", "  Olá QA  ");
      default -> fail("Canal não definido na fixture");
    }
    JsonNode result = runFilter(JSON.createObjectNode().set("body", payload));
    assertEquals(1, result.size());
    JsonNode out = result.get(0).path("json");
    assertAll(
      () -> assertEquals("550000000001", out.path("numero").asText()),
      () -> assertEquals("Olá QA", out.path("mensagem").asText()),
      () -> assertEquals("Pessoa QA", out.path("nome").asText()),
      () -> assertEquals("Pessoa QA", out.path("pushName").asText()),
      () -> assertEquals("qa-message-1", out.path("messageId").asText()),
      () -> assertEquals("550000000001@s.whatsapp.net", out.path("remoteJid").asText()),
      () -> assertEquals(1700000000L, out.path("timestamp").asLong()),
      () -> assertEquals("2023-11-14T22:13:20.000Z", out.path("dataHora").asText())
    );
  }

  @Test void aceitaEventoDiretoEAplicaNomeETipoPadrao() throws Exception {
    ObjectNode payload = validEvent();
    ((ObjectNode) payload.path("data")).remove("pushName");
    JsonNode result = runFilter(payload);
    assertEquals(1, result.size());
    assertEquals("Cliente", result.get(0).path("json").path("nome").asText());
    assertEquals("desconhecido", result.get(0).path("json").path("tipoMensagem").asText());
  }

  @ParameterizedTest(name = "descarta {0}")
  @MethodSource("ignoredEvents")
  void descartaEventosSemGerarItensParaOsProximosNos(String reason, ObjectNode payload) throws Exception {
    assertEquals(0, runFilter(JSON.createObjectNode().set("body", payload)).size(), reason);
  }

  static Stream<Arguments> ignoredEvents() {
    ObjectNode own = validEvent();
    ((ObjectNode) own.path("data").path("key")).put("fromMe", true);
    ObjectNode empty = validEvent();
    ((ObjectNode) empty.path("data").path("message")).put("conversation", "   ");
    ObjectNode audio = validEvent();
    ObjectNode message = (ObjectNode) audio.path("data").path("message");
    message.removeAll();
    message.putObject("audioMessage").put("url", "https://example.invalid/qa");
    ObjectNode other = validEvent(); other.put("event", "connection.update");
    ObjectNode noData = validEvent(); noData.remove("data");
    return Stream.of(
      Arguments.of("mensagem própria", own),
      Arguments.of("status/broadcast", withJid("status@broadcast")),
      Arguments.of("grupo", withJid("550000000001@g.us")),
      Arguments.of("identificador lid", withJid("qa-contact@lid")),
      Arguments.of("JID de outro domínio", withJid("qa@example.test")),
      Arguments.of("texto vazio", empty),
      Arguments.of("áudio sem texto suportado", audio),
      Arguments.of("outro evento", other),
      Arguments.of("evento sem data", noData)
    );
  }

  private static ObjectNode validEvent() {
    ObjectNode event = JSON.createObjectNode().put("event", "messages.upsert");
    ObjectNode data = event.putObject("data");
    data.putObject("key").put("fromMe", false).put("remoteJid", "550000000001@s.whatsapp.net").put("id", "qa-message-1");
    data.putObject("message").put("conversation", "  Olá QA  ");
    data.put("pushName", "Pessoa QA").put("messageTimestamp", 1700000000L);
    return event;
  }

  private static ObjectNode withJid(String jid) {
    ObjectNode event = validEvent();
    ((ObjectNode) event.path("data").path("key")).put("remoteJid", jid);
    return event;
  }

  private static JsonNode runFilter(JsonNode input) throws Exception {
    // No n8n server or external service: only the exported filter and fictitious input.
    Process process = new ProcessBuilder("node", "support/input-filter.cjs").redirectErrorStream(true).start();
    try {
      try (var stdin = process.getOutputStream()) {
        stdin.write(JSON.writeValueAsBytes(Map.of("code", code, "input", input)));
      }
      assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Processo do filtro excedeu 10 segundos");
      String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      assertEquals(0, process.exitValue(), "Falha ao executar filtro exportado: " + output);
      JsonNode result = JSON.readTree(output);
      assertTrue(result.isArray(), "Filtro deve retornar array: " + output);
      return result;
    } finally {
      if (process.isAlive()) process.destroyForcibly();
    }
  }
}
