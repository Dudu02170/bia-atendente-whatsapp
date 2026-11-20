package br.eduardoconsul.qa;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import java.nio.file.*;
import java.sql.*;
import static org.junit.jupiter.api.Assertions.*;

class BiaDatabaseTest {
  @Test void schemaInicializaBancoVazioEAplicaIntegridade() throws Exception {
    // Sempre um PostgreSQL descartável, sem ler .env ou URLs externas.
    try(var pg=new PostgreSQLContainer("postgres:16-alpine").withDatabaseName("bia_qa").withUsername("qa").withPassword("qa-local-only")) {
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
  @Test void editarLeadAtualizaTimestampEPreservaCriacaoEOutrosRegistros() throws Exception {
    try(var pg=new PostgreSQLContainer("postgres:16-alpine").withDatabaseName("bia_qa").withUsername("qa").withPassword("qa-local-only")) {
      pg.start();
      try(var c=DriverManager.getConnection(pg.getJdbcUrl(),pg.getUsername(),pg.getPassword());var s=c.createStatement()) {
        s.execute(Files.readString(Path.of("../sql/01-schema.sql")));
        // Datas fixas evitam sleeps e garantem que um gatilho inerte não passe.
        s.executeUpdate("""
          INSERT INTO leads (telefone,nome,cidade,criado_em,atualizado_em) VALUES
          ('550000000001','Lead QA','Cidade QA',TIMESTAMP '2000-01-01 00:00:00',TIMESTAMP '2000-01-01 00:00:00'),
          ('550000000002','Outro QA','Outra cidade',TIMESTAMP '2000-01-01 00:00:00',TIMESTAMP '2000-01-01 00:00:00')
          """);
        c.setAutoCommit(false);
        assertEquals(1,s.executeUpdate("""
          UPDATE leads SET nome='Lead QA editado',atualizado_em=TIMESTAMP '1999-01-01 00:00:00'
          WHERE telefone='550000000001'
          """));
        try(var rs=s.executeQuery("""
          SELECT nome,cidade,status,bot_ativo,criado_em,atualizado_em,
                 CURRENT_TIMESTAMP::timestamp AS esperado
          FROM leads WHERE telefone='550000000001'
          """)) {
          assertTrue(rs.next());
          assertEquals("Lead QA editado",rs.getString("nome"));
          assertEquals("Cidade QA",rs.getString("cidade"));
          assertEquals("novo",rs.getString("status"));assertTrue(rs.getBoolean("bot_ativo"));
          assertEquals(Timestamp.valueOf("2000-01-01 00:00:00"),rs.getTimestamp("criado_em"));
          assertEquals(rs.getTimestamp("esperado"),rs.getTimestamp("atualizado_em"),
            "O gatilho deve usar o horário do banco na transação, mesmo com timestamp enviado pelo cliente");
          assertTrue(rs.getTimestamp("atualizado_em").after(rs.getTimestamp("criado_em")));
        }
        try(var rs=s.executeQuery("SELECT nome,atualizado_em FROM leads WHERE telefone='550000000002'")) {
          assertTrue(rs.next());assertEquals("Outro QA",rs.getString("nome"));
          assertEquals(Timestamp.valueOf("2000-01-01 00:00:00"),rs.getTimestamp("atualizado_em"));
        }
        try(var rs=s.executeQuery("SELECT count(*) FROM leads")) {
          assertTrue(rs.next());assertEquals(2,rs.getInt(1));
        }
        c.commit();
      }
    }
  }
  private static void assertSqlState(Statement statement,String state,String sql) {
    SQLException error=assertThrows(SQLException.class,()->statement.executeUpdate(sql));
    assertEquals(state,error.getSQLState());
  }
}
