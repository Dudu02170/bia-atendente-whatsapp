package br.eduardoconsul.qa;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BiaWorkflowTest {
  private static JsonNode workflow;
  private static Map<String,JsonNode> nodes;
  @BeforeAll static void load() throws Exception {
    workflow=new ObjectMapper().readTree(Files.readString(Path.of("../workflows/bia-atendimento.json")));
    nodes=new LinkedHashMap<>();
    for(JsonNode n:workflow.path("nodes")) nodes.put(n.path("name").asText(),n);
  }
  @Test void nomesDosNosSaoUnicos() {
    assertEquals(workflow.path("nodes").size(),nodes.size());
    assertFalse(nodes.containsKey(""));
  }
  @Test void conexoesReferenciamNosExistentes() {
    var source=workflow.path("connections").fields();
    while(source.hasNext()) {
      var edge=source.next();
      assertTrue(nodes.containsKey(edge.getKey()),"Origem desconhecida: "+edge.getKey());
      for(JsonNode outputs:edge.getValue()) for(JsonNode branch:outputs) for(JsonNode target:branch)
        assertTrue(nodes.containsKey(target.path("node").asText()),"Destino desconhecido: "+target);
    }
  }
  @Test void entradaWhatsappUsaPostNoCaminhoDocumentado() {
    JsonNode webhook=nodes.get("Webhook");
    assertNotNull(webhook);
    assertEquals("n8n-nodes-base.webhook",webhook.path("type").asText());
    assertEquals("POST",webhook.path("parameters").path("httpMethod").asText());
    assertEquals("bia-whatsapp",webhook.path("parameters").path("path").asText());
  }
  @Test void fluxoDeEntradaAlcancaEnvio() {
    Set<String> visited=new HashSet<>();Deque<String> pending=new ArrayDeque<>();pending.add("Webhook");
    while(!pending.isEmpty()) {
      String name=pending.removeFirst();
      if(!visited.add(name)) continue;
      for(JsonNode outputs:workflow.path("connections").path(name))
        for(JsonNode branch:outputs) for(JsonNode target:branch) pending.add(target.path("node").asText());
    }
    assertTrue(visited.contains("Enviar mensagem"),"Webhook sem caminho até o envio");
    assertTrue(visited.contains("Salvar resposta Bot"),"Resposta não alcança persistência");
  }
  @Test void todoEnvioPassaPeloValidadorFinal() {
    List<String> incoming=new ArrayList<>();
    workflow.path("connections").fields().forEachRemaining(edge->{
      for(JsonNode outputs:edge.getValue()) for(JsonNode branch:outputs) for(JsonNode target:branch)
        if("Enviar mensagem".equals(target.path("node").asText())) incoming.add(edge.getKey());
    });
    assertEquals(List.of("Validador final"),incoming);
  }
}
