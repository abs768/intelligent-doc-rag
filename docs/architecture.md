# Design notes

Reasoning behind the main decisions in DocGPT, plus an honest list of
limitations. Where a decision is backed by a measurement, it links to the
artifact; where it was a judgment call, it says so.

## RAG instead of fine-tuning

Users upload arbitrary documents and expect to query them immediately.
Fine-tuning would require a training run per corpus change, offers no source
attribution, and increases hallucination risk on out-of-distribution
questions. Retrieval makes new documents queryable as soon as embedding
finishes (seconds), gives every answer a citation trail, and runs on CPU-only
hardware. The cost is retrieval-quality dependency and added query latency —
acceptable trade-offs here.

## Model choice: llama3.2:1b

Backed by measurement, not intuition — see [benchmark.md](benchmark.md).
Four local models were benchmarked (42 prompts × 3 iterations) for latency,
throughput, and structured-output quality. `llama3.2:1b` was the smallest
model with a 100% JSON-extraction pass rate (~71 tok/s); `tinyllama` is
faster but fails ~42% of structured-output prompts, which would break the
grounded-answer contract. The 3B and 8B models matched the 1B's pass rate at
2–4× the latency, with no measurable quality gain on this suite.

The benchmark deliberately does **not** claim factual-accuracy numbers:
substring-matching a small model's prose against a reference answer is too
unreliable to call correctness. Building a proper retrieval/answer eval
(labeled question→chunk pairs, recall@k, faithfulness scoring) is the top
item on the roadmap below.

## Chunking: 1500 characters with 200 overlap

`DocumentService` splits extracted text into 1500-character chunks with
200-character overlap, breaking on word boundaries. These numbers are a
judgment call, not a measured optimum: large enough that a chunk usually
holds a complete thought for the LLM to cite, small enough to keep top-k
retrieval focused, with overlap so sentences straddling a boundary appear
intact in at least one chunk. Measuring retrieval quality across chunk sizes
is part of the planned eval work — until then this is explicitly a heuristic.

## Vector store: Qdrant

Requirements: 768-dim vectors (nomic-embed-text), payload filtering for
multi-tenancy, self-hostable for free, easy to run in Docker and
Testcontainers. Qdrant meets all four with indexed payload filters, meaning
the `userId` filter is applied *inside* the HNSW search rather than by
post-filtering results in the application — this is what makes per-user
isolation cheap. Pinecone was ruled out on cost for a self-hosted project;
Weaviate and Milvus would also have worked, but no comparative latency
benchmark was run, so no performance claim is made between them.

## Multi-tenant isolation

Every vector search in `QdrantService` carries a mandatory `userId` filter
plus a `documentId` filter for the user's selected documents. The property
"user B cannot read user A's chunks, even with a guessed document ID" is
proven against real Postgres + Qdrant containers in
[MultiTenantIsolationDemoTest](../backend/src/test/java/com/chatassistant/aichatassistant/MultiTenantIsolationDemoTest.java),
not just against mocks — a mocked test cannot catch filter-semantics bugs in
the real store.

## Grounded answers: schema + validation + refusal

`POST /api/chat/grounded` treats the LLM as an untrusted component:

1. The model must return JSON matching a schema (`GroundedAnswer`).
2. The response is parsed and checked with Bean Validation; invalid output is
   retried up to 3 attempts (`OllamaService.chatStructured`).
3. Citation indices are resolved against the list of chunks that were
   actually retrieved — an index pointing at a non-existent chunk is dropped.
4. If retrieval returned nothing, no citation survived, or the structured
   call kept failing, the API returns a fixed refusal string instead of an
   answer.

The contract is covered by unit tests
([ChatServiceGroundedTest](../backend/src/test/java/com/chatassistant/aichatassistant/service/ChatServiceGroundedTest.java),
[OllamaServiceStructuredTest](../backend/src/test/java/com/chatassistant/aichatassistant/service/OllamaServiceStructuredTest.java))
and an end-to-end Testcontainers test
([GroundedRagIntegrationTest](../backend/src/test/java/com/chatassistant/aichatassistant/GroundedRagIntegrationTest.java)).

## Async ingestion

Document upload returns `202 Accepted` after persisting metadata; extraction,
chunking, embedding, and vector upsert run on a Spring `@Async` thread, with
the document row transitioning `PENDING → PROCESSING → INGESTED | FAILED`.
The frontend polls `/api/documents/list` until ingestion settles. Large files
are uploaded in parts via `/upload-chunk` + `/upload-finalize` to stay under
request-size limits. At this scale a thread pool is the right amount of
machinery — a message queue would add operational surface without adding
capability for a single-node deployment.

## Testing approach

- **Unit tests** (Mockito) for service logic: grounded-answer validation,
  auth, exception mapping.
- **Integration tests** (Testcontainers) against real Postgres and Qdrant for
  the properties that mocks can't prove: tenant isolation and the
  grounded-RAG contract. Ollama is *stubbed* in these tests (deterministic
  embeddings, canned structured answers) so they are fast and deterministic
  while the stores stay real.
- **Frontend tests** (React Testing Library) for the auth flow, the chat UI
  (citation chips, telemetry, send gating), and the API client.
- 37 backend tests (32 need no infrastructure, 5 need Docker) + 18 frontend
  tests. All run in CI.

## Known limitations / roadmap

Listed here so the README doesn't oversell:

- **No streaming responses** — the chat endpoint returns a complete answer
  after generation finishes. SSE streaming (and how to reconcile it with
  post-hoc citation validation) is the next feature.
- **No retrieval-quality evaluation** — the benchmark measures the model, not
  the pipeline. A labeled golden set with recall@k / MRR and a faithfulness
  judge is planned.
- **No hybrid search or reranking** — retrieval is dense-only top-k.
- **No rate limiting** — the API is unthrottled.
- **Observability is logs only** — no metrics or tracing (Micrometer /
  OpenTelemetry) yet.
- **Frontend is Create React App** — deprecated upstream; a Vite + TypeScript
  migration is planned.
- **Single-node by design** — no HA story. A cloud deployment would need
  managed Postgres and Qdrant plus either a GPU-backed inference host for
  Ollama or a swap to a hosted LLM API.
