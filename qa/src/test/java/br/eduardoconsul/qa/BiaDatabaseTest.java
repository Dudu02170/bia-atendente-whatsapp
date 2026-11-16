package br.eduardoconsul.qa;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import java.nio.file.*;
import java.sql.*;
import static org.junit.jupiter.api.Assertions.*;

class BiaDatabaseTest {
  @Test void schemaInicializaBancoVazioEAplicaIntegridade() throws Exception {
    // Sempre um PostgreSQL descartável, sem ler .env ou URLs externas.
    try(var pg=new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("bia_qa").withUsername("qa").withPassword("qa-local-only")) {
      pg.start();
      try(var c=DriverManager.getConnection(pg.getJdbcUrl(),pg.getUsername(),pg.getPassword());var s=c.createStatement()) {
        String sql=Files.readString(Path.of("../sql/01-schema.sql"));
        assertDoesNotThrow(()->s.execute(sql),
          "O schema do repositório deve inicializar um banco vazio. Confira a função atualizar_timestamp usada pelo gatilho.");
        s.executeUpdate("INSERT INTO leads (telefone) VALUES ('550000000001')");
        try(var rs=s.executeQuery("SELECT status,bot_ativo,experiencia_resina,criado_em FROM leads WHERE telefone='550000000001'")) {
          assertTrue(rs.next());assertEquals("novo",rs.getString("status"));
          assertTrue(rs.getBoolean("bot_ativo"));assertFalse(rs.getBoolean("experiencia_resina"));assertNotNull(rs.getTimestamp("criado_em"));
        }
        assertSqlState(s,"23505","INSERT INTO leads (telefone) VALUES ('550000000001')");
        assertSqlState(s,"23502","INSERT INTO leads (telefone) VALUES (NULL)");
        assertSqlState(s,"23503","INSERT INTO mensagens (lead_id,telefone,direcao,conteudo) VALUES (-1,'550000000001','entrada','QA')");
        s.executeUpdate("INSERT INTO mensagens_whatsapp (telefone,role,mensagem,message_id) VALUES ('550000000001','user','QA','qa-id')");
        assertSqlState(s,"23505","INSERT INTO mensagens_whatsapp (telefone,role,mensagem,message_id) VALUES ('550000000001','user','QA','qa-id')");
      }
    }
  }
  private static void assertSqlState(Statement statement,String state,String sql) {
    SQLException error=assertThrows(SQLException.class,()->statement.executeUpdate(sql));
    assertEquals(state,error.getSQLState());
  }
}
