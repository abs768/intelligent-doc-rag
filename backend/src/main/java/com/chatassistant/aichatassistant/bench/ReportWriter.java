package com.chatassistant.aichatassistant.bench;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public final class ReportWriter {

    private ReportWriter() {}

    public static Path write(
            Path outputDir,
            List<String> models,
            List<Prompt> prompts,
            List<BenchResult> results,
            Map<String, Long> modelSizeBytes,
            int iterations,
            String ollamaUrl
    ) throws IOException {
        Files.createDirectories(outputDir);
        String stamp = Instant.now().toString().replace(':', '-').replaceAll("\\..*", "");
        Path file = outputDir.resolve("REPORT-" + stamp + ".md");

        StringBuilder md = new StringBuilder();
        md.append("# Ollama model benchmark\n\n");
        md.append("- **Generated:** ").append(Instant.now()).append("\n");
        md.append("- **Ollama endpoint:** ").append(ollamaUrl).append("\n");
        md.append("- **Iterations per (model, prompt):** ").append(iterations).append("\n");
        md.append("- **OS:** ").append(System.getProperty("os.name"))
                .append(" / ").append(System.getProperty("os.arch")).append("\n");
        md.append("- **JVM:** ").append(System.getProperty("java.version")).append("\n");
        md.append("- **CPU cores:** ").append(Runtime.getRuntime().availableProcessors()).append("\n\n");

        md.append("## Prompt suite\n\n");
        Map<String, Long> byCategory = prompts.stream()
                .collect(Collectors.groupingBy(Prompt::category, Collectors.counting()));
        md.append("| Category | Count |\n|---|---|\n");
        byCategory.forEach((k, v) -> md.append("| ").append(k).append(" | ").append(v).append(" |\n"));
        md.append("\n");

        md.append("## Per-model summary\n\n");
        md.append("| Model | Disk (MB) | Median latency (ms) | p95 latency (ms) | Median tok/s | TTFT median (ms) | Correctness |\n");
        md.append("|---|---:|---:|---:|---:|---:|---:|\n");
        for (String model : models) {
            List<BenchResult> rs = filter(results, r -> r.model().equals(model) && r.errorMessage() == null);
            if (rs.isEmpty()) {
                md.append("| ").append(model).append(" | — | (no results) | — | — | — | — |\n");
                continue;
            }
            long sizeBytes = modelSizeBytes.getOrDefault(model, 0L);
            md.append("| ").append(model)
              .append(" | ").append(sizeBytes == 0 ? "—" : String.format("%.0f", sizeBytes / 1_048_576.0))
              .append(" | ").append(median(rs, BenchResult::latencyMs))
              .append(" | ").append(percentile(rs, BenchResult::latencyMs, 0.95))
              .append(" | ").append(String.format("%.1f", medianDouble(rs, BenchResult::tokensPerSec)))
              .append(" | ").append(median(rs, BenchResult::ttftMs))
              .append(" | ").append(String.format("%.0f%%", 100.0 * correctnessRate(rs)))
              .append(" |\n");
        }
        md.append("\n");

        md.append("## Per-category breakdown\n\n");
        for (String model : models) {
            md.append("### ").append(model).append("\n\n");
            md.append("| Category | Median latency (ms) | Median tok/s | Correctness |\n|---|---:|---:|---:|\n");
            for (String cat : byCategory.keySet()) {
                List<BenchResult> rs = filter(results,
                        r -> r.model().equals(model) && r.category().equals(cat) && r.errorMessage() == null);
                if (rs.isEmpty()) {
                    md.append("| ").append(cat).append(" | — | — | — |\n");
                    continue;
                }
                md.append("| ").append(cat)
                  .append(" | ").append(median(rs, BenchResult::latencyMs))
                  .append(" | ").append(String.format("%.1f", medianDouble(rs, BenchResult::tokensPerSec)))
                  .append(" | ").append(String.format("%.0f%%", 100.0 * correctnessRate(rs)))
                  .append(" |\n");
            }
            md.append("\n");
        }

        md.append("## Sample outputs\n\n");
        List<Prompt> samples = prompts.stream().limit(3).toList();
        for (Prompt p : samples) {
            md.append("### ").append(p.id()).append(" — ").append(p.category()).append("\n\n");
            md.append("**System:** ").append(oneLine(p.system())).append("  \n");
            md.append("**User:** ").append(oneLine(p.user())).append("\n\n");
            for (String model : models) {
                BenchResult first = filter(results,
                        r -> r.model().equals(model) && r.promptId().equals(p.id()) && r.iteration() == 0)
                        .stream().findFirst().orElse(null);
                md.append("**").append(model).append(":** ");
                if (first == null || first.errorMessage() != null) {
                    md.append("_(no output");
                    if (first != null) md.append(": ").append(first.errorMessage());
                    md.append(")_\n\n");
                } else {
                    md.append(first.correct() ? "✓" : "✗").append(" ");
                    md.append("`").append(oneLine(first.output()).replace("`", "'")).append("`\n\n");
                }
            }
        }

        md.append("## Errors\n\n");
        List<BenchResult> errors = filter(results, r -> r.errorMessage() != null);
        if (errors.isEmpty()) {
            md.append("_None._\n");
        } else {
            md.append("| Model | Prompt | Error |\n|---|---|---|\n");
            errors.forEach(r -> md.append("| ").append(r.model())
                    .append(" | ").append(r.promptId())
                    .append(" | ").append(oneLine(r.errorMessage())).append(" |\n"));
        }

        Files.writeString(file, md.toString(), StandardCharsets.UTF_8);
        return file;
    }

    // ---------------- helpers ----------------

    private static <T> List<T> filter(Collection<T> items, java.util.function.Predicate<T> pred) {
        return items.stream().filter(pred).collect(Collectors.toList());
    }

    private static long median(List<BenchResult> rs, java.util.function.ToLongFunction<BenchResult> f) {
        return (long) percentileDouble(
                rs.stream().mapToLong(f).asDoubleStream().boxed().collect(Collectors.toList()), 0.5);
    }

    private static long percentile(List<BenchResult> rs, java.util.function.ToLongFunction<BenchResult> f, double p) {
        return (long) percentileDouble(
                rs.stream().mapToLong(f).asDoubleStream().boxed().collect(Collectors.toList()), p);
    }

    private static double medianDouble(List<BenchResult> rs, java.util.function.ToDoubleFunction<BenchResult> f) {
        return percentileDouble(
                rs.stream().mapToDouble(f).boxed().collect(Collectors.toList()), 0.5);
    }

    private static double percentileDouble(List<Double> values, double p) {
        if (values.isEmpty()) return 0.0;
        List<Double> sorted = new java.util.ArrayList<>(values);
        sorted.sort(Comparator.naturalOrder());
        int idx = (int) Math.min(sorted.size() - 1, Math.floor(p * (sorted.size() - 1)));
        return sorted.get(idx);
    }

    private static double correctnessRate(List<BenchResult> rs) {
        if (rs.isEmpty()) return 0.0;
        long ok = rs.stream().filter(BenchResult::correct).count();
        return ok / (double) rs.size();
    }

    private static String oneLine(String s) {
        if (s == null) return "";
        String collapsed = s.replace('\n', ' ').replace('\r', ' ').replaceAll("\\s+", " ").trim();
        return collapsed.length() > 240 ? collapsed.substring(0, 240) + "…" : collapsed;
    }

    @SuppressWarnings("unused")
    private static <K, V> Map<K, V> ordered(Map<K, V> in) {
        return new LinkedHashMap<>(in);
    }
}
