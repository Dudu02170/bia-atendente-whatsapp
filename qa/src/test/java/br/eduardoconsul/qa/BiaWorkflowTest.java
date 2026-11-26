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

  @Test void somenteEnvioConfirmadoAlcancaPersistenciaDaResposta() {
    JsonNode gate=nodes.get("Envio confirmado");
    assertNotNull(gate); assertEquals("n8n-nodes-base.if",gate.path("type").asText());
    assertFalse(gate.path("disabled").asBoolean());
    JsonNode config=gate.path("parameters").path("conditions");
    assertEquals("strict",config.path("options").path("typeValidation").asText());
    assertFalse(gate.path("parameters").path("looseTypeValidation").asBoolean());
    JsonNode conditions=config.path("conditions"); assertEquals(1,conditions.size());
    JsonNode condition=conditions.get(0);
    assertEquals("={{ $json.enviado }}",condition.path("leftValue").asText());
    assertEquals("boolean",condition.path("operator").path("type").asText());
    assertEquals("true",condition.path("operator").path("operation").asText());
    assertTrue(condition.path("operator").path("singleValue").asBoolean());
    assertSingleMainTarget(workflow.path("connections").path("Enviar mensagem").path("main").path(0),"Envio confirmado","Resultado do envio");
    JsonNode outputs=workflow.path("connections").path("Envio confirmado").path("main");
    assertEquals(2,outputs.size());
    assertSingleMainTarget(outputs.path(0),"Salvar resposta Bot","Envio aprovado");
    assertEquals(0,outputs.path(1).size(),"Falha não deve persistir resposta nem histórico de envio");
  }

  @Test void persistenciaNaoTemCaminhoQueContorneConfirmacao() {
    Set<String> visited=new HashSet<>(); Deque<String> pending=new ArrayDeque<>(); pending.add("Webhook");
    while(!pending.isEmpty()) {
      String name=pending.removeFirst();
      if(name.equals("Envio confirmado")||!visited.add(name)) continue;
      for(JsonNode outputs:workflow.path("connections").path(name))
        for(JsonNode branch:outputs) for(JsonNode target:branch) pending.add(target.path("node").asText());
    }
    assertFalse(visited.contains("Salvar resposta Bot"));
    assertFalse(visited.contains("Salvar mensagem"));
    assertFalse(nodes.containsKey("Salvar mensagem"),"Não mantém um segundo escritor que possa duplicar o histórico");
    assertEquals(0,workflow.path("connections").path("Salvar resposta Bot").path("main").path(0).size(),"Persistência atômica encerra o caminho de sucesso");
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

  @Test void intencoesUsamSaidasCorretasEFallbackConsultivo() {
    JsonNode router=nodes.get("Switch");
    assertNotNull(router,"Switch de intenções ausente");
    assertEquals("n8n-nodes-base.switch",router.path("type").asText());
    JsonNode rules=router.path("parameters").path("rules").path("values");
    String[][] routes={
      {"catalogo_produtos","Resposta catálogo produtos"},
      {"detalhe_segmento","Resposta detalhe segmento"},
      {"lead_facebook_inicial","Resposta lead Facebook"}
    };
    assertEquals(routes.length,rules.size(),"Quantidade de regras de intenção");
    JsonNode outputs=workflow.path("connections").path("Switch").path("main");
    assertEquals(routes.length+1,outputs.size(),"Regras e saída extra de fallback");
    for(int i=0;i<routes.length;i++) {
      JsonNode conditions=rules.get(i).path("conditions").path("conditions");
      assertEquals(1,conditions.size(),"Condição da rota "+routes[i][0]);
      JsonNode condition=conditions.get(0);
      assertEquals("={{ $json.intencao }}",condition.path("leftValue").asText());
      assertEquals(routes[i][0],condition.path("rightValue").asText(),"Intenção na saída "+i);
      assertEquals("string",condition.path("operator").path("type").asText());
      assertEquals("equals",condition.path("operator").path("operation").asText());
      assertSingleMainTarget(outputs.path(i),routes[i][1],"Switch saída "+i);
    }
    assertEquals("extra",router.path("parameters").path("options").path("fallbackOutput").asText());
    assertSingleMainTarget(outputs.path(routes.length),"Chamar IA","Fallback consultivo");
  }

  @Test void gateExigeBotAtivoEUltimaMensagemESemSaidaNaRecusa() {
    JsonNode gate=nodes.get("If");
    assertNotNull(gate,"Gate de atendimento ausente");
    assertEquals("n8n-nodes-base.if",gate.path("type").asText());
    JsonNode config=gate.path("parameters").path("conditions");
    assertEquals("and",config.path("combinator").asText(),"As duas condições devem ser exigidas");
    JsonNode conditions=config.path("conditions");
    assertEquals(2,conditions.size(),"Bot ativo e última mensagem");
    Set<String> expressions=new HashSet<>();
    for(JsonNode condition:conditions) {
      expressions.add(condition.path("leftValue").asText());
      assertEquals("boolean",condition.path("operator").path("type").asText());
      assertEquals("true",condition.path("operator").path("operation").asText());
      assertTrue(condition.path("operator").path("singleValue").asBoolean());
    }
    assertEquals(Set.of(
      "={{ $('Criar ou Atualizar Lead').first().json.bot_ativo }}",
      "={{ $json.eh_ultima_mensagem }}"
    ),expressions);
    JsonNode outputs=workflow.path("connections").path("If").path("main");
    assertTrue(outputs.isArray(),"Saídas do gate devem ser um array");
    assertSingleMainTarget(outputs.path(0),"Buscar últimas mensagens","Gate aprovado");
    for(int i=1;i<outputs.size();i++)
      assertEquals(0,outputs.get(i).size(),"Gate recusado não deve prosseguir: saída "+i);
  }

  @Test void nenhumCaminhoDeEnvioContornaFiltrosObrigatorios() {
    assertTrue(reachesSendWithout(null),"Pré-condição: envio deve ser alcançável");
    for(String required:List.of("Filtrar e Extrair Dados","If","Validador final")) {
      JsonNode node=nodes.get(required);
      assertNotNull(node,"Controle obrigatório ausente: "+required);
      assertFalse(node.path("disabled").asBoolean(false),"Controle desativado: "+required);
      assertFalse(reachesSendWithout(required),"Caminho até envio contorna: "+required);
    }
  }

  // Traversal checks only exported graph edges; it does not execute n8n conditions.
  private static boolean reachesSendWithout(String blocked) {
    Set<String> visited=new HashSet<>();
    Deque<String> pending=new ArrayDeque<>();
    pending.add("Webhook");
    while(!pending.isEmpty()) {
      String name=pending.removeFirst();
      if(name.equals(blocked)||!visited.add(name)) continue;
      if(name.equals("Enviar mensagem")) return true;
      for(JsonNode outputs:workflow.path("connections").path(name))
        for(JsonNode branch:outputs) for(JsonNode target:branch)
          pending.add(target.path("node").asText());
    }
    return false;
  }

  private static void assertSingleMainTarget(JsonNode branch,String expected,String context) {
    assertTrue(branch.isArray(),context+": saída ausente");
    assertEquals(1,branch.size(),context+": quantidade de destinos");
    JsonNode target=branch.get(0);
    assertEquals(expected,target.path("node").asText(),context+": destino");
    assertEquals("main",target.path("type").asText(),context+": tipo de conexão");
    assertEquals(0,target.path("index").asInt(-1),context+": índice da entrada");
  }

  @Test void referenciasLiteraisNosParametrosApontamParaNosExistentes() {
    // Static inspection only: literal $('Node') / $("Node") references.
    // Dynamic node names and n8n expression execution are outside this contract.
    var reference=java.util.regex.Pattern.compile("\\$\\(\\s*(['\"])([^'\"]+)\\1\\s*\\)");
    List<String> unresolved=new ArrayList<>();
    int references=0;
    for(var source:nodes.entrySet()) {
      Deque<JsonNode> pending=new ArrayDeque<>();
      pending.add(source.getValue().path("parameters"));
      while(!pending.isEmpty()) {
        JsonNode value=pending.removeFirst();
        if(value.isTextual()) {
          var matches=reference.matcher(value.asText());
          while(matches.find()) {
            references++;
            String target=matches.group(2);
            if(!nodes.containsKey(target))
              unresolved.add(source.getKey()+" -> "+target);
          }
        } else {
          // Covers nested objects and arrays, including Code, SQL and IF parameters.
          for(JsonNode child:value) pending.add(child);
        }
      }
    }
    assertTrue(references>0,"Nenhuma referência literal inspecionada; confira o formato do workflow");
    assertEquals(List.of(),unresolved,
      "Referências internas a nós ausentes (origem -> destino). Conexões válidas não garantem expressões válidas.");
  }
}
