# Deployment Guide for Resume

## Reality Check: Deploying AI Apps with Local LLMs

**Problem:** Ollama requires 4-8GB RAM. Free hosting (Render, Railway, Vercel) don't support this.

**Solution:** Professional approaches that actual SDE candidates use for ML projects.

---

## ✅ Option 1: Demo Video + GitHub (RECOMMENDED - What 80% Do)

### What You Need:
1. **GitHub repo** (public) ✅ Done
2. **2-minute demo video**
3. **Optional:** Deploy frontend-only

### Steps:

#### 1. Record Demo Video (Loom.com - Free)

1. Go to [loom.com](https://www.loom.com) → Sign up
2. Install desktop app
3. Record (2 minutes):
   - **0:00-0:15** - "DocGPT - RAG system with Spring Boot/React"
   - **0:15-0:45** - Register → Upload document → Processing
   - **0:45-1:30** - Select document → Ask questions → Show answers
   - **1:30-2:00** - Quick code (show ChatService.java RAG logic)

4. Get link: `https://www.loom.com/share/abc123`

#### 2. Update README

Add to top of README.md:

```markdown
## 🎥 Live Demo

**[▶️ Watch 2-Minute Demo](https://www.loom.com/share/your-video-id)**

*The full application (including LLM) runs locally via Docker. Demo video shows complete functionality.*
```

#### 3. Push to GitHub

```bash
cd docgpt-complete
git init
git add .
git commit -m "RAG document Q&A system"
git remote add origin https://github.com/yourusername/docgpt.git
git branch -M main
git push -u origin main
```

#### 4. (Optional) Deploy Frontend Only

```bash
cd frontend
npm run build
npx vercel --prod
# Get: https://docgpt-yourname.vercel.app
```

Add to README:
```markdown
**Frontend Demo:** https://docgpt-yourname.vercel.app  
*(Backend runs locally)*
```

### Resume Bullet:

```
DocGPT - RAG Document Q&A | Spring Boot, React, Qdrant, Ollama
• Built full-stack RAG system with 300ms query latency using 768-dim vector embeddings
• Engineered microservices backend with JWT auth and user-scoped data isolation
• Dockerized 5-service architecture (API, database, vector DB, LLM, frontend)
[GitHub] [Demo Video]
```

---

## ✅ Option 2: Deploy with Cloud API (Truly Live)

**Replace Ollama → OpenAI API = Deployable everywhere**

### Why Better:
- ✅ Actually deployed & always-on
- ✅ Fast (cloud GPUs)
- ✅ Shows cloud architecture knowledge

### Cost:
- **OpenAI:** ~$0.01/conversation
- **Demo usage:** $2-5/month

### Changes Needed:

**Add to pom.xml:**
```xml
<dependency>
    <groupId>com.theokanning.openai-gpt3-java</groupId>
    <artifactId>service</artifactId>
    <version>0.18.2</version>
</dependency>
```

**Replace OllamaService with OpenAIService:**
```java
@Service
public class OpenAIService {
    private final OpenAiService openAi;
    
    public OpenAIService(@Value("${openai.key}") String key) {
        this.openAi = new OpenAiService(key);
    }
    
    public String generate(String prompt) {
        var request = CompletionRequest.builder()
            .model("gpt-3.5-turbo-instruct")
            .prompt(prompt)
            .maxTokens(500)
            .build();
        return openAi.createCompletion(request)
            .getChoices().get(0).getText();
    }
}
```

**Deploy to Render.com:**
1. Push to GitHub
2. render.com → Sign up → New Web Service
3. Connect repo → Deploy
4. Add env var: `OPENAI_API_KEY`

**Live URL:** `https://docgpt-yourname.onrender.com`

---

## 🎯 What Interviewers Care About

**They DON'T care:**
- If it's deployed right this second

**They DO care:**
- ✅ Can you explain architecture?
- ✅ Why these tech choices?
- ✅ What tradeoffs exist?
- ✅ How would you scale it?

**Demo video + GitHub = Perfect for this**

---

## 📋 Quick Action Plan (1 hour)

**Minimum for resume-ready project:**

1. **Push to GitHub** (5 min)
2. **Record demo** (15 min on Loom)
3. **Update README** with video link (5 min)
4. **Deploy frontend** to Vercel (5 min)
5. **Write resume bullets** (10 min)

**Total: ~40 minutes**

---

## 🎬 Interview Response

**"Is this deployed?"**

> "The full system with local LLM runs via Docker Compose - here's a demo video. For production, I'd migrate to a cloud API like OpenAI or Anthropic. I researched the tradeoffs - would you like me to walk through the architecture or deployment options?"

**This shows:**
- ✅ Technical depth
- ✅ Cost awareness
- ✅ Deployment knowledge
- ✅ Practical thinking

---

**Bottom line:** Demo video + GitHub repo + good README = Resume-worthy project. Most SDE candidates with ML projects do exactly this.
