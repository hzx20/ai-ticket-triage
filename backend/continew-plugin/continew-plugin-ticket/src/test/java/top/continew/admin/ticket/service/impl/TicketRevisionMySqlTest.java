/*
 * Copyright (c) 2022-present Charles7c Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package top.continew.admin.ticket.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

/**
 * 在显式指定的隔离 MySQL 测试库验证迁移与版本比较。
 *
 * @author hzx20
 * @since 1.1.0
 */
class TicketRevisionMySqlTest {

    @Test
    @DisplayName("版本迁移保留旧数据，并阻止 ABA 后的旧分类结果写入")
    void revisionMigrationAndCompareAndSet() throws Exception {
        String url = System.getenv("TICKET_MYSQL_TEST_URL");
        Assumptions.assumeTrue(url != null && !url.isBlank(), "未配置隔离 MySQL 测试库");
        // 必须显式使用本机上的测试专用库，不能回退到应用库。
        assertThat(url).matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]+/"
            + "codex_ticket_test_[a-z0-9_]+(\\?.*)?");
        String user = System.getenv("TICKET_MYSQL_TEST_USER");
        String password = System.getenv("TICKET_MYSQL_TEST_PASSWORD");
        assertThat(user).isNotBlank();
        assertThat(password).isNotNull();
        String table = "ticket_revision_test_" + UUID.randomUUID().toString().replace("-", "");
        String migration = Files.readString(Path.of("..", "..", "continew-server", "src", "main",
            "resources", "db", "changelog", "mysql", "plugin", "plugin_ticket_revision.sql"));
        String sql = migration.lines().filter(line -> !line.stripLeading().startsWith("--"))
            .reduce("", (left, right) -> left + "\n" + right).trim();
        assertThat(sql).contains("ALTER TABLE `ticket`");
        sql = sql.replace("`ticket`", "`" + table + "`");

        try (Connection connection = DriverManager.getConnection(url, user, password);
            Statement statement = connection.createStatement()) {
            assertThat(connection.getCatalog()).startsWith("codex_ticket_test_");
            boolean created = false;
            try {
                statement.execute("CREATE TABLE `" + table + "` (id BIGINT PRIMARY KEY, "
                    + "title VARCHAR(100) COLLATE utf8mb4_general_ci NOT NULL) "
                    + "DEFAULT CHARSET=utf8mb4");
                created = true;
                statement.executeUpdate("INSERT INTO `" + table + "` (id, title) VALUES (1, 'a')");
                statement.execute(sql);
                try (ResultSet result = statement
                    .executeQuery("SELECT revision FROM `" + table + "` WHERE id = 1")) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getLong(1)).isZero();
                }
                assertThat(update(connection, table, "A", 0)).isEqualTo(1);
                assertThat(update(connection, table, "a", 1)).isEqualTo(1);
                // Case-insensitive comparison and an A→B→A edit must not let a stale write through.
                assertThat(update(connection, table, "stale", 0)).isZero();
                assertThat(update(connection, table, "classified", 2)).isEqualTo(1);
                assertThat(update(connection, table, "stale editor", 2)).isZero();
                try (ResultSet result = statement
                    .executeQuery("SELECT title, revision FROM `" + table + "` WHERE id = 1")) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getString(1)).isEqualTo("classified");
                    assertThat(result.getLong(2)).isEqualTo(3L);
                }
            } finally {
                if (created) {
                    statement.execute("DROP TABLE `" + table + "`");
                }
            }
        }
    }

    private int update(Connection connection, String table, String title, long revision)
        throws Exception {
        try (PreparedStatement update = connection.prepareStatement("UPDATE `" + table
            + "` SET title = ?, revision = revision + 1 WHERE id = 1 AND revision = ?")) {
            update.setString(1, title);
            update.setLong(2, revision);
            return update.executeUpdate();
        }
    }
}
