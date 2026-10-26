# DocGPT Project Reflection

## 1. System Design

**Core user actions**

- Sign up and log in; everything after that is scoped to that user.
- Upload a PDF or text file and see it move from processing to ready.
- Ask a question and get an answer that cites the chunks it came from, or a clear refusal.

**1a. Initial design**

The first version ([`diagrams/uml_draft.mmd`](diagrams/uml_draft.mmd)) used standard Spring
layering: controllers, services, repositories, entities. It worked, but `DocumentService` did
every RAG step itself: reading the file, chunking, calling the embedding model, writing to Qdrant
and searching. That made it hard to test any one step, and the same ownership check was copied
into three search methods.

**1b. Design changes**

The current design ([`diagrams/uml_final.mmd`](diagrams/uml_final.mmd)) gives each RAG stage its
own class, the same split I use in Python:

| Stage | Java | Python |
|---|---|---|
| Load | `DocumentTextExtractor` | `data_loader.load_all_documents` |
| Split | `TextChunker` | `data_loader.split_documents` |
| Embed | `EmbeddingService` | `embedding.EmbeddingManager` |
| Store | `QdrantService` | `vectorstore.VectorStore` |
| Split → embed → store | `IngestionPipeline` | `EmbeddingPipeline` + `add_documents` |
| Retrieve | `RagRetriever` | `search.RAGRetriever` |
| Generate | `ChatService` + `OllamaService` | `rag_simple` / `rag_advanced` |

The language differs, but the boundaries are the same: each stage takes the previous stage's
output and can be swapped or tested alone. In Java, Spring's dependency injection plays the role
of passing an `EmbeddingManager` into a `RAGRetriever` in Python.

---

## 2. Trade-offs

- **RAG over fine-tuning.** New documents are searchable seconds after upload, every answer can
  cite its source, and it runs on a laptop.
- **A 1B model, chosen by measurement.** `llama3.2:1b` was the smallest model with a 100% JSON
  pass rate and the lowest mean latency of the four I benchmarked. `tinyllama` is faster per token
  but slower per request and fails 42% of structured-output prompts.
- **Refuse rather than guess.** The grounded endpoint returns a fixed refusal when retrieval is
  empty, the model's JSON is invalid after 3 attempts, or no citation points at a real chunk.
  Some answerable questions get refused; in exchange, no answer cites a chunk that wasn't retrieved.
- **Isolation inside the search.** The `userId` filter runs inside Qdrant's HNSW search, not as a
  post-filter in Java, so it can't be skipped and doesn't cost extra queries.
- **Character chunking is a heuristic.** 1500 characters with 200 overlap is a judgment call; I
  haven't measured retrieval quality across chunk sizes yet.

---

## 3. AI Collaboration

**a. How I used AI tools**

I built the first version with Claude in chat, pasting errors and files back and forth. Later
phases used an AI coding agent in the repository, with `AGENTS.md` setting the rules. In both,
I set the goal, reviewed the design before code, and verified results myself.

**b. Suggestions I rejected or changed**

- **Unbacked metrics.** An early README listed performance and coverage numbers that no report in
  the repo produced. I replaced them with a real benchmark and a README where every claim links to
  its evidence.
- **Throughput as the model-selection metric.** Ranking by tokens per second would have picked
  `tinyllama`. Latency per request and JSON pass rate picked the model that actually works.
- **A page field nobody filled.** `Citation.page` was always null, so I removed it until
  page-aware chunking exists.

**c. What I learned about being the lead**

The most useful bug in this project came from reading the code, not from a failing test: the
"async" ingestion ran synchronously because of how Spring proxies `@Async`. Tests passed because
they don't measure which thread does the work. AI tools write plausible code fast; my job is to
know what the framework actually does and check the claim against it.

---

## 4. Testing and Verification

- **Unit tests** (Mockito) for the grounded-answer contract, structured-output retries, auth,
  exception mapping, and now the chunker, retriever and ingestion pipeline.
- **Integration tests** (Testcontainers) against real Postgres and Qdrant for the two properties
  mocks can't prove: tenant isolation and the grounded RAG path. Ollama is stubbed there so the
  tests are fast and deterministic.
- **Frontend tests** (React Testing Library) for the auth flow, chat UI with citation chips, and
  the API client.
- Everything runs in GitHub Actions on every push and pull request.

**Confidence: 4/5.** Isolation and grounding are proven against real stores. What's missing is a
retrieval-quality evaluation: I measure the model, not whether the right chunks come back.

---

## 5. Reflection

**What went well**

- Treating the LLM as untrusted input: schema, validation, retries and a refusal path.
- Proving isolation against real infrastructure instead of mocks.

**What I would improve next**

- **Evaluate the pipeline, not just the model.** Add a labeled question set and score retrieval
  relevance, groundedness, answer relevance and correctness with an LLM judge, the same four
  evaluators I built in Python with LangSmith.
- **Put an LLM gateway in front of Ollama** with fallbacks, caching and PII redaction before
  prompts leave the service.
- **Stream responses** and work out how to validate citations on a streamed answer.
- **Hybrid search and reranking** once there is an eval to show they help.

**Key takeaway**

A RAG app is a pipeline of small, separate stages. Keeping them separate in the code is what
makes each one testable, and what lets me say which stage to improve next.
