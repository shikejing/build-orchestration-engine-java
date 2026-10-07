package com.github.shikejing.buildorchestrationenginejava.execution;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class CommandToolchain implements Toolchain {
    @Override
    public String name() {
        return "command";
    }

    @Override
    public void execute(TaskExecutionContext context) throws Exception {
        if (context.arguments() == null || context.arguments().isEmpty()) {
            throw new IllegalArgumentException("command toolchain requires arguments");
        }
        ProcessBuilder builder = new ProcessBuilder(context.arguments());
        builder.directory(context.stagingDirectory().toFile());
        builder.environment().clear();
        builder.environment().putAll(System.getenv());
        builder.environment().putAll(context.environment());
        builder.environment().put("BUILD_WORKSPACE", context.workspace().toString());
        builder.environment().put("BUILD_INPUT_ROOT", context.workspace().toString());
        builder.environment().put("BUILD_OUTPUT_ROOT", context.stagingDirectory().toString());
        builder.environment().put("BUILD_TARGET", context.targetName());

        Process process = builder.start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread stdout = transfer(process, output, true);
        Thread stderr = transfer(process, output, false);
        if (!process.waitFor(24, TimeUnit.HOURS)) {
            process.destroyForcibly();
            throw new IOException("Command timed out after engine safety limit");
        }
        stdout.join(1000);
        stderr.join(1000);
        if (process.exitValue() != 0) {
            String text = output.toString(StandardCharsets.UTF_8).trim();
            throw new IOException("Command exited with " + process.exitValue()
                    + (text.isEmpty() ? "" : ": " + abbreviate(text)));
        }
    }

    private Thread transfer(Process process, ByteArrayOutputStream output, boolean stdout) {
        var stream = stdout ? process.getInputStream() : process.getErrorStream();
        Thread thread = new Thread(() -> {
            try {
                synchronized (output) {
                    output.write((stdout ? "[stdout]\n" : "[stderr]\n").getBytes(StandardCharsets.UTF_8));
                }
                byte[] buffer = new byte[4096];
                int read;
                while ((read = stream.read(buffer)) >= 0) {
                    synchronized (output) {
                        output.write(buffer, 0, read);
                    }
                }
            } catch (IOException ignored) {
            }
        }, "command-output");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private String abbreviate(String text) {
        return text.length() <= 4000 ? text : text.substring(0, 4000) + "...";
    }
}
