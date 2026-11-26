# Fluxo do n8n

`bia-atendimento.json` é o fluxo completo da Bia, com 22 nós.

## Como importar

1. Abra o n8n em http://localhost:5678.
2. Vá em **Workflows → Import from File** e escolha `bia-atendimento.json`.
3. Crie uma credencial **Postgres** apontando para o banco do `docker-compose` (host `postgres`) e selecione-a nos nós de banco.
4. A chave da Anthropic e a da Evolution API são lidas do `.env` pelas variáveis `ANTHROPIC_API_KEY`, `EVOLUTION_API_KEY` e `EVOLUTION_INSTANCE_NAME`. Nada fica escrito no fluxo.
5. Troque os produtos de exemplo (`PROD-A`, `PROD-B`, `PROD-PRO`) nos nós **Base de produtos** e **Base de links oficiais** pelos produtos do seu negócio.

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
| Revisar | Limpar resposta final → Validador final (remove Markdown, links inventados e promessas que a empresa não pode cumprir) |
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
