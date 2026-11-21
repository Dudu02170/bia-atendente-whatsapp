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

Como testar: `cd qa && mvn --batch-mode --no-transfer-progress test`, com Java 17, Maven, Node 22 e Docker. O total vigente e o escopo estão em qa/README.md, sem skips previstos. A evidência final do GitHub Actions fica no PR da correção; não se considera validado antes de conferir o resultado.

## Continuidade e limites
A correção parte do PR #1 (`qa/test-automation`), ainda aberto; revisar a suíte primeiro e depois retargetar a correção para main. Não duplica os commits de testes.
O SQL inicializa banco novo e não é uma migração para instalações existentes. Nenhum volume ou banco real é alterado. Os testes PostgreSQL executam SQL real em containers descartáveis. Os contratos do workflow e o adaptador do filtro não executam o runtime/nós do n8n, nem integram WhatsApp, Evolution ou IA.
Próxima pendência funcional: inspecionar a prevenção de processamento duplicado no workflow e só então definir uma mudança pequena com regressão isolada. A unicidade de `message_id` no banco não comprova idempotência do fluxo inteiro.

## QA-BIA-002 — Tipos inesperados causam exceção no filtro de entrada
Reconfirmado na versão f39a2e8 com o código exportado e dados fictícios: remoteJid=123 lança TypeError em includes; conversation=123 lança TypeError em trim; messageTimestamp=invalido lança RangeError em toISOString. A validação antiga de domínio por includes também aceita um sufixo de outro domínio e pode produzir um número a partir de JID textual inválido.

Correção proposta: verificar estruturas e tipos antes dos métodos, exigir JID numérico com domínio exato e validar o Date antes de gerar ISO. Retornar array vazio em entrada malformada; preservar os quatro formatos de texto, defaults e rejeições existentes. Timestamp numérico em texto continua aceito, normalizado para número. Não altera os outros nós, conexões ou parâmetros do workflow.

Critérios Java: 23 entradas malformadas devem retornar zero itens sem erro de execução; o timestamp textual deve manter instante e mensagem esperados; os 14 cenários anteriores devem continuar ativos. A suíte completa tem 49 casos: 38 de filtro isolado, 9 estruturais e 2 PostgreSQL reais em containers descartáveis.

Evidência local: 23/23 descartadas após correção, contra 16 casos que falhavam ou eram aceitos indevidamente antes; quatro formatos válidos e timestamp textual preservados. Os resultados Java/SQL finais ficam no PR #2, cuja branch já contém a correção de QA-BIA-001.

Limites: executar o código do nó em processo Node chamado pelo JUnit não executa o runtime/nós/conexões n8n, webhook HTTP, Evolution, IA ou WhatsApp. Não valida existência do número nem idempotência do workflow. Eventos sem messageId mantêm o comportamento anterior; antes de implementar prevenção de duplicidade, inspecionar como o identificador é gravado e usado nos nós de SQL/roteamento, com regressão e serviços isolados. Nenhum dado real é modificado.

## QA-BIA-003 — Papel incompatível bloqueia a verificação da última mensagem
Inspeção em 21/11/2026, versão 89ef5a5: Salvar mensagem do cliente insere role=user; Verificar última mensagem filtra role=cliente. Assim, mesmo a última entrada gravada não é encontrada e eh_ultima_mensagem fica falso. Correção: selecionar role=user, sem mudar os gates ou as conexões.

Aceitação: a última entrada do telefone deve produzir true, ignorando mensagens posteriores assistant e de outro telefone; ID antigo/desconhecido e telefone sem histórico devem produzir false; fallback textual sem ID mantém o comportamento anterior. Preservar os campos usados pelos próximos nós e não modificar o histórico. Os sete cenários Java executam as consultas reais exportadas em PostgreSQL 16 descartável, com adaptador mínimo de expressões $json. Total vigente: 56 testes; evidência final no PR #2/Actions, sem alegar execução do n8n.

Entregue nesta etapa: correção da consulta e regressão do agrupamento por última entrada. Próxima pendência: impedir que eventos duplicados prossigam após o INSERT, hoje seguido por SELECT incondicional. A constraint unique não garante idempotência do fluxo; definir recuperação de falhas antes de implementar esse controle. Também permanecem fora do escopo validação de integrações externas e demais consultas de saída. Como testar: cd qa && mvn --batch-mode --no-transfer-progress test, com Java 17, Node 22 e Docker.
