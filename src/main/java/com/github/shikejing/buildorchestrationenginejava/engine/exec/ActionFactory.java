package com.github.shikejing.buildorchestrationenginejava.engine.exec;

import com.github.shikejing.buildorchestrationenginejava.engine.model.ActionSpec;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Creates actions from declarative specs. Actions only write into the
 * staging directory; the engine commits outputs atomically afterwards,
 * so a failed action never leaves half-written products in the output tree.
 */
public class ActionFactory {

    public Action create(ActionSpec spec) {
        return switch (spec.getKind()) {
            case WRITE -> ctx -> {
                String content = spec.getParams().getOrDefault("content", "");
                writeOutput(ctx, 0, content.getBytes(StandardCharsets.UTF_8));
            };
            case CONCAT -> ctx -> {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                for (String input : ctx.getTarget().getInputs()) {
                    byte[] data = ctx.getInputs().read(input)
                            .orElseThrow(() -> new IOException("missing input: " + input));
                    buf.write(data, 0, data.length);
                }
                writeOutput(ctx, 0, buf.toByteArray());
            };
            case COPY -> ctx -> {
                String input = ctx.getTarget().getInputs().isEmpty()
                        ? null : ctx.getTarget().getInputs().get(0);
                if (input == null) {
                    throw new IOException("copy action requires at least one input");
                }
                byte[] data = ctx.getInputs().read(input)
                        .orElseThrow(() -> new IOException("missing input: " + input));
                writeOutput(ctx, 0, data);
            };
            case SLEEP -> ctx -> {
                long ms = Long.parseLong(spec.getParams().getOrDefault("ms", "0"));
                Thread.sleep(ms);
                writeOutput(ctx, 0, ("slept " + ms).getBytes(StandardCharsets.UTF_8));
            };
            case APPEND -> ctx -> {
                // Appends to a side-effect log (absolute path in params.file),
                // then writes the declared output. params.fail=true makes it
                // fail after appending, so tests can count attempts.
                Map<String, String> params = spec.getParams();
                String log = params.get("file");
                if (log == null) {
                    throw new IOException("append action requires param 'file'");
                }
                String line = params.getOrDefault("content", ctx.getTarget().getName());
                Path logPath = Path.of(log);
                Files.createDirectories(logPath.toAbsolutePath().getParent());
                Files.writeString(logPath, line + System.lineSeparator(),
                        StandardCharsets.UTF_8,
                        Files.exists(logPath)
                                ? java.nio.file.StandardOpenOption.APPEND
                                : java.nio.file.StandardOpenOption.CREATE);
                writeOutput(ctx, 0, line.getBytes(StandardCharsets.UTF_8));
                if ("true".equals(params.get("fail"))) {
                    throw new IllegalStateException("append action failed as declared");
                }
            };
            case FAIL -> ctx -> {
                throw new IllegalStateException(
                        spec.getParams().getOrDefault("message", "action failed as declared"));
            };
        };
    }

    private static void writeOutput(ActionContext ctx, int outputIndex, byte[] bytes) throws IOException {
        var outputs = ctx.getTarget().getOutputs();
        if (outputs.size() <= outputIndex) {
            throw new IOException("action wants to write output #" + outputIndex
                    + " but target '" + ctx.getTarget().getName() + "' declares only "
                    + outputs.size());
        }
        Path dst = ctx.getStagingDir().resolve(outputs.get(outputIndex)).normalize();
        Files.createDirectories(dst.getParent());
        Files.write(dst, bytes);
    }
}
