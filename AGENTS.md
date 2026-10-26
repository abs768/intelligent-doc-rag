# AGENTS.md — DocGPT

Instructions for any coding agent (Codex, Claude Code, Copilot) working in this repository.
Read this whole file before the first edit of a session.

## Project
DocGPT is a self-hosted RAG app: users upload PDFs or text files and ask questions; answers
are grounded in the retrieved chunks, cite them, and refuse when retrieval finds nothing.
Spring Boot 3.2 / Java 21 backend, React 18 frontend, PostgreSQL, Qdrant, Ollama.

## Where things live — one class per RAG stage
| Stage | Class | Python equivalent |
|---|---|---|
| 1. Load | `rag/DocumentTextExtractor` | `data_loader.load_all_documents` |
| 2. Split | `rag/TextChunker` (1500 chars, 200 overlap) | `data_loader.split_documents` |
| 3. Embed | `service/EmbeddingService` (nomic-embed-text, 768-dim) | `embedding.EmbeddingManager` |
| 4. Store | `service/QdrantService` | `vectorstore.VectorStore` |
| 2–4 orchestration | `rag/IngestionPipeline` | `EmbeddingPipeline` + `add_documents` |
| 5. Retrieve | `rag/RagRetriever` | `search.RAGRetriever` |
| 6. Generate | `service/ChatService` + `service/OllamaService` | `rag_simple` / `rag_advanced` |

- `service/DocumentService` — upload, list, delete. It calls the pipeline; it does no RAG work itself.
- `bench/` — the model benchmark harness behind `docs/benchmark.md`.
- `diagrams/uml_final.mmd` — the class design. `diagrams/uml.mmd` is a copy of it.

## Current phase: Phase 5 — modular RAG structure and documentation
- Allowed: the `rag` package, `DocumentService`, `ChatService` wiring, tests, docs, diagrams.
- Not allowed: changing prompts, chunk size/overlap, the embedding model, the chat model, or
  the grounded-answer contract. Those changes need a benchmark or eval run to justify them.

## Rules that always apply
- **Tenant isolation is non-negotiable.** Every Qdrant search filters by `userId`, and
  `RagRetriever` checks document ownership before searching. Never add a search path that skips either.
- **The LLM is untrusted.** Grounded answers must be schema-valid JSON, cite only chunks that
  were actually retrieved, and fall back to `ChatService.GROUNDED_REFUSAL` otherwise.
- **`@Async` only works across beans.** Spring applies it through a proxy, so an `@Async`
  method called from its own class runs synchronously. Keep async work in `IngestionPipeline`.
- **One stage, one class.** New retrieval logic goes in `RagRetriever`, new splitting in
  `TextChunker`. Don't rebuild chunking or embedding inside another service.
- **Numbers in docs come from evidence.** Benchmark figures come from `docs/benchmark.md`;
  test counts come from an actual `mvn test` / `npm test` run.
- If the class design changes, update `diagrams/uml_final.mmd` in the same change and copy it
  to `diagrams/uml.mmd`.

## How to verify
```bash
cd backend
mvn test                                     # all tests; 2 classes need Docker (Testcontainers)
mvn test -Dtest='!GroundedRagIntegrationTest,!MultiTenantIsolationDemoTest'   # no infrastructure

cd ../frontend
npm test -- --watchAll=false
npm run build
```
- Definition of done: CI is green (backend tests, frontend tests and build, Docker images).
- If a test fails, explain why before changing code. Never edit a test just to make it pass.
- If you could not run a command, say so plainly instead of implying it passed.

## Style
- Java 21, constructor injection, records for DTOs, SLF4J logging (never log document contents).
- Javadoc on every public class saying which RAG stage it is.
- Keep it simple; explain any change that adds complexity before making it.
