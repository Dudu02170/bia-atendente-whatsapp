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
| Agrupar | Wait (10 s) → Verificar última mensagem: se o cliente mandou várias mensagens seguidas, responde só uma vez, considerando todas |
| Checar humano | If: segue apenas se `bot_ativo` for verdadeiro para aquele lead |
| Contexto | Buscar últimas mensagens (8) → Montar contexto |
| Entender | Detectar intenção → Base de produtos → Base de links oficiais → Switch |
| Responder | Catálogo, detalhe de linha e lead de anúncio têm respostas prontas; o resto vai para Chamar IA (Claude) |
| Revisar | Limpar resposta final → Validador final (remove Markdown, links inventados e promessas que a empresa não pode cumprir) |
| Enviar | Enviar mensagem (Evolution API) → Salvar resposta Bot → Salvar mensagem |
