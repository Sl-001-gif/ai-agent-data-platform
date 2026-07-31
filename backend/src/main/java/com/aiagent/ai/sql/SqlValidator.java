package com.aiagent.ai.sql;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** SQL 安全校验器：独立兜底，不依赖任何生成器。规则 R1-R9 见 validate 内注释。 */
@Component
public class SqlValidator {

    /** 校验结果：通过标志 + 错误信息列表。 */
    public record ValidationResult(boolean valid, List<String> errors) {
    }

    private static final Set<String> TABLE_WHITELIST =
            new HashSet<>(List.of("order_info", "user_info", "product_info"));

    private static final Set<String> STATEMENT_BLACKLIST = new HashSet<>(List.of(
            "DROP", "UPDATE", "DELETE", "INSERT", "ALTER", "TRUNCATE", "CREATE", "REPLACE",
            "GRANT", "REVOKE", "SET", "EXEC", "EXECUTE", "CALL", "EXPLAIN", "SHOW", "USE",
            "MERGE", "LOAD", "HANDLER", "DO", "KILL", "RENAME", "START", "STOP", "COMMIT",
            "ROLLBACK", "SAVEPOINT", "LOCK", "UNLOCK", "PURGE", "RESET", "INSTALL",
            "UNINSTALL", "UNION", "INTO"));

    private static final Set<String> DANGEROUS_FUNCTION_BLACKLIST = new HashSet<>(List.of(
            "SLEEP", "BENCHMARK", "LOAD_FILE", "UPDATEXML", "EXTRACTVALUE", "GTID_SUBSET", "GTID_SUBTRACT"));

    private static final Set<String> SYSTEM_DB_BLACKLIST = new HashSet<>(List.of(
            "INFORMATION_SCHEMA", "PERFORMANCE_SCHEMA", "SYS", "MYSQL"));

    private static final Pattern TABLE_PATTERN =
            Pattern.compile("(FROM|JOIN)\\s+([A-Za-z0-9_]+)", Pattern.CASE_INSENSITIVE);

    /**
     * 校验 SQL 是否安全可执行。
     * R1 空值/空白 → 拒绝；R2 原始串含分号 → 拒绝；
     * R3 状态机剥除 '...' 与 "..." 字符串字面量得 skeleton；
     * R4 skeleton 含 --/#/* 注释符 → 拒绝；R5 首 token ≠ SELECT → 拒绝；
     * R6 语句级黑名单、R7 危险函数、R8 系统库 → 拒绝；
     * R9 提取 FROM/JOIN 表名，无 FROM 或表不在白名单 → 拒绝。
     */
    public ValidationResult validate(String sql, String table) {
        List<String> errors = new ArrayList<>();
        if (sql == null || sql.trim().isEmpty()) {
            errors.add("R1: SQL 为空");
            return new ValidationResult(false, errors);
        }
        String original = sql;
        if (original.contains(";")) {
            errors.add("R2: SQL 含分号");
        }
        String skeleton = stripStringLiterals(original);
        if (skeleton.contains("--") || skeleton.contains("#") || skeleton.contains("/*") || skeleton.contains("*/")) {
            errors.add("R4: SQL 含注释");
        }
        List<String> tokens = tokenize(skeleton);
        if (tokens.isEmpty() || !"SELECT".equals(tokens.get(0))) {
            errors.add("R5: SQL 必须以 SELECT 开头");
        }
        for (String token : tokens) {
            if (STATEMENT_BLACKLIST.contains(token)) {
                errors.add("R6: 命中语句级黑名单: " + token);
            }
            if (DANGEROUS_FUNCTION_BLACKLIST.contains(token)) {
                errors.add("R7: 命中危险函数: " + token);
            }
            if (SYSTEM_DB_BLACKLIST.contains(token)) {
                errors.add("R8: 命中系统库: " + token);
            }
        }
        List<String> tables = extractTables(skeleton);
        if (tables.isEmpty()) {
            errors.add("R9: 未找到 FROM/JOIN 表名");
        } else {
            for (String t : tables) {
                if (!TABLE_WHITELIST.contains(t)) {
                    errors.add("R9: 表不在白名单: " + t);
                }
            }
        }
        if (table != null && !table.isEmpty() && !TABLE_WHITELIST.contains(table)) {
            errors.add("R9: 目标表不在白名单: " + table);
        }
        return new ValidationResult(errors.isEmpty(), errors);
    }

    /** R3: 状态机剥除单双引号字符串字面量，内容替换为占位空格。 */
    private static String stripStringLiterals(String sql) {
        StringBuilder sb = new StringBuilder(sql.length());
        int i = 0;
        int n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            if (c == '\'' || c == '"') {
                char quote = c;
                sb.append(' ');
                i++;
                while (i < n) {
                    char d = sql.charAt(i);
                    if (d == '\\' && i + 1 < n) {
                        i += 2;
                        continue;
                    }
                    if (d == quote) {
                        if (i + 1 < n && sql.charAt(i + 1) == quote) {
                            i += 2;
                            continue;
                        }
                        i++;
                        break;
                    }
                    i++;
                }
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }

    /** 按非 [A-Za-z0-9_$] 切分 token 并统一大写，用于完整 token 匹配。 */
    private static List<String> tokenize(String sql) {
        List<String> tokens = new ArrayList<>();
        for (String part : sql.split("[^A-Za-z0-9_$]+")) {
            if (!part.isEmpty()) {
                tokens.add(part.toUpperCase(Locale.ROOT));
            }
        }
        return tokens;
    }

    /** R9: 提取 (FROM|JOIN) 后的表名。 */
    private static List<String> extractTables(String skeleton) {
        List<String> tables = new ArrayList<>();
        Matcher matcher = TABLE_PATTERN.matcher(skeleton);
        while (matcher.find()) {
            tables.add(matcher.group(2));
        }
        return tables;
    }
}