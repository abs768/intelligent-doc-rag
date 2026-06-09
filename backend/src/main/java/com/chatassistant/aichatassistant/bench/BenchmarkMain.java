package com.chatassistant.aichatassistant.bench;

import com.chatassistant.aichatassistant.client.OllamaChatResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * CLI benchmark for Ollama models against a fixed prompt suite.
 *
 * Methodology:
 *   - For each (model, prompt) pair we run N iterations (default 3) and capture timing
 *     from Ollama's own response payload — load_duration, prompt_eval_duration,
 *     eval_count, eval_duration.
 *   - TTFT is approximated as (load_duration + prompt_eval_duration). Streaming would
 *     give a more precise number.
 *   - Quality is scored ONLY for the `json` category (valid JSON object + required
 *     keys present). The `factual` and `rag` categories are reported as latency/
 *     throughput only — substring matching is too loose to call correctness.
 *
 * Run from backend/:
 *   mvn spring-boot:run \
 *     -Dspring-boot.run.main-class=com.chatassistant.aichatassistant.bench.BenchmarkMain \
 *     -Dspring-boot.run.arguments="--models=llama3.2:3b,llama3:latest --iterations=3"
 *
 * Args:
 *   --models=<csv>        Ollama model tags (already pulled).
 *   --iterations=<n>      Iterations per (model, prompt). Default 3.
 *   --ollama-url=<url>    Default http://localhost:11434.
 *   --prompts=<path>      Override prompt suite YAML. Default classpath bench/prompts.yaml.
 *   --output=<path>       Canonical report path. Default docs/benchmark.md.
 *   --history-dir=<path>  Timestamped copy directory. Default bench-reports. Empty disables.
 */
public final class BenchmarkMain {

    public static void main(String[] args) throws Exception {
        Args parsed = Args.parse(args);
        if (parsed.models.isEmpty()) {
            System.err.println("ERROR: --models is required (comma-separated list of Ollama model tags)");
            System.err.println("Example: --models=llama3.2:3b,llama3:latest");
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
                    String correctness = "json".equals(p.category()) ? (r.correct() ? " ✓" : " ✗") : "";
                    log("  [%s][%s iter=%d] %dms tok/s=%.1f%s",
                            model, p.id(), iter, r.latencyMs(), r.tokensPerSec(), correctness);
                }
            }

            modelSizes.put(model, client.loadedModelSizeBytes(model));
        }

        String markdown = ReportWriter.render(
                parsed.models, prompts, results, modelSizes, parsed.iterations, parsed.ollamaUrl);

        Path canonical = ReportWriter.writeToFile(parsed.outputPath, markdown);
        log("Canonical report: %s", canonical.toAbsolutePath());

        if (parsed.historyDir != null) {
            String stamp = Instant.now().toString().replace(':', '-').replaceAll("\\..*", "");
            Path historyFile = parsed.historyDir.resolve("REPORT-" + stamp + ".md");
            ReportWriter.writeToFile(historyFile, markdown);
            log("History copy:     %s", historyFile.toAbsolutePath());
        }
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

    /**
     * Only the json category is scored for quality. factual/rag previously used
     * substring matching on a reference answer, but that proved too fragile to
     * defend as a quality measure — see prompts.yaml for the reference fields,
     * left in as informational only.
     */
    private static boolean score(Prompt p, String output, ObjectMapper mapper) {
        if (!"json".equals(p.category())) return false;
        if (output == null || output.isBlank()) return false;
        return scoreJson(p, output, mapper);
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
            List<String> models,
            int iterations,
            String ollamaUrl,
            Path promptsPath,
            Path outputPath,
            Path historyDir
    ) {
        static Args parse(String[] argv) {
            List<String> models = List.of();
            int iterations = 3;
            String url = "http://localhost:11434";
            Path prompts = null;
            Path outputPath = Paths.get("docs", "benchmark.md");
            Path historyDir = Paths.get("bench-reports");

            for (String arg : argv) {
                if (arg.startsWith("--models=")) {
                    models = List.of(arg.substring("--models=".length()).split("\\s*,\\s*"));
                } else if (arg.startsWith("--iterations=")) {
                    iterations = Integer.parseInt(arg.substring("--iterations=".length()));
                } else if (arg.startsWith("--ollama-url=")) {
                    url = arg.substring("--ollama-url=".length());
                } else if (arg.startsWith("--prompts=")) {
                    prompts = Paths.get(arg.substring("--prompts=".length()));
                } else if (arg.startsWith("--output=")) {
                    outputPath = Paths.get(arg.substring("--output=".length()));
                } else if (arg.startsWith("--history-dir=")) {
                    String v = arg.substring("--history-dir=".length());
                    historyDir = v.isBlank() ? null : Paths.get(v);
                }
            }
            return new Args(models, iterations, url, prompts, outputPath, historyDir);
        }
    }

    private static void log(String fmt, Object... args) {
        System.out.println(String.format(fmt, args));
    }

    private BenchmarkMain() {}
}
