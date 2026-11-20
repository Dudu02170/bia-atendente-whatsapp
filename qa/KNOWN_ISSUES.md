# QA-BIA-001 — Inicialização bloqueada por função ausente

## Estado anterior reconfirmado
O schema cria `trigger_leads_atualizar`, mas a versão `5c3d284` não define `public.atualizar_timestamp()`. O log confirma 23/24 casos aprovados e falha na inicialização do PostgreSQL vazio; as verificações de integridade posteriores ficam bloqueadas.
Evidência: https://github.com/Dudu02170/bia-atendente-whatsapp/actions/runs/44530278157

## Correção proposta
O inicializador define a função antes do gatilho. Em cada UPDATE de `public.leads`, ela atribui o horário da transação do banco a `NEW.atualizado_em` e retorna a linha editada. Mantém o gatilho existente, os dados da edição, a criação do lead e os registros não editados.

## Critérios de aceitação
- Inicializar o SQL real em PostgreSQL 16 vazio, sem função criada artificialmente pelo teste.
- Manter os defaults e constraints originais de telefone, referência de lead e identificador de mensagem.
- Editar um lead com datas históricas fixas e verificar `atualizado_em` igual ao horário do banco na transação, mesmo se a edição fornecer uma data antiga.
- Preservar `criado_em`, cidade, status, bot ativo, outro lead e a contagem de registros.

Como testar: `cd qa && mvn --batch-mode --no-transfer-progress test`, com Java 17, Maven, Node 22 e Docker. A suíte contém 25 casos, sem skips previstos. A evidência final do GitHub Actions fica no PR da correção; não se considera validado antes de conferir o resultado.

## Continuidade e limites
A correção parte do PR #1 (`qa/test-automation`), ainda aberto; revisar a suíte primeiro e depois retargetar a correção para main. Não duplica os commits de testes.
O SQL inicializa banco novo e não é uma migração para instalações existentes. Nenhum volume ou banco real é alterado. Os testes PostgreSQL executam SQL real em containers descartáveis. Os contratos do workflow e o adaptador do filtro não executam o runtime/nós do n8n, nem integram WhatsApp, Evolution ou IA.
Próxima pendência funcional: inspecionar a prevenção de processamento duplicado no workflow e só então definir uma mudança pequena com regressão isolada. A unicidade de `message_id` no banco não comprova idempotência do fluxo inteiro.
