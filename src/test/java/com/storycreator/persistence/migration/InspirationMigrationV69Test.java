package com.storycreator.persistence.migration;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V69（灵感允许无项目）迁移脚本的行为验证 —— 在「**已经有数据**」的表上把真实脚本再跑一遍。
 *
 * <p>为什么单独立一条：这次迁移踩到两个只在「线上有数据」时才爆炸的坑，而常规的
 * {@code @DataJpaTest} 起的是空库，正好两个都绕开了：
 * <ol>
 *   <li>H2 <b>删不掉匿名外键</b>（V66 建表时没给约束起名），只能关掉引用完整性后重建整张表；</li>
 *   <li>重建后 <b>IDENTITY 序列不会因为显式插入历史 id 而前移</b>：下一次自增插入会重新
 *       从 1 开始，当场撞主键 —— V60 就漏了这一步。</li>
 * </ol>
 *
 * <p>这里用裸 JDBC 造出 V69 之前的样子（匿名外键 + 三行数据），再执行迁移脚本本身，把
 * 「保留 id / 序列前移 / 外键放开 / 引用完整性还原」四件事钉住。不依赖 Spring 上下文，
 * 因此也不受其它用例事务回滚语义的影响。
 */
class InspirationMigrationV69Test {

    private static final String MIGRATION = "/db/migration/V69__inspiration_without_project.sql";

    @Test
    void replayOnTableWithData_keepsIds_advancesIdentity_dropsForeignKey() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:h2:mem:v69_migration_test;DB_CLOSE_DELAY=-1", "sa", "");
             Statement s = c.createStatement()) {

            // ---------- 1. 造出 V69 之前的样子 ----------
            s.execute("CREATE TABLE projects (id BIGINT AUTO_INCREMENT PRIMARY KEY, title VARCHAR(200) NOT NULL)");
            s.execute("INSERT INTO projects (title) VALUES ('甲'), ('乙')");
            s.execute("""
                    CREATE TABLE inspirations (
                        id          BIGINT AUTO_INCREMENT PRIMARY KEY,
                        project_id  BIGINT NOT NULL,
                        title       VARCHAR(200) NOT NULL,
                        content     TEXT,
                        created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        updated_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE
                    )
                    """);
            s.execute("CREATE INDEX idx_inspirations_project ON inspirations(project_id)");
            s.execute("INSERT INTO inspirations (project_id, title, content) "
                    + "VALUES (1, '一', 'a'), (1, '二', 'b'), (2, '三', 'c')");

            // 迁移之前，「无项目」（project_id = 0）是**写不进去**的 —— 这正是要放开的那件事
            assertThatThrownBy(() -> s.execute("INSERT INTO inspirations (project_id, title) VALUES (0, '零')"))
                    .as("迁移前 project_id = 0 应被外键拒绝")
                    .hasMessageContaining("Referential integrity constraint violation");

            // ---------- 2. 执行真实迁移脚本 ----------
            List<String> statements = migrationStatements();
            assertThat(statements).as("迁移脚本应能被拆成多条语句").hasSizeGreaterThanOrEqualTo(8);
            for (String stmt : statements) {
                s.execute(stmt);
            }

            // ---------- 3. 老数据必须原样还在（连 id 都不要漂） ----------
            assertThat(count(s, "SELECT COUNT(*) FROM inspirations")).as("重建不能丢数据").isEqualTo(3);
            assertThat(count(s, "SELECT COUNT(*) FROM inspirations WHERE id = 1 AND title = '一' AND content = 'a'"))
                    .as("回填要连 id/标题/正文一起搬过来").isEqualTo(1);
            assertThat(count(s, "SELECT MAX(id) FROM inspirations")).as("id 必须保留").isEqualTo(3);

            // ---------- 4. 自增序列必须被顶到 max(id)+1 ----------
            // H2 的 IDENTITY 不会因为「显式插入 id」而前进；漏掉 RESTART 就会撞历史主键。
            s.execute("INSERT INTO inspirations (project_id, title) VALUES (1, '新行')");
            assertThat(count(s, "SELECT COUNT(*) FROM inspirations")).isEqualTo(4);
            assertThat(count(s, "SELECT MAX(id) FROM inspirations"))
                    .as("迁移后的第一次自增插入必须拿到 4，而不是退回 1")
                    .isEqualTo(4);

            // ---------- 5. 0 号放行，外键也确实没了 ----------
            s.execute("INSERT INTO inspirations (project_id, title) VALUES (0, '无项目灵感')");
            assertThat(count(s, "SELECT COUNT(*) FROM inspirations WHERE project_id = 0")).isEqualTo(1);
            s.execute("INSERT INTO inspirations (project_id, title) VALUES (999999, '指向不存在的项目')");
            assertThat(count(s, "SELECT COUNT(*) FROM inspirations WHERE project_id = 999999")).isEqualTo(1);

            // ---------- 6. 引用完整性必须被还原 ----------
            // 迁移期间关过 SET REFERENTIAL_INTEGRITY FALSE，忘了还原 = 整库外键从此形同虚设。
            s.execute("CREATE TABLE ri_probe (id BIGINT AUTO_INCREMENT PRIMARY KEY, pid BIGINT NOT NULL, "
                    + "FOREIGN KEY (pid) REFERENCES projects(id))");
            assertThatThrownBy(() -> s.execute("INSERT INTO ri_probe (pid) VALUES (123456)"))
                    .as("迁移结束后引用完整性必须已还原（否则全库外键都失去约束力）")
                    .hasMessageContaining("Referential integrity constraint violation");
        }
    }

    /** 读取真实迁移脚本并拆成可执行语句（先剥掉行注释，避免注释里的分号干扰）。 */
    private static List<String> migrationStatements() throws Exception {
        String sql;
        try (InputStream in = InspirationMigrationV69Test.class.getResourceAsStream(MIGRATION)) {
            assertThat(in).as("迁移脚本应在 classpath 上：%s", MIGRATION).isNotNull();
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        String withoutComments = Arrays.stream(sql.split("\\R"))
                .filter(line -> !line.trim().startsWith("--"))
                .collect(Collectors.joining("\n"));
        return Arrays.stream(withoutComments.split(";"))
                .map(String::trim)
                .filter(stmt -> !stmt.isEmpty())
                .toList();
    }

    private static long count(Statement s, String sql) throws Exception {
        try (ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
