# QA de back-end da BIA
Java 17, Maven 3.9+, Node.js 22+, JUnit 5, Jackson, JDBC e Testcontainers. Requer Docker em execução.
```sh
cd qa
mvn --batch-mode test
```

Onze testes de contrato estrutural verificam o workflow exportado: identidade/conexões dos nós, entrada POST, caminhos de envio, roteamento, gates e referências literais nos parâmetros.

BiaInputFilterTest executa o JavaScript real do nó "Filtrar e Extrair Dados", extraído do JSON versionado, por meio de um processo Node chamado pelo JUnit. São 38 casos com payloads fictícios: os 14 anteriores (quatro formatos de texto, evento direto/defaults e nove rejeições) mais 23 entradas malformadas e um timestamp numérico recebido como texto. Os casos novos exigem descarte sem exceção de formatos/tipos inválidos, JID sem número/domínio exato e timestamp não representável.
O adaptador fornece somente $input.first().json e console.log. A execução tem timeout e não disponibiliza clientes de HTTP, banco, IA ou WhatsApp ao código. Nenhum .env é carregado.

Dois testes de integração usam o SQL real em PostgreSQL 16 descartável: inicialização/defaults, unicidade, NOT NULL e chave estrangeira; e edição de um lead com atualização automática de `atualizado_em`, preservando `criado_em`, os demais campos e outro lead. A data antiga é fixa, sem sleeps; o teste compara o novo timestamp com o horário da mesma transação no banco. Além desses dois casos, BiaLatestMessageTest acrescenta dezoito cenários PostgreSQL das consultas reais de lead, gravação e última mensagem. BiaHistoryTest tem treze cenários de histórico, rollback, correlação e migração; BiaSendAuthorizationTest tem quinze cenários da autorização final, transporte até a persistência e bloqueio sequencial de resposta já vinculada. Total vigente da suíte: 108 casos (11 contratos, 38 entradas, 11 cenários do remetente com HTTP simulado e 48 cenários PostgreSQL).

## Correção de QA-BIA-001
`sql/01-schema.sql` define agora `public.atualizar_timestamp()` antes do gatilho. O schema deve inicializar sem intervenção do teste; as asserções originais de integridade permanecem ativas. O teste adicional verifica o comportamento do gatilho real, inclusive quando a edição tenta fornecer um timestamp antigo. Resultado remoto e evidências no PR da correção.

sql/01-schema.sql é o inicializador de banco vazio, executado somente na primeira criação do volume. Para o schema anterior existente, a migração explícita sql/migrations/002-resposta-origem.sql deve ser revisada/aplicada antes do workflow atualizado; os testes a executam apenas em banco descartável. Nenhum volume ou SQL de produção é executado nesta entrega.

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
