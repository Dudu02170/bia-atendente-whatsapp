# QA de back-end da BIA
Java 17, Maven 3.9+, JUnit 5, Jackson, JDBC e Testcontainers. Requer Docker em execução.
```sh
cd qa
mvn --batch-mode test
```
Cinco testes de contrato estrutural do workflow real: nomes únicos, conexões válidas, entrada POST, caminho completo ao envio e passagem pelo validador final.
Um teste de integração inicializa o SQL real em PostgreSQL 16 descartável e verifica defaults, unicidade, NOT NULL e chave estrangeira.
Não carrega .env, não acessa o banco existente, não chama IA, WhatsApp ou Evolution.
## Defeito encontrado na revisão
sql/01-schema.sql referencia public.atualizar_timestamp(), mas nenhum arquivo do repositório define essa função. O teste de inicialização deve ficar vermelho até a migração ser corrigida. Não há skip, criação artificial da função ou mudança do SQL para esconder o problema.
As verificações de integridade posteriores ficam bloqueadas enquanto a inicialização falhar.
## Limites
Os testes Jackson verificam a topologia/configuração, não executam os nós do n8n. Ainda não cobrem respostas HTTP do webhook, interpretação das mensagens, IA e entrega WhatsApp. Esses testes exigem uma instância isolada de n8n e serviços controlados.
Relatórios JUnit: target/surefire-reports/. CI executa em PRs.
Referências: https://java.testcontainers.org/modules/databases/postgres/
