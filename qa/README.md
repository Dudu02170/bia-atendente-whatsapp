# QA de back-end da BIA
Java 17, Maven 3.9+, Node.js 22+, JUnit 5, Jackson, JDBC e Testcontainers. Requer Docker em execução.
```sh
cd qa
mvn --batch-mode test
```

Nove testes de contrato estrutural verificam o workflow exportado: identidade/conexões dos nós, entrada POST, caminhos de envio, roteamento, gates e referências literais nos parâmetros.

BiaInputFilterTest executa o JavaScript real do nó "Filtrar e Extrair Dados", extraído do JSON versionado, por meio de um processo Node chamado pelo JUnit. São 14 casos com payloads fictícios: quatro formatos de texto, evento direto com valores padrão e nove rejeições (mensagem própria, broadcast, grupo, lid, JID inválido, texto vazio, áudio sem texto, outro evento e ausência de data).
O adaptador fornece somente $input.first().json e console.log. A execução tem timeout e não disponibiliza clientes de HTTP, banco, IA ou WhatsApp ao código. Nenhum .env é carregado.

Dois testes de integração usam o SQL real em PostgreSQL 16 descartável: inicialização/defaults, unicidade, NOT NULL e chave estrangeira; e edição de um lead com atualização automática de `atualizado_em`, preservando `criado_em`, os demais campos e outro lead. A data antiga é fixa, sem sleeps; o teste compara o novo timestamp com o horário da mesma transação no banco. Total da suíte: 25 casos (9 contratos, 14 entradas e 2 integrações PostgreSQL).

## Correção de QA-BIA-001
`sql/01-schema.sql` define agora `public.atualizar_timestamp()` antes do gatilho. O schema deve inicializar sem intervenção do teste; as asserções originais de integridade permanecem ativas. O teste adicional verifica o comportamento do gatilho real, inclusive quando a edição tenta fornecer um timestamp antigo. Resultado remoto e evidências no PR da correção.

O SQL é um inicializador de banco vazio, executado pelo container somente na primeira criação do volume. Não é uma migração para bancos existentes; esta entrega não executa SQL nem recria volumes de produção.

## Limites
Os contratos Jackson são estruturais. Os casos do filtro executam uma unidade de JavaScript com um adaptador mínimo; não executam o runtime, os nós ou as conexões do n8n. Não comprovam recebimento HTTP do webhook, comportamento da IA, execução das consultas do workflow ou entrega no WhatsApp. A integração PostgreSQL usa banco descartável, sem acessar dados reais.

Relatórios JUnit: target/surefire-reports/. CI executa em PRs.
