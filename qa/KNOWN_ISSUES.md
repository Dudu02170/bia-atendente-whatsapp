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

Aceitação: a última entrada do telefone deve produzir true, ignorando mensagens posteriores assistant e de outro telefone; ID antigo/desconhecido e telefone sem histórico devem produzir false; fallback textual sem ID mantém o comportamento anterior. Preservar os campos usados pelos próximos nós e não modificar o histórico. Os sete cenários Java executam as consultas reais exportadas em PostgreSQL 16 descartável, com adaptador mínimo de expressões $json. Total após essa etapa: 56 testes; evidência final no PR #2/Actions, sem alegar execução do n8n.

Entregue nesta etapa: correção da consulta e regressão do agrupamento por última entrada. Próxima pendência: impedir que eventos duplicados prossigam após o INSERT, hoje seguido por SELECT incondicional. A constraint unique não garante idempotência do fluxo; definir recuperação de falhas antes de implementar esse controle. Também permanecem fora do escopo validação de integrações externas e demais consultas de saída. Como testar: cd qa && mvn --batch-mode --no-transfer-progress test, com Java 17, Node 22 e Docker.

## QA-BIA-004 — ID/tipo com apóstrofo geram SQL inválido
Reconfirmado em d7eace4 com renderização isolada da consulta real: messageId=qa'id gera 'qa'id'::text; tipoMensagem=tipo'QA gera 'tipo'QA'::text. Os dois campos são aceitos como texto pelo filtro. A falha está em Criar ou Atualizar Lead e Salvar mensagem do cliente, incluindo as colunas SELECT de saída.

Entregue: escape dos apóstrofos desses dois campos, seguindo a convenção já usada para nome/mensagem, sem apagar ou normalizar os valores. Critérios: persistir/retornar ID e tipo exatamente iguais, com apóstrofos simples/repetidos; preservar nome, texto, outros telefones, unicidade do ID e NULL para ID vazio. Quatro cenários Java adicionais executam as consultas reais em PostgreSQL descartável. Total após essa etapa: 60 testes (38 de filtro, 9 estruturais e 13 PostgreSQL). Evidência final no PR #2/Actions. Não se alega execução de n8n nem auditoria das outras consultas.

Continuidade: esta correção remove um bloqueio da gravação necessária ao controle por message_id; a deduplicação ponta a ponta continua pendente. Antes de descartar eventos repetidos no roteamento, definir como retomar uma execução que gravou a entrada mas falhou antes do envio. Os serviços externos e demais consultas de saída continuam fora da validação desta etapa. Como testar: cd qa && mvn --batch-mode --no-transfer-progress test, com Java 17, Node 22 e Docker.

## QA-BIA-005 — Tentativa malsucedida era encaminhada como resposta enviada
Reconfirmado em e5837fb: o código real de Enviar mensagem retorna enviado=false ao capturar exceção HTTP, enquanto a conexão aponta diretamente para Salvar resposta Bot, que grava role=assistant sem checar o sinal. O caso local de HTTP 503 simulado reproduz esse retorno false; a inspeção do grafo confirma o caminho indevido. Não houve envio externo.

Entregue: gate Envio confirmado (IF booleano estrito) entre envio e persistência. Só true alcança Salvar resposta Bot e o histórico final; false termina sem nova tentativa ou gravação de resposta. Mantém código do remetente, SQL, gates anteriores, dados de entrada e caminho de sucesso subsequente. O erro continua disponível na saída do remetente da execução; não há alerta externo ou confirmação de entrega/leitura.

Aceitação: manter os 60 testes anteriores; acrescentar seis testes Java do remetente exportado com HTTP simulado (sucesso/503/timeout/sem texto/sem número/texto vazio) e dois contratos do grafo (condição estrita, ramos e ausência de bypass). Total após essa etapa: 68 testes: 38 filtro, 6 remetente isolado, 11 estruturais e 13 PostgreSQL real descartável. Os testes do remetente não enviam HTTP; contratos não executam n8n. Como testar: cd qa && mvn --batch-mode --no-transfer-progress test, com Java 17, Node 22 e Docker. Evidência final no PR #2/Actions.

A pendência de mapeamento/escape do nó final foi tratada em QA-BIA-006. A execução completa do runtime e a recuperação de falhas continuam pendentes; INSERT com ON CONFLICT e SELECT incondicional ainda não garantem idempotência.

## QA-BIA-006 — Campos ausentes e escape incorreto no histórico final
Reconfirmado em 23151d2: Salvar resposta Bot usa RETURNING * em mensagens_whatsapp (telefone/mensagem); Salvar mensagem espera numero/textoResposta. Com uma entrada fictícia no formato real do retorno, a renderização produz telefone/resposta undefined. Também faltava escape de apóstrofos nos textos recebidos/enviados.

Entregue: a consulta final consome telefone/mensagem, mantém a mensagem recebida pela referência ao filtro, escapa os textos e usa um único INSERT com duas linhas. lead_id continua buscado em leads pelo telefone, sem confundir o ID de mensagens_whatsapp ou leads_whatsapp com a FK. Não cria lead real ou migração; ausência mantém NULL, como permitido antes.

Aceitação: usar a linha JDBC real de Salvar resposta Bot como entrada, sem fabricar numero/textoResposta; persistir exatamente duas direções/conteúdos com telefone correto, incluindo apóstrofos/quebras; preservar outro histórico e associação correta de lead presente/ausente; rejeição da segunda linha não pode deixar só a primeira. Quatro cenários Java/PostgreSQL real descartável, incluindo CHECK de falha apenas no teste. Total vigente: 72 testes (11 estruturais, 38 filtro, 6 remetente com HTTP simulado e 17 PostgreSQL). Execução: cd qa && mvn --batch-mode --no-transfer-progress test (Java 17, Node 22 e Docker). Evidência final no PR #2/Actions.

Limites: o adaptador fornece referência fictícia ao filtro, sem runtime n8n/item linking. A transação é somente da instrução final, não dos nós distintos nem do envio externo: o registro assistant anterior permanece se o histórico final falhar, explicitamente testado. Não há deduplicação de execuções, retry automático ou comprovação de entrega WhatsApp. Próxima etapa: definir recuperação desse estado parcial e de um envio com resultado incerto antes de descartar reprocessamentos por message_id. Não se alega integração ponta a ponta.
