import java.sql.*;
public class DbInspect2 {
  public static void main(String[] a) throws Exception {
    String url = "jdbc:mysql://mysql-3ffe4f3b-shivanisatyam307612-e694.c.aivencloud.com:12687/defaultdb?useSSL=true&allowPublicKeyRetrieval=true&serverTimezone=Asia/Kolkata";
    try (Connection c = DriverManager.getConnection(url, "avnadmin", "AVNS_LnAq7YpV4PYRJrONit3"); Statement s = c.createStatement()) {
      print(s, "SELECT t.id, t.name, t.manager_id, u.email AS manager_email FROM teams t LEFT JOIN users u ON u.id = t.manager_id");
      print(s, "SELECT o.id, o.name, o.owner_id, u.email AS owner_email FROM organizations o LEFT JOIN users u ON u.id = o.owner_id");
      print(s, "SELECT COUNT(*) AS null_team_pipelines FROM pipelines WHERE is_deleted = 0 AND team_id IS NULL");
    }
  }
  static void print(Statement s, String sql) throws SQLException {
    System.out.println("\n=== " + sql + " ===");
    try (ResultSet rs = s.executeQuery(sql)) {
      int cols = rs.getMetaData().getColumnCount();
      while (rs.next()) {
        StringBuilder row = new StringBuilder();
        for (int i = 1; i <= cols; i++) { if (i>1) row.append(" | "); row.append(rs.getMetaData().getColumnLabel(i)).append('=').append(rs.getObject(i)); }
        System.out.println(row);
      }
    }
  }
}
