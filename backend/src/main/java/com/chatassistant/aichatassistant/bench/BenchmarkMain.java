package com.chatassistant.aichatassistant.bench;

import com.chatassistant.aichatassistant.client.OllamaChatResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * CLI benchmark for Ollama models against a fixed prompt suite.
 *
 * Run (from the backend/ directory):
 *   ./mvnw spring-boot:run \
 *     -Dspring-boot.run.main-class=com.chatassistant.aichatassistant.bench.BenchmarkMain \
 *     -Dspring-boot.run.arguments="--models=llama3.2:1b,phi3:mini --iterations=3"
 *
 * Args:
 *   --models=<csv>        comma-separated Ollama model tags (already pulled)
 *   --iterations=<n>      runs per (model, prompt). default 3
 *   --ollama-url=<url>    default http://localhost:11434
 *   --prompts=<path>      override prompt suite YAML. default bundled bench/prompts.yaml
 *   --output-dir=<path>   default ./bench-reports
 */
public final class BenchmarkMain {

    public static void main(String[] args) throws Exception {
        Args parsed = Args.parse(args);
        if (parsed.models.isEmpty()) {
            System.err.println("ERROR: --models is required (comma-separated list of Ollama model tags)");
            System.err.println("Example: --models=llama3.2:1b,phi3:mini,mistral:7b-instruct");
            System.exit(2);
        }

        ObjectMapper mapper = new ObjectMapper();
        OllamaBenchClient client = new OllamaBenchClient(parsed.ollamaUrl, mapper);
        List<Prompt> prompts = PromptSuite.load(parsed.promptsPath);

        log("Loaded %d prompts. Models: %s. Iterations: %d.",
                prompts.size(), parsed.models, parsed.iterations);

        List<BenchResult> results = new ArrayList<>();
        Map<String, Long> modelSizes = new HashMap<>();

        for (String model : parsed.models) {
            log("=== Model: %s ===", model);

            // Warm-up — first call also pulls model into memory if needed
            try {
                client.chat(model, "You are a test.", "Reply with the single word: ok.", null);
            } catch (Exception e) {
                log("Warm-up failed for %s: %s — skipping remaining prompts for this model.",
                        model, e.getMessage());
                continue;
            }

            for (Prompt p : prompts) {
                for (int iter = 0; iter < parsed.iterations; iter++) {
                    BenchResult r = runOne(client, mapper, model, p, iter);
                    results.add(r);
                    log("  [%s][%s iter=%d] %dms tok/s=%.1f correct=%s",
                            model, p.id(), iter, r.latencyMs(), r.tokensPerSec(), r.correct());
                }
            }

            modelSizes.put(model, client.loadedModelSizeBytes(model));
        }

        Path outDir = parsed.outputDir;
        Path report = ReportWriter.write(
                outDir, parsed.models, prompts, results, modelSizes, parsed.iterations, parsed.ollamaUrl);
        log("Report written: %s", report.toAbsolutePath());
    }

    // ---------------- per-call ----------------

    private static BenchResult runOne(
            OllamaBenchClient client, ObjectMapper mapper, String model, Prompt p, int iter
    ) {
        String format = "json".equals(p.category()) ? "json" : null;
        long t0 = System.nanoTime();
        OllamaChatResponse resp;
        try {
            resp = client.chat(model, p.system(), p.user(), format);
        } catch (Exception e) {
            return BenchResult.error(model, p, iter, e.getMessage());
        }
        long latencyMs = (System.nanoTime() - t0) / 1_000_000L;

        String output = resp.message() == null ? "" : resp.message().content();
        long ttftMs = ((nullToZero(resp.loadDurationNs()) + nullToZero(resp.promptEvalDurationNs())) / 1_000_000L);
        double tokensPerSec = computeTokensPerSec(resp);
        int evalCount = resp.evalCount() == null ? 0 : resp.evalCount();
        boolean correct = score(p, output, mapper);

        return new BenchResult(model, p.id(), p.category(), iter,
                latencyMs, ttftMs, tokensPerSec, evalCount, correct, output, null);
    }

    private static double computeTokensPerSec(OllamaChatResponse resp) {
        if (resp.evalCount() == null || resp.evalDurationNs() == null || resp.evalDurationNs() == 0L) {
            return 0.0;
        }
        return resp.evalCount() / (resp.evalDurationNs() / 1_000_000_000.0);
    }

    private static long nullToZero(Long v) { return v == null ? 0L : v; }

    // ---------------- scoring ----------------

    private static boolean score(Prompt p, String output, ObjectMapper mapper) {
        if (output == null || output.isBlank()) return false;
        return switch (p.category()) {
            case "json" -> scoreJson(p, output, mapper);
            case "factual", "rag" -> scoreContains(p, output);
            default -> false;
        };
    }

    private static boolean scoreContains(Prompt p, String output) {
        if (p.goldContains().isEmpty()) return true; // no gold = treat as pass
        String lower = output.toLowerCase(Locale.ROOT);
        return p.goldContains().stream().allMatch(s -> lower.contains(s.toLowerCase(Locale.ROOT)));
    }

    private static boolean scoreJson(Prompt p, String output, ObjectMapper mapper) {
        try {
            JsonNode node = mapper.readTree(output);
            if (!node.isObject()) return false;
            for (String key : p.requiredKeys()) {
                if (!node.has(key)) return false;
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ---------------- args ----------------

    private record Args(
            List<String> models, int iterations, String ollamaUrl, Path promptsPath, Path outputDir
    ) {
        static Args parse(String[] argv) {
            List<String> models = List.of();
            int iterations = 3;
            String url = "http://localhost:11434";
            Path prompts = null;
            Path outDir = Paths.get("bench-reports");

            for (String arg : argv) {
                if (arg.startsWith("--models=")) {
                    models = List.of(arg.substring("--models=".length()).split("\\s*,\\s*"));
                } else if (arg.startsWith("--iterations=")) {
                    iterations = Integer.parseInt(arg.substring("--iterations=".length()));
                } else if (arg.startsWith("--ollama-url=")) {
                    url = arg.substring("--ollama-url=".length());
                } else if (arg.startsWith("--prompts=")) {
                    prompts = Paths.get(arg.substring("--prompts=".length()));
                } else if (arg.startsWith("--output-dir=")) {
                    outDir = Paths.get(arg.substring("--output-dir=".length()));
                }
            }
            return new Args(models, iterations, url, prompts, outDir);
        }
    }

    private static void log(String fmt, Object... args) {
        System.out.println(String.format(fmt, args));
    }

    private BenchmarkMain() {}
}
