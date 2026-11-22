# QA de back-end da BIA
Java 17, Maven 3.9+, Node.js 22+, JUnit 5, Jackson, JDBC e Testcontainers. Requer Docker em execução.
```sh
cd qa
mvn --batch-mode test
```

Onze testes de contrato estrutural verificam o workflow exportado: identidade/conexões dos nós, entrada POST, caminhos de envio, roteamento, gates e referências literais nos parâmetros.

BiaInputFilterTest executa o JavaScript real do nó "Filtrar e Extrair Dados", extraído do JSON versionado, por meio de um processo Node chamado pelo JUnit. São 38 casos com payloads fictícios: os 14 anteriores (quatro formatos de texto, evento direto/defaults e nove rejeições) mais 23 entradas malformadas e um timestamp numérico recebido como texto. Os casos novos exigem descarte sem exceção de formatos/tipos inválidos, JID sem número/domínio exato e timestamp não representável.
O adaptador fornece somente $input.first().json e console.log. A execução tem timeout e não disponibiliza clientes de HTTP, banco, IA ou WhatsApp ao código. Nenhum .env é carregado.

Dois testes de integração usam o SQL real em PostgreSQL 16 descartável: inicialização/defaults, unicidade, NOT NULL e chave estrangeira; e edição de um lead com atualização automática de `atualizado_em`, preservando `criado_em`, os demais campos e outro lead. A data antiga é fixa, sem sleeps; o teste compara o novo timestamp com o horário da mesma transação no banco. Além desses dois casos, BiaLatestMessageTest acrescenta onze cenários PostgreSQL das consultas reais de lead, gravação e última mensagem. BiaHistoryTest acrescenta quatro cenários do histórico final. Total da suíte: 72 casos (11 contratos, 38 entradas, 6 cenários do remetente com HTTP simulado e 17 cenários PostgreSQL).

## Correção de QA-BIA-001
`sql/01-schema.sql` define agora `public.atualizar_timestamp()` antes do gatilho. O schema deve inicializar sem intervenção do teste; as asserções originais de integridade permanecem ativas. O teste adicional verifica o comportamento do gatilho real, inclusive quando a edição tenta fornecer um timestamp antigo. Resultado remoto e evidências no PR da correção.

O SQL é um inicializador de banco vazio, executado pelo container somente na primeira criação do volume. Não é uma migração para bancos existentes; esta entrega não executa SQL nem recria volumes de produção.

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
