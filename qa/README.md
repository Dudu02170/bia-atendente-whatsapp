# QA de back-end da BIA
Java 17, Maven 3.9+, Node.js 22+, JUnit 5, Jackson, JDBC e Testcontainers. Requer Docker em execução.
```sh
cd qa
mvn --batch-mode test
```

Nove testes de contrato estrutural verificam o workflow exportado: identidade/conexões dos nós, entrada POST, caminhos de envio, roteamento, gates e referências literais nos parâmetros.

BiaInputFilterTest executa o JavaScript real do nó "Filtrar e Extrair Dados", extraído do JSON versionado, por meio de um processo Node chamado pelo JUnit. São 14 casos com payloads fictícios: quatro formatos de texto, evento direto com valores padrão e nove rejeições (mensagem própria, broadcast, grupo, lid, JID inválido, texto vazio, áudio sem texto, outro evento e ausência de data).
O adaptador fornece somente $input.first().json e console.log. A execução tem timeout e não disponibiliza clientes de HTTP, banco, IA ou WhatsApp ao código. Nenhum .env é carregado.

Um teste de integração inicializa o SQL real em PostgreSQL 16 descartável e verifica defaults, unicidade, NOT NULL e chave estrangeira.

## Defeito conhecido
sql/01-schema.sql referencia public.atualizar_timestamp(), mas nenhum arquivo do repositório define essa função. A inicialização deve permanecer vermelha até a migração ser corrigida. Não há skip nem criação artificial da função. As verificações de integridade posteriores ficam bloqueadas enquanto a inicialização falhar.

## Limites
Os contratos Jackson são estruturais. Os casos do filtro executam uma unidade de JavaScript com um adaptador mínimo; não executam o runtime, os nós ou as conexões do n8n. Não comprovam recebimento HTTP do webhook, comportamento da IA, execução das consultas do workflow ou entrega no WhatsApp. A integração PostgreSQL usa banco descartável, sem acessar dados reais.

Relatórios JUnit: target/surefire-reports/. CI executa em PRs.
