# Fluxo do n8n

`bia-atendimento.json` é o fluxo completo da Bia, com 23 nós.

## Como importar

1. Abra o n8n em http://localhost:5678.
2. Vá em **Workflows → Import from File** e escolha `bia-atendimento.json`.
3. Em banco já existente, aplique a migração revisada `sql/migrations/002-resposta-origem.sql` antes de importar o workflow atualizado; valide primeiro numa cópia isolada. Banco novo recebe a coluna no inicializador `sql/01-schema.sql`. Não recrie o volume existente.
4. Crie uma credencial **Postgres** apontando para o banco do `docker-compose` (host `postgres`) e selecione-a nos nós de banco.
5. A chave da Anthropic e a da Evolution API são lidas do `.env` pelas variáveis `ANTHROPIC_API_KEY`, `EVOLUTION_API_KEY` e `EVOLUTION_INSTANCE_NAME`. Nada fica escrito no fluxo.
6. Troque os produtos de exemplo (`PROD-A`, `PROD-B`, `PROD-PRO`) nos nós **Base de produtos** e **Base de links oficiais** pelos produtos do seu negócio.

## Etapas do fluxo

| Etapa | Nós |
| --- | --- |
| Receber | Webhook → Filtrar e Extrair Dados (só mensagens recebidas, ignora as enviadas pelo próprio número) |
| Registrar | Criar ou Atualizar Lead → Salvar mensagem do cliente |
| Agrupar | Wait (10 s) → Verificar última mensagem: seleciona a última entrada do telefone com role=user; entradas antigas não passam pela comparação. Não garante idempotência de eventos repetidos |
| Checar humano | Verificar última mensagem relê bot_ativo do lead após Wait; If exige bot_ativo_atual e última mensagem como booleanos true |
| Contexto | Buscar últimas mensagens (8) → Montar contexto |
| Entender | Detectar intenção → Base de produtos → Base de links oficiais → Switch |
| Responder | Catálogo, detalhe de linha e lead de anúncio têm respostas prontas; o resto vai para Chamar IA (Claude) |
| Revisar | Limpar resposta final → Validador final (remove Markdown, links inventados e promessas que a empresa não pode cumprir) → Autorizar envio atual (relê pausa/última entrada) |
| Enviar | Enviar mensagem (Evolution API) → Envio confirmado → Salvar resposta Bot (resposta + histórico em uma instrução SQL) |

## Validação da entrada
O nó Filtrar e Extrair Dados descarta estruturas/tipos malformados antes de acessar texto, JID e timestamp. Aceita os formatos já suportados (conversation, texto estendido e legendas de imagem/vídeo), com JID numérico no domínio exato @s.whatsapp.net. O timestamp pode ser número ou texto numérico representável por Date e sai normalizado como número. Esse filtro não verifica a existência do número nem substitui a prevenção de processamento duplicado. Regressões e limites estão em ../qa/README.md.

## Verificação da última entrada
Salvar mensagem do cliente grava role=user; Verificar última mensagem usa o mesmo papel, ordenando pelo ID e ignorando respostas assistant. A decisão compara message_id quando presente e conserva o fallback por texto quando ausente. A correção não muda o gate que também exige bot ativo. Os testes Java executam as consultas reais em banco descartável, sem executar o runtime n8n nem enviar mensagens. A prevenção completa de duplicidade e recuperação após falhas ainda é pendência em ../qa/KNOWN_ISSUES.md.

## Preservação dos metadados na entrada SQL
Criar ou Atualizar Lead e Salvar mensagem do cliente escapam apóstrofos de messageId/tipoMensagem ao gerar literais SQL, como já fazem para nome e texto. IDs e tipos textuais mantêm seu conteúdo original nos retornos e no histórico. ID vazio continua NULL no banco; a unicidade existente não impede que o fluxo responda novamente ao mesmo evento. Os testes dessas consultas usam PostgreSQL descartável; não validam o runtime n8n ou outras consultas do workflow.

## Falhas de envio
Envio confirmado exige o booleano enviado=true retornado por Enviar mensagem antes de alcançar Salvar resposta Bot e Salvar mensagem. A saída false fica sem conexão: a informação de erro continua na saída do remetente para inspeção da execução, sem gravar uma resposta como enviada. Não há retry automático. O sinal é o resultado do remetente existente, não uma confirmação de entrega/leitura do WhatsApp. Os testes do remetente usam HTTP simulado e os testes do gate inspecionam o grafo; não são execução n8n ou integração Evolution.

## Persistência atômica após envio confirmado
Salvar resposta Bot grava o registro assistant em mensagens_whatsapp e as linhas entrada/saída em mensagens em uma única instrução SQL com CTEs. Usa a linha realmente inserida para telefone/resposta e a referência existente ao filtro para a entrada, com escape de apóstrofos. O lead_id continua sendo procurado em leads por telefone e pode ser NULL. Só retorna a linha da resposta se a instrução inteira concluir. O antigo escritor separado Salvar mensagem foi removido para não duplicar o histórico.

Se qualquer um dos três registros for rejeitado pelo banco, nenhum deles fica gravado por essa operação; a entrada user recebida anteriormente e os registros de outros telefones permanecem intactos. Os testes JDBC também repetem a operação SQL depois de uma falha confirmada/rollback, sem reenviar mensagem externa. Isso não implementa retry automático no workflow.

A transação cobre somente a persistência posterior ao envio. Evolution/WhatsApp e a entrada user já gravada estão fora dela. Um envio bem-sucedido seguido de erro no banco ainda pode deixar a mensagem externa sem histórico local. Resultado incerto por perda de conexão não autoriza reprocessamento automático. Deduplicação por message_id, correlação de resposta/tentativa, reconciliação e política de recuperação continuam pendentes. A unicidade do ID de entrada não impede nova resposta ao mesmo evento. Não há execução real de n8n nos testes; o adaptador fornece referência fictícia ao filtro e PostgreSQL 16 é descartável.

## Pausa do bot durante a espera
Verificar última mensagem consulta o bot_ativo atual em leads_whatsapp para o telefone de entrada, depois da espera. If usa bot_ativo_atual, em vez da cópia retornada por Criar ou Atualizar Lead antes dos 10 segundos. Exige booleanos true para estado atual e última mensagem, com tipos estritos; lead ausente ou bot_ativo NULL resulta em false. Reativação confirmada antes da consulta também é observada. A leitura não modifica leads ou histórico.

O estado é o observado pela consulta naquele momento: uma pausa posterior, durante contexto/IA/envio, ainda pode ocorrer depois dessa checagem. Não é trava sobre o atendimento nem cancela um envio já iniciado. Os testes executam as consultas reais em PostgreSQL descartável e inspecionam o gate exportado, sem executar Wait, IF ou runtime n8n, atendimento humano real ou WhatsApp. A próxima proteção desse fluxo é verificar novamente próximo ao envio; deduplicação/reconciliação continua pendente.

## Reautorização próxima ao envio
Após Validador final e antes de Enviar mensagem, Autorizar envio atual relê o lead e a última entrada role=user do telefone original do filtro. Só devolve uma linha com autorizado=true e dados_envio quando o bot está ativo e o ID ainda é o mais recente. Sem ID mantém o fallback por texto; não é deduplicação. Lead ausente/NULL/pausado, entrada substituída ou histórico ausente produz zero linhas. O nó não emite dados vazios nem continua em falha; configure sua credencial Postgres como nos demais nós SQL ao importar.

O JSON da resposta é preservado, inclusive metadados, e numero é definido pelo telefone canônico da entrada. Enviar mensagem exige autorizado booleano true e dados_envio objeto; itens planos, strings true ou payload ausente/array não tentam HTTP. O retorno do remetente continua plano para o gate de confirmação e persistência existentes. Não há envio ou gravação na consulta de autorização.

A leitura reduz a janela durante contexto/IA, mas não torna a checagem e a chamada HTTP atômicas: mudança posterior ao SELECT ou envio já iniciado ainda não é cancelado. Não bloqueia execuções duplicadas do mesmo ID, nem reconcilia resultado incerto. Testes JDBC usam PostgreSQL descartável e passam sua linha real ao código do remetente com HTTP simulado; não executam runtime/item linking n8n ou Evolution/WhatsApp reais.

## Correlação da resposta com a entrada
Autorizar envio atual também inclui mensagem_origem_id com o ID interno da última entrada user autorizada. Esse valor, obtido no banco, substitui qualquer sugestão desse campo no payload e acompanha a resposta pelo remetente até Salvar resposta Bot. A gravação associa somente entrada user do mesmo telefone; metadado ausente/inválido, ID inexistente, outro telefone ou role assistant deixam o vínculo NULL por compatibilidade. O fluxo normal transporta o ID numérico produzido pela consulta. Não usa o message_id externo da resposta nem tenta inferir vínculos históricos.

A coluna opcional mensagem_origem_id tem FK para mensagens_whatsapp(id) e índice de consulta não exclusivo. Registros antigos permanecem NULL. Excluir a entrada deixa o vínculo NULL, preservando a resposta e o histórico final. Isso permite localizar a entrada de uma resposta confirmada sem misturar conversas. O bloqueio sequencial descrito abaixo usa esse vínculo; ele não registra uma tentativa antes do HTTP nem reconcilia envio sem resultado local.

Para bancos existentes a migração é explícita, transacional e repetível no schema anterior conhecido; os testes emulam esse schema removendo somente a nova coluna de um banco descartável, mantêm registros legados e aplicam o arquivo duas vezes. Nenhuma migração foi aplicada em produção. Consultas/configurações personalizadas ou outras versões de schema exigem revisão própria antes de uso.

## Entrada com resposta já vinculada
Antes do remetente, Autorizar envio atual também exige ausência de resposta assistant do mesmo telefone vinculada à última entrada user. Se essa resposta já foi gravada, o SQL retorna zero linhas, sem forçar saída, impedindo nova autorização sequencial da entrada. Respostas sem vínculo, de outra entrada/telefone e registros user não causam esse bloqueio. Usa o índice e a coluna da migração 002, que continua necessária em bancos existentes antes de importar o workflow.

Isso não é exclusão mútua: duas autorizações simultâneas antes da persistência podem passar. Envio externo sem registro local ou de resultado incerto continua exigindo reconciliação, sem retry automático. Não infere vínculos antigos NULL nem comprova entrega/leitura WhatsApp. A regressão JDBC executa SQL real em PostgreSQL descartável; HTTP do remetente é simulado, sem runtime n8n ou integrações reais.

## Limite do fallback textual sem ID
Verificar última mensagem e Autorizar envio atual só usam a comparação textual quando a entrada está sem ID externo e a última mensagem user no banco também tem message_id NULL. Uma execução sem ID não pode assumir uma entrada identificada posterior apenas por repetir o texto. Entradas com ID continuam comparadas pela identidade externa; o escritor mantém a conversão existente de vazio para NULL.

Uma nova entrada sem ID, de texto igual a outra já respondida, continua elegível pelo próprio registro, sem ser descartada por texto. Após gravar resposta vinculada, aplica-se o bloqueio sequencial anterior. Ainda falta transportar a identidade interna desde a gravação para distinguir execuções de duas entradas ambas sem ID e de texto igual; não há garantia concorrente ou reconciliação de envio incerto. Testes SQL/JDBC são isolados, sem runtime n8n ou mensagens externas.

## Identidade interna da entrada ao longo da execução
Salvar mensagem do cliente retorna entrada_id real com os metadados. Reenvio com ID externo só reutiliza uma linha user do mesmo telefone e texto; colisão incompatível produz zero itens. Cada entrada sem ID externo recebe um registro/ID interno próprio, sem deduplicação por texto. O escritor não força saída nem continua em erro/faz retry. Conflito concorrente ainda invisível ao snapshot pode produzir zero itens, interrompendo esse caminho.

Verificar última mensagem recebe/transporta essa identidade. Autorizar envio atual relê entrada_id diretamente da saída original de Salvar mensagem do cliente e exige que ainda corresponda à última user, além dos critérios anteriores. Payload de resposta não pode trocar a identidade. Com isso uma execução antiga não assume uma nova entrada anônima de texto igual. ID informado inválido recusa; ausência/NULL conserva compatibilidade de execução legada, sem backfill. Nenhuma migração adicional.

Isso identifica a entrada, não reserva a tentativa de envio: autorizações simultâneas ainda podem passar antes da persistência. Estado durável/reconciliação de resultado externo incerto continuam pendentes. SQL real é testado em PostgreSQL descartável; referência ao escritor usa fixture nomeada da linha JDBC. Runtime/item linking/nós n8n e integrações externas não foram executados, HTTP é simulado.

## Links da resposta sem substituição em cascata
Validador final preserva links com autoridade exatamente suaempresa.com.br ou www.suaempresa.com.br, inclusive HTTP(S), caminho, query e fragmento. O domínio é o placeholder já configurado neste projeto. URLs explícitas de outra autoridade e alternativas conhecidas viram www.suaempresa.com.br em uma única passagem, sem reprocessar o endereço inserido. Pontuação final fica fora da substituição. Revalidar a resposta não duplica www nem .br.

Domínio em credencial, host com sufixo/subdomínio, caminho ou query não torna a URL oficial. Porta explícita também usa substituição. A regra reconhece HTTP(S)/www e alternativas já tratadas, não todos os esquemas ou domínios soltos. Demais políticas/limites do validador permanecem. Testes Java executam o Code exportado em adaptador isolado e transportam sua saída ao remetente com HTTP simulado; não validam site, rede ou runtime n8n.
