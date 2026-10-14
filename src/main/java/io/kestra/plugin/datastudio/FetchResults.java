package io.kestra.plugin.datastudio;

import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;

import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.FileSerde;

public final class FetchResults {

    private FetchResults() {
    }

    public static <T> Stored<T> apply(RunContext runContext, FetchType fetchType, List<T> rows) throws Exception {
        int size = rows.size();
        return switch (fetchType) {
            case FETCH_ONE -> new Stored<>(size, null, rows.isEmpty() ? null : rows.getFirst(), null);
            case FETCH -> new Stored<>(size, null, null, rows);
            case STORE -> new Stored<>(size, store(runContext, rows), null, null);
            case NONE -> new Stored<>(size, null, null, null);
        };
    }

    private static <T> URI store(RunContext runContext, List<T> rows) throws Exception {
        Path path = runContext.workingDir().createTempFile(".ion");
        try (OutputStream output = new BufferedOutputStream(new FileOutputStream(path.toFile()))) {
            for (T row : rows) {
                FileSerde.write(output, row);
            }
        }
        return runContext.storage().putFile(path.toFile());
    }

    public record Stored<T>(int size, URI uri, T one, List<T> many) {
    }
}
