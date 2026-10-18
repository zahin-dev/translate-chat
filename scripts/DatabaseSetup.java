import java.sql.DriverManager;

/** Creates only this project's two databases. Does not change existing records. */
class DatabaseSetup {
    public static void main(String[] args) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:postgresql://127.0.0.1:55432/postgres",
                "chat", System.getenv("CHAT_DB_PASSWORD"))) {
            for (String database : new String[] {"translate_chat", "translate_chat_test"}) {
                boolean exists;
                try (var query = connection.prepareStatement("SELECT 1 FROM pg_database WHERE datname=?")) {
                    query.setString(1, database);
                    try (var rows = query.executeQuery()) { exists = rows.next(); }
                }
                if (!exists) {
                    try (var statement = connection.createStatement()) { statement.execute("CREATE DATABASE " + database); }
                }
            }
        }
        System.out.println("Project databases are ready.");
    }
}
