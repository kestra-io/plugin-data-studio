package io.kestra.plugin.datastudio;

import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.net.URI;
import java.util.List;

import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.FileSerde;

public final class FetchResults {

    private FetchResults() {
    }

    public static <T> Stored<T> apply(RunContext runContext, FetchType fetchType, List<T> rows) throws Exception {
        var size = rows.size();
        return switch (fetchType) {
            case FETCH_ONE -> new Stored<>(size, null, rows.isEmpty() ? null : rows.getFirst(), null);
            case FETCH -> new Stored<>(size, null, null, rows);
            case STORE -> new Stored<>(size, store(runContext, sink -> {
                for (var row : rows) {
                    sink.accept(row);
                }
            }).uri(), null, null);
            case NONE -> new Stored<>(size, null, null, null);
        };
    }

    // Rows are written as the producer emits them, so a large result is never held in memory.
    public static <T> Stored<T> store(RunContext runContext, RowProducer<T> producer) throws Exception {
        var path = runContext.workingDir().createTempFile(".ion");
        var counter = new int[]{0};
        try (var output = new BufferedOutputStream(new FileOutputStream(path.toFile()))) {
            producer.produce(row -> {
                FileSerde.write(output, row);
                counter[0]++;
            });
        }
        return new Stored<>(counter[0], runContext.storage().putFile(path.toFile()), null, null);
    }

    @FunctionalInterface
    public interface RowSink<T> {
        void accept(T row) throws Exception;
    }

    @FunctionalInterface
    public interface RowProducer<T> {
        void produce(RowSink<T> sink) throws Exception;
    }

    public record Stored<T>(int size, URI uri, T one, List<T> many) {
    }
}
