import java.nio.file.*;
import java.sql.*;

public class PatchNodeConfigs {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("usage: PatchNodeConfigs <taskId> <configDir>");
        }
        long taskId = Long.parseLong(args[0]);
        Path configDir = Paths.get(args[1]);
        try (Connection connection = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/ecommerce_agent?client_encoding=UTF8",
                "postgres",
                "postgres")) {
            connection.setAutoCommit(false);
            int updated = 0;
            try (PreparedStatement ps = connection.prepareStatement(
                    "update task_node set node_config = ? where task_id = ? and node_name = ?")) {
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(configDir, "*.json")) {
                    for (Path file : stream) {
                        String nodeName = file.getFileName().toString().replaceFirst("\\.json$", "");
                        String config = Files.readString(file);
                        ps.setString(1, config);
                        ps.setLong(2, taskId);
                        ps.setString(3, nodeName);
                        updated += ps.executeUpdate();
                    }
                }
            }
            connection.commit();
            System.out.println("updated=" + updated);
        }
    }
}