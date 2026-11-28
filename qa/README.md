# QA de back-end da BIA
Java 17, Maven 3.9+, Node.js 22+, JUnit 5, Jackson, JDBC e Testcontainers. Requer Docker em execução.
```sh
cd qa
mvn --batch-mode test
```

Onze testes de contrato estrutural verificam o workflow exportado: identidade/conexões dos nós, entrada POST, caminhos de envio, roteamento, gates e referências literais nos parâmetros.

BiaInputFilterTest executa o JavaScript real do nó "Filtrar e Extrair Dados", extraído do JSON versionado, por meio de um processo Node chamado pelo JUnit. São 38 casos com payloads fictícios: os 14 anteriores (quatro formatos de texto, evento direto/defaults e nove rejeições) mais 23 entradas malformadas e um timestamp numérico recebido como texto. Os casos novos exigem descarte sem exceção de formatos/tipos inválidos, JID sem número/domínio exato e timestamp não representável.
O adaptador fornece somente $input.first().json e console.log. A execução tem timeout e não disponibiliza clientes de HTTP, banco, IA ou WhatsApp ao código. Nenhum .env é carregado.

Dois testes de integração usam o SQL real em PostgreSQL 16 descartável: inicialização/defaults, unicidade, NOT NULL e chave estrangeira; e edição de um lead com atualização automática de `atualizado_em`, preservando `criado_em`, os demais campos e outro lead. A data antiga é fixa, sem sleeps; o teste compara o novo timestamp com o horário da mesma transação no banco. Além desses dois casos, BiaLatestMessageTest acrescenta dezoito cenários PostgreSQL das consultas reais de lead, gravação e última mensagem. BiaHistoryTest tem trinta cenários de histórico, rollback, correlação e migração; BiaSendAuthorizationTest tem quinze cenários da autorização final, transporte até a persistência e bloqueio sequencial de resposta já vinculada. BiaEntryIdentityTest acrescenta quatorze cenários de identidade interna. Total vigente previsto da suíte: 241 casos (11 contratos, 38 entradas, 30 remetente simulado, 36 links, 21 nomes, 20 tipos, 6 limites de resposta e 79 PostgreSQL).

## Correção de QA-BIA-001
`sql/01-schema.sql` define agora `public.atualizar_timestamp()` antes do gatilho. O schema deve inicializar sem intervenção do teste; as asserções originais de integridade permanecem ativas. O teste adicional verifica o comportamento do gatilho real, inclusive quando a edição tenta fornecer um timestamp antigo. Resultado remoto e evidências no PR da correção.

sql/01-schema.sql é o inicializador de banco vazio, executado somente na primeira criação do volume. Para o schema anterior existente, as migrações explícitas sql/migrations/002-resposta-origem.sql e depois sql/migrations/003-vinculos-historico-resposta.sql devem ser revisadas/aplicadas antes do workflow atualizado; os testes a executam apenas em banco descartável. Nenhum volume ou SQL de produção é executado nesta entrega.

## Limites
Os contratos Jackson são estruturais. Os casos do filtro executam uma unidade de JavaScript com um adaptador mínimo; não executam o runtime, os nós ou as conexões do n8n. Não comprovam recebimento HTTP do webhook, comportamento da IA, execução dos nós SQL pelo n8n ou entrega no WhatsApp. A integração PostgreSQL usa banco descartável, sem acessar dados reais.

Relatórios JUnit: target/surefire-reports/. CI executa em PRs.

## QA-BIA-002 — Entrada malformada interrompia o filtro
O código real do filtro agora verifica os objetos de entrada/data/key/message, os tipos de JID/texto/metadados e a representação do timestamp antes de chamar métodos de string/data. O JID deve conter somente dígitos e o domínio exato @s.whatsapp.net; não é uma validação de titularidade ou existência do número. Timestamp numérico em texto continua aceito e é normalizado para número. Campo ausente mantém os defaults existentes.

Antes da correção, a execução local do código exportado reproduziu TypeError para JID/texto numéricos e RangeError para timestamp inválido. No conjunto novo de 23 entradas, 16 falhavam ou eram aceitas indevidamente; após a correção, 23/23 foram descartadas sem exceção. Os quatro formatos válidos e o timestamp textual também passaram na verificação local. Essa execução não é runtime n8n; a suíte Java completa deve ser conferida no Actions do PR #2. Maven/Docker não foram executados no terminal local.

## QA-BIA-003 — Última mensagem recebida não liberava o gate
A consulta Verificar última mensagem procurava role=cliente, embora Salvar mensagem do cliente grave role=user. Agora busca user, preservando telefone, ordenação por ID, comparação pelo identificador e fallback textual para entradas sem ID.

BiaLatestMessageTest executa as duas consultas extraídas do workflow em PostgreSQL 16 descartável. Sete cenários: ID atual, ID antigo, ID desconhecido com mesmo texto, outro telefone, telefone sem histórico e fallback sem ID com texto atual/antigo. Uma resposta assistant inserida depois das entradas não deve interferir. Confere os campos devolvidos e que a consulta não modifica o histórico. A mensagem contém aspas para verificar o escape existente.

support/render-query.cjs avalia somente as expressões JavaScript exportadas com $json e referências a nós fictícios fornecidos pelo teste; o JDBC executa o SQL resultante. Isso testa SQL real, mas não implementa nem valida o motor de expressões, item linking, Wait, gate ou demais nós do n8n. Não comprova idempotência: o INSERT ignora ID duplicado, mas seu SELECT posterior ainda emite a entrada. Sem ID, continua a comparação textual anterior. Nenhum envio é realizado. Executar a suíte pelo comando acima; evidência final no Actions do PR #2.

## QA-BIA-004 — Metadados com apóstrofos interrompiam a gravação
O filtro aceita messageId e tipoMensagem textuais, mas duas consultas os interpolavam sem duplicar os apóstrofos. Por exemplo, qa'id gerava o literal SQL inválido 'qa'id'. Criar ou Atualizar Lead e Salvar mensagem do cliente agora aplicam a mesma convenção de escape já usada por nome/mensagem, tanto na gravação quanto nas colunas de saída. Os valores originais são preservados, sem remover caracteres ou rejeitar entradas para esconder o problema.

Quatro novos casos Java/JDBC executam as consultas reais exportadas em PostgreSQL 16 descartável: ID com apóstrofo, tipo com apóstrofo, ambos com apóstrofos repetidos e defaults vazios. Verificam retorno do lead, gravação e retorno da mensagem, nome/texto também com apóstrofos, reenvio com o mesmo ID e histórico de outros telefones. ID vazio continua convertido em NULL; a constraint mantém uma linha para ID repetido e permite múltiplas entradas sem ID. Isso não garante prevenção de respostas duplicadas, pois o SELECT posterior ainda emite a entrada.

O adaptador local confirmou o literal SQL antes/depois; Maven/Docker não são executados no terminal local. Conferir a execução PostgreSQL real no Actions do PR #2. Não é auditoria geral ou parametrização de todas as consultas: somente os metadados nos dois nós de entrada foram alterados. Não testa runtime n8n ou integrações externas.

## QA-BIA-005 — Falha de envio seguia para o histórico de resposta
Em e5837fb, Enviar mensagem captura falhas HTTP e retorna enviado=false, mas sua conexão direta alcançava Salvar resposta Bot. Agora o gate Envio confirmado exige enviado booleano true antes dos dois nós finais de persistência. O ramo false termina sem gravação; o erro permanece no item do remetente para inspeção. Não implementa retry nem confirmação de entrega/leitura externa.

BiaSendMessageTest acrescenta seis testes Java do código exportado, executado pelo adaptador support/send-message.cjs: sucesso, falha 503, timeout, resposta ausente, número ausente e texto vazio após limpeza. O adaptador fornece somente $json, $env fictício e this.helpers.httpRequest simulado; registra o pedido, mas não faz rede. Verifica retorno booleano, erro e metadados preservados, conteúdo enviado ao stub e ausência de tentativa em entradas incompletas. Não representa integração real com Evolution/WhatsApp. Dois novos contratos estruturais verificam o IF estrito, os ramos e a ausência de caminho do webhook até a persistência que contorne a confirmação. As nove asserções estruturais anteriores continuam ativas.

Verificação local: seis casos do remetente passaram no adaptador isolado; antes da correção o resultado false tinha conexão direta com Salvar resposta Bot. Resultado Java completo e SQL real devem ser conferidos no Actions do PR #2. Maven/Docker não são executados no terminal local. Nenhum dado/credencial real foi usado.

A revisão do mapeamento do último nó Salvar mensagem foi concluída em QA-BIA-006 abaixo. A validação SQL isolada não comprova execução completa do caminho de sucesso no n8n.

## QA-BIA-006 — Histórico final usava campos ausentes e textos sem escape
Salvar resposta Bot retorna uma linha mensagens_whatsapp com telefone/mensagem. Salvar mensagem usava numero/textoResposta, produzindo literais undefined; textos com apóstrofos também geravam SQL inválido. O nó final agora usa os campos reais retornados, escapa os textos e grava entrada/saída em um único INSERT de duas linhas. O lead_id continua procurado na tabela leads pelo telefone; quando ausente, mantém NULL permitido pelo schema, sem inventar ou copiar IDs de outra tabela.

Quatro cenários de BiaHistoryTest executam a consulta real Salvar resposta Bot e usam sua linha JDBC real como entrada da consulta final: texto simples com lead, apóstrofos/quebras com lead, histórico sem lead e rejeição da saída por CHECK criado somente no banco descartável do teste. Validam conteúdo, telefone, direção, referência de lead, preservação de outro telefone e ausência de par parcial quando a segunda linha falha. O último caso também explicita que o registro assistant do nó anterior permanece: não há transação distribuída entre nós SQL distintos.

O adaptador fornece $('Filtrar e Extrair Dados').first().json.mensagem por fixture local, sem executar n8n, item linking ou o filtro nessa sequência JDBC. Não é integração ponta a ponta. A renderização local confirmou undefined antes e campos/escape corretos depois; PostgreSQL e Java completos são validados no Actions do PR #2. Mantém os 68 testes anteriores, sem novas dependências, acesso real ou mensagens externas. Próxima pendência: recuperação após gravação/envio parcial antes de introduzir deduplicação de execução.

## QA-BIA-007 — Resposta sem histórico final após falha no segundo nó SQL
Salvar resposta Bot agora executa uma instrução SQL com CTEs para o assistant e o par entrada/saída, retornando a linha real da resposta apenas após a conclusão. O escritor separado Salvar mensagem foi removido do grafo. Os três cenários de sucesso mantêm texto simples/apóstrofos/quebras, lead presente/ausente e histórico de outro telefone. O caso de rejeição da saída continua garantindo ausência de par parcial e agora exige também ausência de assistant, com a entrada user anterior preservada; repete somente o SQL após remover a rejeição e verifica exatamente um assistant e duas linhas de histórico. Dois novos casos rejeitam entrada ou assistant e exigem rollback de toda a operação, sem apagar dados anteriores. As falhas são CHECKs criados só no banco descartável; schema/aplicação não são flexibilizados.

A consulta real é extraída do workflow, renderizada pelo adaptador mínimo existente e executada via JDBC em PostgreSQL 16. O grafo ainda exige envio confirmado antes do único escritor final e não possui o escritor duplicado. Contratos não executam runtime n8n, expressões/item linking ou o envio Evolution/WhatsApp. Nenhum cliente real é chamado. A atomicidade é só do banco após o envio: não resolve envio já realizado sem histórico local, resultado de commit incerto, retry do workflow ou idempotência. Evidência final e logs no PR #2/Actions; não se declara 74/74 antes de conferir o CI.

## QA-BIA-008 — Estado do bot capturado antes da espera
Verificar última mensagem relê bot_ativo de leads_whatsapp e retorna bot_ativo_atual booleano; lead ausente/NULL recusa. If exige esse campo e eh_ultima_mensagem como true estrito, sem usar a cópia antiga de Criar ou Atualizar Lead. O contrato verifica as duas condições, tipos e o caminho Wait → consulta → If.

Cinco novos casos BiaLatestMessageTest executam primeiro a consulta real de criação/atualização para capturar o estado anterior, alteram o banco isolado entre as consultas e executam a consulta real de verificação: pausa, reativação, ativo mantido, NULL e lead excluído. Verificam booleano nativo, preservação da comparação de última mensagem/metadados e snapshots completos de leads/mensagens sem efeitos colaterais, incluindo outro telefone ativo. Sem sleeps ou runtime n8n; alterações são fictícias no banco descartável. Resultado final deve ser conferido no Actions/PR #2. A leitura não cobre pausa posterior à consulta ou durante envio, nem comprova integração externa.

## QA-BIA-009 — Resposta podia ficar obsoleta durante contexto/IA
Autorizar envio atual fica entre Validador final e Enviar mensagem, relê bot ativo e última entrada pelo telefone/ID canônicos do filtro e só retorna item aprovado. O remetente exige envelope estrito, preserva os dados internos no resultado plano e não tenta HTTP sem aprovação. Os seis casos anteriores do remetente continuam com as mesmas asserções, recebendo agora o envelope obrigatório; cinco novos verificam false, string true, payload ausente, array e item plano sem autorização.

Oito casos BiaSendAuthorizationTest executam consultas reais de lead/entrada/gate inicial, simulam no banco mudanças durante geração da resposta e executam a consulta real final: atual, pausa, NULL, exclusão, ID novo com texto igual, ausência de histórico e fallback sem ID atual/antigo. A linha JDBC real alimenta o remetente real no adaptador com HTTP simulado. Conferem JSON completo com apóstrofos/quebras/backslash/metadados, destinatário canônico e snapshots sem escrita em leads/histórico. O grafo exige autorização no único caminho após validador e antes de remetente; sem emissão vazia/continuação em erro. Não é runtime n8n ou integração de envio real. A janela após a consulta e deduplicação continuam fora desta etapa. Resultado final no Actions/PR #2.

## QA-BIA-010 — Correlação de resposta confirmada com a entrada
A autorização retorna mensagem_origem_id autoritativo; os oito cenários existentes agora também verificam que um valor sugerido no payload é substituído pelo ID user real e, no sucesso, executam a persistência real com a linha retornada pelo remetente simulado. O fluxo SQL/JSON/HTTP simulado não é execução n8n ou envio externo.

Sete novos casos BiaHistoryTest: origem correta, outro telefone, role assistant, metadado ausente e ID inexistente; migração repetida preservando registros legados/IDs/metadados/sequência e permitindo nova correlação; FK rejeitando órfão e exclusão da origem preservando resposta/histórico. Total BiaHistoryTest: 13 cenários. A migração real é aplicada no PostgreSQL descartável após remover apenas a coluna nova para emular o schema anterior, sem inventar reparo histórico. A coluna antiga ausente, o índice e a FK SET NULL são verificados. As asserções anteriores de rollback/preservação continuam. Nenhum script/volume de produção é executado. Resultado final no Actions/PR #2.

## QA-BIA-011 — Reprocessamento de entrada com resposta já registrada
Autorizar envio atual recusa a última entrada quando existe uma resposta assistant do mesmo telefone com mensagem_origem_id igual ao ID dessa entrada. Usa o vínculo já persistido e o índice existente; não acrescenta migração nem infere associação de registros antigos NULL.

Os oito cenários anteriores preservam suas asserções. Os casos autorizados agora passam pela persistência real e tentam autorizar a mesma entrada novamente: zero linhas, zero chamadas no remetente simulado e snapshots intactos, inclusive no fallback sem ID. Cinco casos novos verificam mesma origem (bloqueia), outra origem, sem vínculo, outro papel e outro telefone (permitem). Os casos normais usam o escritor SQL real; inconsistências de papel/telefone são fixtures explícitas para conferir os limites do filtro. Leituras não alteram leads, mensagens ou histórico.

Como testar: cd qa && mvn --batch-mode --no-transfer-progress test. Java/PostgreSQL completos são executados no Actions do PR #2; evidência final nele. Não há runtime n8n nem chamadas Evolution/WhatsApp reais, e o HTTP é simulado. O bloqueio vale para reexecução sequencial após resposta vinculada gravada. Duas consultas antes da gravação podem autorizar simultaneamente; envio sem registro local, resultado incerto e linhas antigas sem vínculo exigem reconciliação própria. Não adiciona retry automático nem garantia exatamente uma vez.

## QA-BIA-012 — Fallback sem ID não pode assumir entrada identificada
Verificar última mensagem e Autorizar envio atual exigem que ultima.message_id seja NULL antes de comparar texto quando a entrada está sem ID externo. A identidade externa existente continua sendo comparada por ID; uma execução sem ID não pode aproveitar outra mensagem identificada de texto igual. O escritor já converte ID vazio para NULL e permanece inalterado.

Quatro casos novos: duas recusas após a espera para texto igual de entrada identificada (telefone principal e outro telefone); recusa final com zero HTTP simulado; e nova entrada realmente sem ID, de texto igual a uma anterior já respondida, ainda autorizada, persistida com seu próprio vínculo e recusada na repetição. O cenário positivo anterior de fallback mantém sua asserção true, agora com fixture realmente sem ID no banco. Não relaxa asserções para acomodar a falha. Total: 108 Java/JUnit, sendo 18 casos de última mensagem e 15 de autorização.

Execução: cd qa && mvn --batch-mode --no-transfer-progress test. Evidência final no Actions/PR #2. PostgreSQL real em container descartável; expressões adaptadas e HTTP simulado. Não executa runtime n8n ou integrações externas. Duas entradas sem ID e com texto igual ainda são ambíguas: capturar/transportar identidade interna da entrada é continuidade, assim como reserva durável/reconciliação de tentativa. Não é deduplicação concorrente nem retry seguro.

## QA-BIA-013 — Identidade interna desde a gravação da entrada
Salvar mensagem do cliente devolve entrada_id inteiro real com os seis metadados anteriores. INSERT/RETURNING e busca de evento repetido pertencem à mesma instrução: ID externo já existente só retorna a linha quando telefone, role user e texto coincidem. Entradas sem ID continuam NULL no campo externo, recebendo IDs internos distintos. Colisão incompatível retorna zero linhas; o nó não força saída, continua em erro ou faz retry automático.

Verificar última mensagem transporta a identidade e recusa ID informado inválido ou diferente da última entrada user. Autorizar envio atual lê a identidade diretamente da saída original de Salvar mensagem do cliente, evitando depender do payload gerado durante contexto/IA. Ambos mantêm os controles anteriores de identidade externa/texto e bot. Campo ausente/NULL preserva compatibilidade com entradas legadas; o caminho atualizado sempre recebe o ID do escritor.

Quatorze casos novos em BiaEntryIdentityTest: dois de retorno/metadados/repetição com e sem ID externo; quatro de substituição por texto igual (com/sem ID, durante espera ou após primeira aprovação), transportando a linha real JDBC ao remetente simulado e à persistência real; cinco de identidade inválida (outro telefone, assistant, inexistente, string e fração); três colisões incompatíveis de ID externo. As verificações não escrevem dados; snapshots conferem preservação. O contrato existente exige que o escritor esteja no caminho de envio e mantenha flags de falha fechadas. Asserções anteriores são mantidas; fixtures legadas anteriores fornecem explicitamente escritor sem campo novo.

Como testar: cd qa && mvn --batch-mode --no-transfer-progress test. PostgreSQL real descartável; adaptador fornece fixtures nomeadas das linhas reais do escritor/filtro, sem executar runtime/item linking n8n. HTTP simulado; nenhuma integração ou mensagem externa. O teste não simula concorrência entre transações: conflito concorrente invisível ao snapshot pode retornar zero linhas, interrompendo esse caminho, sem retry. Continua pendente reserva durável da tentativa/reconciliação. Identidade de entrada não garante exatamente uma vez nem resolve resultado externo incerto.

## QA-BIA-014 — Normalização de links corrompia o domínio configurado
O código real do Validador final em 0583d8a transformava www.suaempresa.com.br em www.www.suaempresa.com.br.br, inclusive ao receber URL oficial com caminho. Substituições consecutivas reprocessavam a própria substituição. O domínio continua sendo o placeholder configurado no projeto; nenhum site real foi inferido ou acessado.

A correção normaliza em uma única passagem. Preserva URLs HTTP(S), www e domínio oficial sem prefixo, com caminho/query/fragmento, quando a autoridade é exatamente suaempresa.com.br ou www.suaempresa.com.br. Endereços alternativos existentes e URLs explícitas externas viram o site configurado uma vez. Credenciais, subdomínio, sufixo externo e domínio apenas no caminho/query não qualificam como autoridade oficial. Pontuação final/parágrafos são preservados. Não usa rede, novo parser/dependência ou migração.

BiaResponseLinksTest: 18 pares entrada/saída e dois casos com vários links/pontuação. Cada caso executa o corpo real do validador duas vezes (idempotência), confere metadados e passa a saída ao corpo real do remetente com autorização fictícia e HTTP simulado. Os outros 122 testes permanecem. Execução: cd qa && mvn --batch-mode --no-transfer-progress test. Resultado integral no Actions/PR #2. Não executa n8n/item linking, SQL de autorização nesta sequência, browser/Selenium, DNS, site, IA ou Evolution/WhatsApp reais.

Limites: reconhece HTTP(S)/www e os domínios alternativos já tratados; não é sanitizador universal de URI/domínios soltos. Porta explícita/credencial não é aceita como URL oficial e usa a substituição. Limites de tamanho e demais políticas do validador continuam. Reserva durável de tentativa/reconciliação e validação n8n isolada permanecem no backlog; links corrigidos não resolvem concorrência de envio.

## QA-BIA-015 — Nome livre não deve ser interpretado como regex
Em 3cf6414, o Code real Limpar resposta final lançava erro para nomes como (, ) e *; o nome .* podia apagar a resposta inteira. A.B também removia AxB por interpretar ponto como curinga. O nome era interpolado sem escape nas expressões que removem a saudação repetida.

Agora o primeiro nome é escapado como literal nas duas expressões. Exige limite de nome antes de pontuação/espaço/fim, preservando Anabela quando o nome é Ana. Mantém a origem pushName/cliente.nome e o tratamento anterior de nome comum. O fallback textual existente é aplicado depois da remoção do nome, garantindo saída não vazia quando a resposta contém apenas o nome. Demais políticas, limites e metadados permanecem.

BiaResponseNameTest acrescenta 21 casos: oito nomes com metacaracteres sem ocorrência; cinco remoções literais; três casos de nomes parecidos/prefixos; alternância, nome comum, resposta só com nome, whitespace e origem legada cliente.nome. Cada caso passa pelos corpos reais de limpeza e validação, com preservação de metadados, e segue ao remetente com autorização fictícia e HTTP simulado. Os 21 vetores passaram localmente; Java/PostgreSQL completos no Actions/PR #2. Como testar: cd qa && mvn --batch-mode --no-transfer-progress test.

Não executa runtime/item linking n8n ou autorização SQL nessa sequência, nem IA/Evolution/WhatsApp reais. Nenhum nome/dado/credencial real ou envio externo. Continua pendente reserva durável/reconciliação de tentativa e validação n8n isolada; a correção do nome não resolve concorrência de envio.

## QA-BIA-016 — Resposta deve ser string, sem coerção de objetos
Em 6e467e1, os corpos de Limpar resposta final e Validador final convertiam objeto em [object Object]; o remetente isolado autorizado tentava enviar esse texto. Chamar IA já interrompia conteúdo não textual, mas por TypeError em replace, sem validação explícita. Não se afirma que a IA malformada atravessava o fluxo inteiro.

Agora limpeza/validador/remetente retornam zero itens para textoResposta não string, mantendo a recusa anterior de campo ausente/string vazia. Chamar IA verifica o tipo do campo text consumido e lança Resposta da IA sem texto válido quando incompatível. Não converte objeto, array, número ou booleano em resposta. Strings, inclusive string numérica 0, continuam com a limpeza/fallback e demais regras existentes. Sem retry ou resposta artificial para substituir erro da IA.

BiaResponseTypeTest: dez tipos inválidos nos três nós posteriores; ausência/string vazia; cinco tipos inválidos de text retornado pela IA; quatro strings válidas percorrendo IA/limpeza/validador/remetente. Vinte casos Java novos, mantendo os 163 anteriores. O adaptador existente ganhou somente ANTHROPIC_API_KEY fictícia fixa para inspecionar a requisição simulada; não lê .env nem usa rede. Tipo inválido na IA gera só uma chamada IA simulada, sem resultado/remetente. Dados válidos preservam destinatário/origem/metadados. Vetores locais aprovados; resultado Java/PostgreSQL completo no Actions/PR #2.

Execução: cd qa && mvn --batch-mode --no-transfer-progress test. Chamadas IA/Evolution e autorização são fixtures explícitas; não executa runtime/item linking n8n, autorização SQL nessa sequência ou integrações reais. Próximas pendências: reserva durável/reconciliação e runtime isolado; inspecionar também a normalização antecipada de links em Chamar IA, distinta do validador final já corrigido.

## QA-BIA-017 — Links preservados desde a resposta da IA
A pendência registrada em QA-BIA-016 foi reconfirmada em c9d5070: Chamar IA convertia Acesse https://www.suaempresa.com.br/catalogo?origem=qa#resina em Acesse www.suaempresa.com.br. Limpeza/validador/remetente não conseguiam recuperar caminho/query/fragmento já perdidos. A correção QA-BIA-014 do validador final, isoladamente, não cobria esse ponto anterior.

Chamar IA agora usa a normalização em uma passagem já adotada no validador: preserva autoridade oficial exata e endereço restante; substitui alternativas/URLs explícitas externas uma vez, sem reprocessar a substituição. Mantém site configurado, prompts, modelo, erros, limpeza, contexto e limites anteriores. Somente essa seção do Code foi alterada; não é um sanitizador universal de URI nem valida site real.

Os 20 testes BiaResponseLinksTest mantêm todas as asserções diretas anteriores e acrescentam, em cada caso, a sequência Chamar IA → Limpar resposta final → Validador final → Enviar mensagem, com e sem histórico. São 40 verificações adicionais de sequência dentro dos mesmos 20 casos JUnit; o total continua 183. Conferem link em cada etapa, metadados, contexto e corpo enviado. Requisições IA/Evolution são simuladas; autorização é fixture. Os 40 vetores passaram localmente, sem rede. Java/PostgreSQL completos no Actions/PR #2. Executar cd qa && mvn --batch-mode --no-transfer-progress test.

Não executa runtime/item linking/conexões n8n ou autorização SQL nessa sequência, nem IA, WhatsApp, DNS ou site reais. Demais políticas de texto/limites continuam, inclusive suas transformações independentes da regra de domínio. Reserva durável/reconciliação e validação n8n isolada continuam pendentes.

## QA-BIA-018 — Markdown e tom alteravam o conteúdo das URLs
Em e7bf1bc, a sequência real dos Code alterava resina_a_b?src=qa_a_b para resinaab?src=qaab e o caminho /top para /ótimo. A validação de autoridade preservava o domínio, mas o destino já havia mudado.

Chamar IA, Limpar resposta final, Validador final e Enviar mensagem agora protegem temporariamente as URLs reconhecidas durante Markdown/tom. Controles são removidos antes da proteção; o prefixo temporário não pode colidir com resposta/metadados. Links são restaurados antes da validação de domínio e dos limites existentes, sem marcadores novos na saída. Markdown externo a um link continua removido; conteúdo literal de caminho/query/fragmento, inclusive _, * e palavras de tom, é preservado. A proteção não autoriza links: validação posterior de domínio continua ativa.

BiaResponseLinksTest tem 36 casos: 20 anteriores mantidos, 12 novos pares de URL e quatro novos testes de Markdown externo, tom fora da URL, colisão/controle e tamanho. Os casos curtos passam diretamente pelo validador e pela sequência IA/limpeza/validador/remetente com e sem histórico. São 38 vetores locais curtos (um teste percorre quatro delimitadores) x dois contextos, mais testes de limite; não são 76 casos JUnit. Tudo passou localmente. Total 199 Java/JUnit previsto; evidência integral no Actions/PR #2. Executar cd qa && mvn --batch-mode --no-transfer-progress test.

Sem runtime/item linking n8n ou integração real: IA/HTTP/autorização são fixtures explícitas. Domínio placeholder e limites/políticas restantes continuam. URLs reconhecidas pela regra existente são protegidas; não é um parser universal de URI/Markdown. Reservas duráveis/reconciliação e validação n8n isolada seguem pendentes.

## QA-BIA-019 — Limite de tamanho sem URL ou Unicode parcial
Em 58e33c6, IA/limpeza/validador aplicavam slice no meio da URL; o ajuste pelo último ponto também podia cortar dentro de domínio/caminho/query. Uma URL oficial grande saía com 650/750 caracteres e destino incompleto. O corte também podia dividir um par UTF-16.

Os três limitadores mantêm os orçamentos existentes. Uma URL individual maior que o orçamento usa o site configurado; corte que atravessa outra URL recua ao início dela. URLs que cabem, inclusive terminando exatamente no corte, permanecem inteiras. A procura de fim de frase ignora pontuação interna de URL, e o corte não deixa metade de par UTF-16. Conteúdo curto e metadados preservados. Reconhecimento continua o mesmo, sem parser universal de URI.

BiaResponseLimitTest acrescenta cinco contratos parametrizados (IA 650, limpeza 650/1200, validador 750/1200), cada um com nove verificações de tamanho/destino/Unicode/texto curto, e uma sequência de URL grande até o remetente simulado. São seis novos casos JUnit, não 45 testes. Total 205 previsto; vetores locais aprovados. O teste anterior de proteção mantém suas asserções e usa uma URL curta completa seguida de texto grande, em vez de exigir o prefixo de uma URL truncada. Executar cd qa && mvn --batch-mode --no-transfer-progress test; evidência integral no Actions/PR #2.

IA/HTTP/autorização são fixtures explícitas, sem runtime/item linking n8n, serviços/site reais ou mensagens externas. Domínio placeholder, limites e políticas restantes mantidos; sem dados/credenciais reais. Reserva durável/reconciliação e validação n8n isolada continuam pendentes.

## QA-BIA-020 — HTTP resolvido não basta para confirmar envio
Em ca4b038, resposta HTTP null, objeto vazio ou corpo de erro recebia enviado=true no Code do remetente isolado. Agora exige objeto com key.id string não vazia e sem erro explícito (error, status ERROR/zero/HTTP >=400). Resposta incompatível retorna enviado=false, resultadoEnvio=indeterminado, erro e corpo recebido preservados. Mantém uma tentativa, sem retry. O gate existente exige true estrito; esta entrega não executa o gate no runtime n8n.

Critério mínimo conferido no canal WhatsApp/Baileys da Evolution 2.3.7 usada no docker-compose: textMessage retorna a mensagem preparada, com key original; status identifica estado, incluindo PENDING, sem comprovar entrega/leitura. Fontes primárias fixadas no commit cd800f2976e1e5b682fbf86a01ee4d85ae61f370:
- https://github.com/evolution-foundation/evolution-api/blob/cd800f2976e1e5b682fbf86a01ee4d85ae61f370/src/api/integrations/channel/whatsapp/whatsapp.baileys.service.ts#L2629
- https://github.com/evolution-foundation/evolution-api/blob/cd800f2976e1e5b682fbf86a01ee4d85ae61f370/src/api/integrations/channel/whatsapp/whatsapp.baileys.service.ts#L4652
- https://github.com/evolution-foundation/evolution-api/blob/cd800f2976e1e5b682fbf86a01ee4d85ae61f370/src/utils/renderStatus.ts
Não se exige conjunto completo de campos para manter compatibilidade, nem se presume contrato de outros canais/versões.

BiaSendMessageTest passa de 11 para 30 casos: 14 respostas incompatíveis e cinco status identificados, mantendo os anteriores. Conferem resultado/booleano, evidência bruta, destinatário, origem, texto e uma tentativa simulada; os 19 vetores passaram localmente. Total 224 previsto; evidência integral no Actions/PR #2. Executar cd qa && mvn --batch-mode --no-transfer-progress test.

Resultado indeterminado não autoriza retry, não prova falha definitiva nem resolve reconciliação se o envio ocorreu. Recibo com ID também não garante entrega. HTTP é simulado, sem Evolution/WhatsApp real ou dado/credencial real. Reservas duráveis/reconciliação e validação n8n isolada continuam pendentes.

## QA-BIA-021 — ID externo da resposta preservado no histórico
Em 05e85e0, Enviar mensagem já retornava response.key.id, mas Salvar resposta Bot gravava message_id=NULL. Isso descartava a identidade externa disponível para rastrear a resposta. Agora o escritor usa essa string, com escape de apóstrofos e preservação exata do valor. Campo ausente/incompatível/vazio mantém NULL por compatibilidade; nenhum ID é fabricado ou atribuído a registro antigo.

A coluna e a unicidade já existiam; não há migração. A constraint continua estrita: colisão rejeita a instrução com SQLSTATE 23505 e reverte resposta/par entrada-saída, sem sobrescrever outra linha. Isso não é retry idempotente nem deduplicação de envio externo; uma falha de persistência após envio exige inspeção/reconciliação antes de reenviar.

Seis casos novos em BiaHistoryTest (19 no total): dois IDs retornados pelo Code real do remetente com HTTP simulado, incluindo apóstrofo, persistidos pela SQL real; três colisões com user/assistant/outro telefone verificam snapshots/rollback; legado sem recibo conserva NULL. Mantêm asserções anteriores de conteúdo, origem, metadados e histórico alheio. Total 230 previsto, com 68 PostgreSQL 16/JDBC descartável. Renderização local conferiu escape/fallback; Maven/Docker indisponíveis localmente, execução integral no Actions/PR #2.

Como testar: cd qa && mvn --batch-mode --no-transfer-progress test. Referência ao filtro é fixture; não executa runtime/item linking n8n ou Evolution/WhatsApp reais. HTTP do remetente é simulado; SQL/JDBC usam banco descartável. Reserva durável/reconciliação e validação n8n isolada continuam pendentes. Nenhum dado/credencial real ou mensagem externa.


## QA-BIA-022 — Reapresentação sequencial da persistência confirmada

Antes, reapresentar ao escritor o mesmo recibo já gravado gerava SQLSTATE 23505. Agora, novas respostas com recibo também gravam atomicamente o vínculo explícito entre assistant e seu par de histórico entrada/saída em vinculos_historico_resposta. A consulta retorna a resposta existente com persistencia_reutilizada=true somente se recibo, telefone, nome, texto, origem user e os conteúdos/direções do par coincidirem. Uma gravação nova retorna false. Não chama o remetente novamente nem cria/atualiza linhas nessa reutilização.

Banco novo recebe a tabela no inicializador. Em banco existente, revisar/testar em cópia isolada a migração 003-vinculos-historico-resposta.sql após 002 e antes de importar o workflow. Não recriar volume. A migração é repetível no schema anterior conhecido e não associa registros legados por aproximação. Sem vínculo, com histórico incompleto ou payload divergente, a unicidade continua rejeitando a instrução com 23505. Excluir um registro associado remove apenas o vínculo via FK CASCADE, sem apagar os demais registros; o conjunto incompleto deixa de qualificar para reutilização. Nenhuma migração em produção.

Aceitação/regressão: dois recibos idênticos (incluindo apóstrofo) reutilizam a mesma linha e mantêm snapshot integral; seis divergências recusam sem alteração; histórico removido não é reparado por inferência; migração aplicada duas vezes preserva legado; rejeição do vínculo reverte toda a gravação. Onze casos novos, BiaHistoryTest passa a 30; total previsto 241 Java/JUnit, com 79 PostgreSQL 16/JDBC descartável. Asserções de colisão anteriores mantidas. Executar cd qa && mvn --batch-mode --no-transfer-progress test (Java 17, Node 22, Maven e Docker). Renderização local e diff conferidos; execução integral/evidência no Actions e PR #2.

Limites/backlog: reutilização sequencial do escritor, sem retry HTTP ou nova tentativa externa. Concorrência de INSERT pode continuar em 23505; não reserva envio nem garante exatamente uma vez. Resposta sem recibo/origem não ganha associação inferida. HTTP/filtro são simulados, SQL é real no banco descartável; não executa runtime/item linking/nós n8n, Evolution/WhatsApp ou IA reais. Reserva durável/reconciliação e validação n8n isolada seguem pendentes. Mesmo PR #2/fix/schema-timestamp dependente do PR #1 aberto, sem merge/force-push, nova dependência, dado/credencial real ou mensagem externa.
