# DocGPT

> **DocGPT is a self-hosted “chat with your PDFs” application built with Spring Boot, React, PostgreSQL, Qdrant, and Ollama.**

It lets users upload PDF documents, extracts and chunks the text, embeds each chunk into a vector database, and answers questions using retrieved document context with a local LLM.

This is a portfolio RAG system focused on the core document-ingestion and retrieval flow: authentication, PDF upload, vector search, document-grounded chat, async processing, and Docker-based local deployment.

---

## What this is

DocGPT is a standard Retrieval-Augmented Generation application:

```text
PDF upload
   ↓
PDFBox text extraction
   ↓
Character-based chunking
   ↓
Ollama embeddings
   ↓
Qdrant vector storage
   ↓
User question
   ↓
Query embedding
   ↓
Top-k vector retrieval
   ↓
Context-stuffed prompt
   ↓
Ollama chat response
```

The app is designed to run locally through Docker Compose. It does not require a hosted LLM API.

---

## Architecture

```text
React frontend
   ↓
Spring Boot backend
   ↓
PostgreSQL      Qdrant        Ollama
users           vectors       chat model
documents       chunks        embedding model
conversations   payloads
messages
```

### Components

| Layer             | Technology             | Purpose                                                                                  |
| ----------------- | ---------------------- | ---------------------------------------------------------------------------------------- |
| Frontend          | React                  | Login, upload, chat, document selection, persona/language options, light/dark theme      |
| Backend           | Spring Boot 3, Java 17 | Auth, document ingestion, chat orchestration, async processing, service/repository layer |
| Relational DB     | PostgreSQL             | Users, document metadata, conversations, messages                                        |
| Vector DB         | Qdrant                 | Stores embedded document chunks with metadata payloads                                   |
| Local LLM Runtime | Ollama                 | Runs the chat model and embedding model locally                                          |
| Deployment        | Docker Compose         | Runs frontend, backend, PostgreSQL, Qdrant, and Ollama together                          |

---

## Core features

* User authentication
* PDF upload and text extraction
* Chunked upload flow for larger files
* Async document ingestion
* Document processing status polling
* Character-based chunking with overlap
* Local embeddings through Ollama
* Qdrant vector search
* Multi-tenant vector isolation using mandatory `userId` filtering
* Document-grounded chat responses
* Conversation and message persistence
* Persona options through prompt customization
* Spanish response toggle
* Docker Compose local deployment

---

## RAG flow

### 1. Upload

A user uploads a PDF through the React frontend.

The backend extracts text using PDFBox and splits the document into chunks.

Current chunking strategy:

```text
chunk size: 1500 characters
overlap: 200 characters
```

Each chunk is embedded through Ollama and stored in Qdrant with payload metadata:

```text
userId
documentId
filename
content
```

PostgreSQL stores only document metadata, users, conversations, and messages. The actual vectorized document chunks live in Qdrant.

---

### 2. Ask a question

When a user asks a question:

```text
question
   ↓
Ollama embedding
   ↓
Qdrant top-k search
   ↓
retrieved chunks
   ↓
system prompt with document context
   ↓
Ollama chat response
```

The prompt instructs the model to answer using only the retrieved context.

---

### 3. Multi-tenant retrieval

Every vector search includes a mandatory `userId` filter so users can only retrieve chunks from their own documents.

This is one of the most important backend design choices in the project.

```text
search query
   +
mandatory userId filter
   ↓
Qdrant retrieval
```

---

## Tech stack

### Frontend

* React
* JavaScript
* Light/dark theme
* Upload and chat UI
* Persona and language controls

### Backend

* Java 17
* Spring Boot 3
* Spring Security / JWT authentication
* Spring Data JPA
* PostgreSQL
* Qdrant client integration
* Ollama API integration
* PDFBox for text extraction
* Async processing for document ingestion

### Infrastructure

* Docker Compose
* PostgreSQL container
* Qdrant container
* Ollama container
* Backend container
* Frontend container

---

## Local setup

### Prerequisites

Install:

* Docker
* Docker Compose

Ollama models used by default:

```text
llama3.2:1b
nomic-embed-text
```

Depending on your Docker/Ollama setup, you may need to pull the models manually:

```bash
ollama pull llama3.2:1b
ollama pull nomic-embed-text
```

---

## Run with Docker Compose

```bash
docker compose up --build
```

This starts:

```text
frontend
backend
postgres
qdrant
ollama
```

After the services start, open the frontend in your browser.

```text
http://localhost:3000
```

The backend API runs on:

```text
http://localhost:8080
```

Qdrant runs on:

```text
http://localhost:6333
```

---

## Environment configuration

The project uses Docker Compose environment variables for database, JWT, Qdrant, and Ollama configuration.

Before deploying anywhere beyond local development, replace development secrets such as:

```text
your-super-secret-jwt-key-change-this-in-production
```

Do not use the default JWT secret outside local testing.

---

## Example usage

1. Register or log in.
2. Upload a PDF.
3. Wait for the document status to change from `PROCESSING` to `INGESTED`.
4. Select the document.
5. Ask a question.
6. The backend retrieves relevant chunks from Qdrant and sends them to the local LLM.
7. The response is shown in the chat UI.

---

## API overview

The backend follows a controller → service → repository structure.

Typical responsibilities:

```text
Auth controller       registration and login
Document controller   upload, status, document listing
Chat controller       conversations, messages, question answering
Qdrant service        embeddings, upserts, vector search
Ollama service        local LLM and embedding calls
Document service      PDF extraction, chunking, async ingestion
```

---

## Async document processing

Large uploads use asynchronous processing.

The upload request can return before ingestion is complete. The frontend polls the document status every few seconds until processing finishes.

```text
UPLOAD RECEIVED
   ↓
PROCESSING
   ↓
INGESTED
```

This prevents long-running PDF extraction and embedding from blocking the HTTP request.

---

## Persona and language options

DocGPT includes lightweight prompt customization.

Supported persona modes include:

```text
Analyst
Commercial Lead
Technical Lead
External Merchant
```

There is also a Spanish response toggle.

These features are implemented through additional prompt instructions, not through separate fine-tuned models.

---

## Testing

The repository includes backend tests for core service and integration behavior.

Run tests with:

```bash
./mvnw test
```

or:

```bash
mvn test
```

The current test suite is useful for validating core flows, but it should not be treated as full production-grade coverage.

---

## Limitations

DocGPT is a working portfolio RAG system, not a production document intelligence platform.

Current limitations:

* Character-based chunking, not semantic chunking
* No reranking
* No hybrid lexical + vector retrieval
* No evaluation harness for answer quality
* No citation-level answer verification
* No hallucination scoring
* No advanced document parsing beyond PDF text extraction
* No OCR for scanned PDFs
* No streaming responses unless added separately
* Local model quality depends on the Ollama model selected
* Default local model is lightweight and intended for demos
* Development JWT secret must be replaced before real deployment

---

## Security notes

The project includes user-based vector isolation through a mandatory `userId` filter in Qdrant searches.

However, for production use, additional hardening would be needed:

* Replace default JWT secrets
* Add stronger password policies
* Add rate limiting
* Add file size and file type enforcement
* Add virus scanning for uploads
* Add request validation and abuse protection
* Add observability and audit logging
* Add secure secret management
* Add HTTPS termination
* Add backup and migration strategy for PostgreSQL and Qdrant

---

## Future work

Potential improvements:

* Semantic chunking
* Hybrid search
* Reranking
* Better citation display
* Answer evaluation suite
* Streaming responses
* OCR support
* Multi-document comparison
* Document summarization
* Admin dashboard
* Better model selection
* Production secret management
* More comprehensive test coverage

---

## Project status

DocGPT is a complete local RAG demo with:

* React frontend
* Spring Boot backend
* PostgreSQL persistence
* Qdrant vector search
* Ollama local LLM integration
* Docker Compose deployment
* Authenticated document upload
* Multi-tenant retrieval filtering
* Document-grounded chat

It is best understood as a strong learning and portfolio project for full-stack RAG architecture, backend service design, and self-hosted LLM workflows.

---

## License

MIT
