package dev.ledgerly.ledger;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Checks the ledger invariants I1–I4 with the statements of {@code scripts/invariants.sql}, the same file that is run
 * by hand after load tests.
 */
public final class InvariantChecker {

    private static final String SCRIPT = "scripts/invariants.sql";
    private static final Pattern INVARIANT_HEADER = Pattern.compile("^-- (I\\d+):.*");

    private final JdbcClient jdbc;
    private final Map<String, String> statements;

    public InvariantChecker(JdbcClient jdbc) {
        this.jdbc = jdbc;
        this.statements = parse(read(locateScript()));
    }

    /** The rows that violate each invariant, keyed by invariant ({@code I1} … {@code I4}). Empty if sound. */
    public Map<String, List<Map<String, @Nullable Object>>> violations() {
        Map<String, List<Map<String, @Nullable Object>>> violations = new LinkedHashMap<>();
        statements.forEach((invariant, sql) -> {
            List<Map<String, @Nullable Object>> rows = jdbc.sql(sql).query().listOfRows();
            if (!rows.isEmpty()) {
                violations.put(invariant, rows);
            }
        });
        return violations;
    }

    /** One statement per invariant: the lines from an {@code -- I<n>:} comment up to the terminating semicolon. */
    static Map<String, String> parse(List<String> lines) {
        Map<String, String> statements = new LinkedHashMap<>();
        @Nullable String invariant = null;
        StringBuilder sql = new StringBuilder();
        for (String line : lines) {
            Matcher header = INVARIANT_HEADER.matcher(line);
            if (header.matches()) {
                invariant = header.group(1);
                sql.setLength(0);
            } else if (invariant != null && !line.startsWith("--")) {
                sql.append(line).append('\n');
                if (line.stripTrailing().endsWith(";")) {
                    String statement = sql.toString().strip();
                    statements.put(invariant, statement.substring(0, statement.length() - 1));
                    invariant = null;
                }
            }
        }
        if (statements.isEmpty()) {
            throw new IllegalStateException(SCRIPT + " contains no invariant statements");
        }
        return statements;
    }

    /** Tests run from the module directory, the script lives at the repository root. */
    private static Path locateScript() {
        for (Path directory = Path.of("").toAbsolutePath(); directory != null; directory = directory.getParent()) {
            Path candidate = directory.resolve(SCRIPT);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException(SCRIPT + " not found in the working directory or any of its parents");
    }

    private static List<String> read(Path script) {
        try {
            return Files.readAllLines(script);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
