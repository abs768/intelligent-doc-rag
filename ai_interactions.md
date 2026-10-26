# AI Interactions Log

How DocGPT was built with AI assistants, phase by phase. Each phase lists its commits so every
entry can be checked against the history. Phase 1 was built with **Claude** in chat; the later
phases used **Claude Code** working in the repository under the rules in [`AGENTS.md`](AGENTS.md).

---

## The workflow I used in every phase

I acted as the human in the loop between the idea and the agent's output:

1. **Describe the goal** in product terms first ("a user must never see another user's chunks").
2. **Get the design before the code**: the classes, their responsibilities and how they connect.
3. **Approve or redirect** the design before anything is built.
4. **Build in small modules**, one responsibility per class, so each can be tested on its own.
5. **Verify, then repeat**: run the tests, read the output, and only then start the next phase.

---

## Phase 1 — First working RAG app (`4f28e39`, January 2026)

**What I asked for:** a document Q&A app in Spring Boot: upload a PDF, ask questions, get
answers from a local LLM, with per-user accounts.

**What was built:** the layered design in [`diagrams/uml_draft.mmd`](diagrams/uml_draft.mmd):
controllers, `AuthService` + `JwtService`, `DocumentService`, `ChatService`, `EmbeddingService`,
`QdrantService`, `OllamaService`, JPA entities, a React frontend and Docker Compose.

**What I had to fix or verify myself:**
- A compile error from a missing entity import in `ConversationRepository`.
- The Qdrant Java client failing at compile time with `ListenableFuture` not found; the fix was
  adding the Guava dependency it needs.
- RAG answers that ignored the uploaded file. The cause was that no document was selected, so
  retrieval had nothing to search; selecting the document made the UI and the RAG path agree.
- Uploads that looked stuck while embeddings were generated, which later led to the status
  polling and chunked upload.

## Phase 2 — Grounded answers, benchmark, real-infrastructure tests (`0436c93` → `b2414c5`, June 2026)

**What I asked for:** answers that can't make things up, a measured reason for the model
choice, and tests that prove tenant isolation against real stores.

**What was built:**
- `POST /api/chat/grounded`: schema-validated JSON from the model, up to 3 attempts, citations
  mapped only to chunks that were actually retrieved, and a fixed refusal otherwise.
- The `bench/` harness: 42 prompts × 3 iterations across 4 Ollama models.
- Testcontainers tests against real Postgres and Qdrant for isolation and the grounded path.

**What I checked or changed:**
- I replaced performance numbers in the old README that no artifact backed with a link to
  `docs/benchmark.md` (`12ccfdf`).
- I added `llama3.2:1b` to the benchmark because it is the model the app actually ships
  (`929c61a`), and corrected the test counts (`b2414c5`).
- I removed `Citation.page` because nothing filled it (`ff2e425`): a field that is always null
  reads as unfinished work.

## Phase 3 — CI, frontend tests, an honest README (`c954568` → `57e2337`, July 2026)

**What was built:** a GitHub Actions workflow (backend tests including Testcontainers, frontend
tests and build, both Docker images), 18 React Testing Library tests, and a demo GIF.

**What I checked:** the README was rewritten so every claim links to the code, test or report
that proves it (`7d0d7e9`). CI caught a stale `package-lock.json` and an exhaustive-deps lint
error, both fixed in follow-up commits (`241c9d2`, `a473b5d`).

## Phase 4 — Publish latency, remove a gated link (`8870145`, `a8a6d3d`, September 2026)

**What I checked:** the benchmark summary now leads with measured latency, not only throughput,
because throughput alone would have picked the wrong model. A demo link that required access
was removed so reviewers don't hit a login wall.

## Phase 5 — Modular RAG structure (this branch)

**What I asked for:** make the Java code follow the same modular structure as my Python RAG
pipeline (`data_loader` / `embedding` / `vectorstore` / `search`), with the same documents as my
other projects: diagrams, `AGENTS.md`, this log and a reflection.

**What Claude did:**
- Split `DocumentService` into one class per stage: `DocumentTextExtractor` (load),
  `TextChunker` (split), `IngestionPipeline` (split → embed → store) and `RagRetriever`
  (retrieve). `DocumentService` now only handles upload, list and delete.
- Found that ingestion was not actually asynchronous: `finalizeUpload` called its own `@Async`
  method, which Spring runs synchronously because `@Async` works through a proxy. Moving the
  method to `IngestionPipeline` fixed it. It also removed `@Transactional` from finalize so the
  `PROCESSING` row is committed before the background thread reads it.
- Collapsed three copies of the document-ownership check into one method in `RagRetriever`.
- Added unit tests for the chunker, the retriever and the pipeline, and wrote the diagrams from
  the code.

**What I checked:** prompts, chunk size and overlap, models and the grounded-answer contract are
unchanged, and CI (which compiles and runs every test, including Testcontainers) is green
before merging.
