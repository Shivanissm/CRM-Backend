import java.sql.*;

public class DbInspect {
  public static void main(String[] a) throws Exception {
    String url = "jdbc:mysql://mysql-3ffe4f3b-shivanisatyam307612-e694.c.aivencloud.com:12687/defaultdb?useSSL=true&allowPublicKeyRetrieval=true&serverTimezone=Asia/Kolkata";
    try (Connection c = DriverManager.getConnection(url, "avnadmin", "AVNS_LnAq7YpV4PYRJrONit3");
         Statement s = c.createStatement()) {
      print(s, "SELECT id, name, organization_id, team_id, is_deleted FROM pipelines ORDER BY id");
      print(s, "SELECT id, username, organization_id, pipeline_id FROM brideside_vendors ORDER BY id");
      print(s, "SELECT id, name, is_active FROM organizations ORDER BY id");
      print(s, "SELECT u.id, u.email, r.name AS role FROM users u JOIN roles r ON u.role_id = r.id ORDER BY u.id LIMIT 10");
      print(s, "SELECT id, name FROM teams ORDER BY id");
    }
  }
  static void print(Statement s, String sql) throws SQLException {
    System.out.println("\n=== " + sql + " ===");
    try (ResultSet rs = s.executeQuery(sql)) {
      int cols = rs.getMetaData().getColumnCount();
      while (rs.next()) {
        StringBuilder row = new StringBuilder();
        for (int i = 1; i <= cols; i++) {
          if (i > 1) row.append(" | ");
          row.append(rs.getMetaData().getColumnLabel(i)).append('=').append(rs.getObject(i));
        }
        System.out.println(row);
      }
    }
  }
}
