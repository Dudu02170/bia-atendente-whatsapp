# Defeito reproduzido pela automação
## QA-BIA-001 — Schema não inicializa em PostgreSQL vazio
Teste Java BiaDatabaseTest, PostgreSQL 16 descartável:
ERROR: function public.atualizar_timestamp() does not exist.
O script sql/01-schema.sql cria trigger_leads_atualizar, mas a função chamada não é criada em nenhum arquivo do repositório.
Reprodução: cd qa && mvn test, com Java 17, Maven e Docker.
Evidência: https://github.com/Dudu02170/bia-atendente-whatsapp/actions/runs/43980093610
Impacto: um deploy do banco a partir do SQL versionado falha; as verificações de constraints posteriores no teste ficam bloqueadas.
Sugestão: versionar a função antes da criação do gatilho e repetir a suíte. O teste não injeta nem simula a função ausente.
Os cinco testes estruturais do workflow passaram nesse run. Eles não equivalem à execução das integrações n8n/WhatsApp.
