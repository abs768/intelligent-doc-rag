# DocGPT - Quick Start Guide

## Prerequisites
- Docker Desktop installed and RUNNING
- 8GB+ RAM available
- 10GB+ disk space

## Setup (5 minutes)

### 1. Start Docker Desktop
**Mac:** Open Docker app from Applications  
**Windows:** Start Docker Desktop from Start menu

Wait for Docker to fully start (whale icon steady, not animated)

### 2. Clone This Project
```bash
git clone https://github.com/abs768/intelligent-doc-rag.git
cd intelligent-doc-rag
```

### 3. Start All Services
```bash
docker-compose up -d
```

This starts:
- PostgreSQL (database)
- Qdrant (vector DB)
- Ollama (AI models)
- Backend API
- Frontend web app

### 4. Download AI Models (REQUIRED - only once)

**LLM Model (~1.3GB):**
```bash
docker exec -it docgpt-ollama ollama pull llama3.2:1b
```

**Embedding Model (274MB):**
```bash
docker exec -it docgpt-ollama ollama pull nomic-embed-text
```

This takes 5-10 minutes depending on internet speed.

### 5. Verify Everything is Running

```bash
docker ps
```

You should see 5 containers:
- docgpt-postgres
- docgpt-qdrant
- docgpt-ollama
- docgpt-backend
- docgpt-frontend

### 6. Open The App

Go to: **http://localhost:3000**

## First Use

1. **Register** - Click "Register" tab, create account
2. **Upload Document** - Choose a .txt or .pdf file, click Upload
3. **Wait 10-30 seconds** for embedding to complete
4. **Click the document** in sidebar (turns orange when selected)
5. **Ask a question** about the document

## Common Issues

### "Upload failed"
- Make sure Docker is running: `docker ps`
- Check backend logs: `docker logs docgpt-backend --tail 50`
- Restart backend: `docker-compose restart backend`

### "I don't have information..."
- Did you **click the document** to select it? (should be orange)
- Did you wait 10-30 seconds after upload for embedding?
- Check console (F12 → Console tab) for errors

### Port already in use
```bash
# Kill process on port 3000
lsof -ti:3000 | xargs kill -9

# Or use different port
docker-compose down
# Edit docker-compose.yml, change "3000:80" to "3001:80"
docker-compose up -d
```

### Docker not found
- Start Docker Desktop application
- Wait for it to fully start
- Try `docker ps` again

## Stop Everything

```bash
docker-compose down
```

## Delete All Data (reset)

```bash
docker-compose down -v
```

## Need Help?

1. Check logs: `docker-compose logs -f`
2. Restart: `docker-compose restart`
3. Nuclear option: `docker-compose down -v && docker-compose up -d`

Then pull models again.
