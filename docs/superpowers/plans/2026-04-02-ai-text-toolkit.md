# AI Text Toolkit Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a Spring Boot demo app that lets users run AI text operations (summarize, extract topics, change tone) via HTMX UI, with full OTel trace/log/metric export to Databricks Unity Catalog.

**Architecture:** Single Spring Boot 4.0.4 app. Databricks Java SDK resolves credentials from `.databrickscfg` profile. Spring AI's OpenAI client calls Databricks FMAPI. Custom OTel exporter beans inject dynamic auth tokens for export to Databricks OTLP endpoints. HTMX + Thymeleaf for server-rendered UI.

**Tech Stack:** Java 25, Spring Boot 4.0.4, Spring AI 2.0.0-M3, HTMX 5.0.0, Databricks SDK for Java, OpenTelemetry (OTLP HTTP/protobuf)

---

## File Map

| File | Responsibility |
|------|---------------|
| `pom.xml` | Add Databricks SDK + Thymeleaf dependencies |
| `src/main/resources/application.properties` | All configuration (Databricks, Spring AI, OTel) |
| `src/main/java/.../config/DatabricksProperties.java` | `@ConfigurationProperties` for Databricks settings |
| `src/main/java/.../config/DatabricksConfig.java` | SDK WorkspaceClient + token resolution beans |
| `src/main/java/.../config/OtelExporterConfig.java` | Custom OTLP exporter beans with dynamic auth |
| `src/main/java/.../service/TextAiService.java` | ChatClient wrapper for 3 AI operations |
| `src/main/java/.../controller/HomeController.java` | GET / → index.html |
| `src/main/java/.../controller/TextOperationsController.java` | POST /api/* → HTMX fragments |
| `src/main/resources/prompts/summarize.st` | Summarize prompt template |
| `src/main/resources/prompts/extract.st` | Extract topics prompt template |
| `src/main/resources/prompts/tone.st` | Change tone prompt template |
| `src/main/resources/templates/index.html` | Main page (Thymeleaf + HTMX) |
| `src/main/resources/templates/fragments/result-card.html` | Result fragment for HTMX swap |
| `src/main/resources/static/css/style.css` | Polished custom CSS |

All Java files live under `com.databricks.jg.ai_tracing_otel`.

---

### Task 1: Add Dependencies to pom.xml

**Files:**
- Modify: `pom.xml`

- [ ] **Step 1: Add Databricks SDK and Thymeleaf dependencies**

Add these dependencies to the `<dependencies>` section in `pom.xml`:

```xml
<dependency>
    <groupId>com.databricks</groupId>
    <artifactId>databricks-sdk-java</artifactId>
    <version>0.103.0</version>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-thymeleaf</artifactId>
</dependency>
```

Note: `spring-boot-starter-webmvc`, `spring-boot-starter-actuator`, `spring-boot-starter-opentelemetry`, `htmx-spring-boot`, and `spring-ai-starter-model-openai` are already present.

- [ ] **Step 2: Verify build compiles**

Run: `./mvnw compile -q`
Expected: BUILD SUCCESS (no errors)

- [ ] **Step 3: Commit**

```bash
git add pom.xml
git commit -m "feat: add Databricks SDK and Thymeleaf dependencies"
```

---

### Task 2: Configure Application Properties

**Files:**
- Modify: `src/main/resources/application.properties`

- [ ] **Step 1: Write full application configuration**

Replace the content of `application.properties` with:

```properties
spring.application.name=ai-tracing-otel

# --- Databricks Connection ---
databricks.profile=DEFAULT
databricks.otel.catalog=main
databricks.otel.schema=default
databricks.otel.table-prefix=demo

# --- Spring AI (OpenAI-compatible → Databricks FMAPI) ---
# base-url and api-key are set programmatically by DatabricksConfig
spring.ai.openai.chat.options.model=databricks-meta-llama-3-3-70b-instruct
spring.ai.openai.chat.options.temperature=0.4

# Log prompts and completions in traces (demo only — disable in prod)
spring.ai.chat.observations.log-prompt=true
spring.ai.chat.observations.log-completion=true

# --- OTel Tracing ---
# Endpoints and headers are set programmatically by OtelExporterConfig
management.tracing.sampling.probability=1.0
management.opentelemetry.resource-attributes.service.name=${spring.application.name}

# --- Actuator ---
management.endpoints.web.exposure.include=health,info,metrics
```

- [ ] **Step 2: Commit**

```bash
git add src/main/resources/application.properties
git commit -m "feat: add application configuration for Databricks, Spring AI, and OTel"
```

---

### Task 3: Databricks SDK Integration

**Files:**
- Create: `src/main/java/com/databricks/jg/ai_tracing_otel/config/DatabricksProperties.java`
- Create: `src/main/java/com/databricks/jg/ai_tracing_otel/config/DatabricksConfig.java`

- [ ] **Step 1: Create DatabricksProperties**

Create `src/main/java/com/databricks/jg/ai_tracing_otel/config/DatabricksProperties.java`:

```java
package com.databricks.jg.ai_tracing_otel.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "databricks")
public record DatabricksProperties(
    String profile,
    OtelProperties otel
) {
    public record OtelProperties(
        String catalog,
        String schema,
        String tablePrefix
    ) {
        public String spansTable() {
            return catalog + "." + schema + "." + tablePrefix + "_otel_spans";
        }

        public String logsTable() {
            return catalog + "." + schema + "." + tablePrefix + "_otel_logs";
        }

        public String metricsTable() {
            return catalog + "." + schema + "." + tablePrefix + "_otel_metrics";
        }
    }
}
```

- [ ] **Step 2: Create DatabricksConfig**

Create `src/main/java/com/databricks/jg/ai_tracing_otel/config/DatabricksConfig.java`:

```java
package com.databricks.jg.ai_tracing_otel.config;

import com.databricks.sdk.core.DatabricksConfig;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DatabricksProperties.class)
public class DatabricksConfiguration {

    @Bean
    DatabricksConfig databricksConfig(DatabricksProperties properties) {
        var config = new DatabricksConfig()
            .setProfile(properties.profile());
        config.resolve();
        return config;
    }

    @Bean
    OpenAiChatModel chatModel(
            DatabricksConfig config,
            @Value("${spring.ai.openai.chat.options.model}") String model,
            @Value("${spring.ai.openai.chat.options.temperature}") double temperature) {
        Map<String, String> headers = config.authenticate();
        String token = headers.get("Authorization").replace("Bearer ", "");

        var openAiApi = OpenAiApi.builder()
            .apiKey(token)
            .baseUrl(config.getHost() + "/serving-endpoints")
            .build();

        var chatOptions = OpenAiChatOptions.builder()
            .model(model)
            .temperature(temperature)
            .build();

        return new OpenAiChatModel(openAiApi, chatOptions);
    }
}
```

- [ ] **Step 3: Verify build compiles**

Run: `./mvnw compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/databricks/jg/ai_tracing_otel/config/DatabricksProperties.java \
        src/main/java/com/databricks/jg/ai_tracing_otel/config/DatabricksConfig.java
git commit -m "feat: add Databricks SDK integration with profile-based auth"
```

---

### Task 4: OTel Exporter Configuration

**Files:**
- Create: `src/main/java/com/databricks/jg/ai_tracing_otel/config/OtelExporterConfig.java`

- [ ] **Step 1: Create custom OTel exporter config**

Create `src/main/java/com/databricks/jg/ai_tracing_otel/config/OtelExporterConfig.java`:

```java
package com.databricks.jg.ai_tracing_otel.config;

import com.databricks.sdk.core.DatabricksConfig;
import io.opentelemetry.exporter.otlp.http.logs.OtlpHttpLogRecordExporter;
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporter;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;
import java.util.function.Supplier;

@Configuration(proxyBeanMethods = false)
public class OtelExporterConfig {

    @Bean
    OtlpHttpSpanExporter otlpHttpSpanExporter(
            DatabricksConfig config,
            DatabricksProperties properties) {
        String endpoint = config.getHost() + "/api/2.0/otel/v1/traces";
        Supplier<Map<String, String>> headers = () -> {
            Map<String, String> auth = config.authenticate();
            return Map.of(
                "Authorization", auth.get("Authorization"),
                "X-Databricks-UC-Table-Name", properties.otel().spansTable()
            );
        };
        return OtlpHttpSpanExporter.builder()
            .setEndpoint(endpoint)
            .setHeaders(headers)
            .build();
    }

    @Bean
    OtlpHttpLogRecordExporter otlpHttpLogRecordExporter(
            DatabricksConfig config,
            DatabricksProperties properties) {
        String endpoint = config.getHost() + "/api/2.0/otel/v1/logs";
        Supplier<Map<String, String>> headers = () -> {
            Map<String, String> auth = config.authenticate();
            return Map.of(
                "Authorization", auth.get("Authorization"),
                "X-Databricks-UC-Table-Name", properties.otel().logsTable()
            );
        };
        return OtlpHttpLogRecordExporter.builder()
            .setEndpoint(endpoint)
            .setHeaders(headers)
            .build();
    }

    @Bean
    OtlpHttpMetricExporter otlpHttpMetricExporter(
            DatabricksConfig config,
            DatabricksProperties properties) {
        String endpoint = config.getHost() + "/api/2.0/otel/v1/metrics";
        Supplier<Map<String, String>> headers = () -> {
            Map<String, String> auth = config.authenticate();
            return Map.of(
                "Authorization", auth.get("Authorization"),
                "X-Databricks-UC-Table-Name", properties.otel().metricsTable()
            );
        };
        return OtlpHttpMetricExporter.builder()
            .setEndpoint(endpoint)
            .setHeaders(headers)
            .build();
    }
}
```

- [ ] **Step 2: Verify build compiles**

Run: `./mvnw compile -q`
Expected: BUILD SUCCESS

Note: The `OtlpHttpMetricExporter` class may come from a different package — if compile fails, check the import. The Micrometer OTLP registry handles metrics differently. If this doesn't compile, we may need to use `management.otlp.metrics.export.*` properties instead and drop the custom metrics exporter bean. Traces and logs exporters are the priority.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/databricks/jg/ai_tracing_otel/config/OtelExporterConfig.java
git commit -m "feat: add custom OTel exporters with Databricks auth headers"
```

---

### Task 5: Prompt Templates

**Files:**
- Create: `src/main/resources/prompts/summarize.st`
- Create: `src/main/resources/prompts/extract.st`
- Create: `src/main/resources/prompts/tone.st`

- [ ] **Step 1: Create the prompts directory and all three templates**

Create `src/main/resources/prompts/summarize.st`:

```
You are a concise text summarizer. Summarize the following text in 2-3 sentences,
capturing the key points. Be clear and direct.

Text to summarize:
{text}
```

Create `src/main/resources/prompts/extract.st`:

```
You are a text analyst. Extract the key topics, entities, and themes from the
following text. Return them as a bulleted list. Include people, organizations,
locations, and abstract concepts. Be specific.

Text to analyze:
{text}
```

Create `src/main/resources/prompts/tone.st`:

```
You are a professional writer. Rewrite the following text in a {tone} tone.
Preserve the meaning and key information, but adjust the style, vocabulary,
and sentence structure to match the requested tone.

Target tone: {tone}

Text to rewrite:
{text}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/resources/prompts/
git commit -m "feat: add prompt templates for summarize, extract, and tone operations"
```

---

### Task 6: TextAiService

**Files:**
- Create: `src/main/java/com/databricks/jg/ai_tracing_otel/service/TextAiService.java`

- [ ] **Step 1: Create the service**

Create `src/main/java/com/databricks/jg/ai_tracing_otel/service/TextAiService.java`:

```java
package com.databricks.jg.ai_tracing_otel.service;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

@Service
public class TextAiService {

    private final ChatClient chatClient;

    @Value("classpath:/prompts/summarize.st")
    private Resource summarizePrompt;

    @Value("classpath:/prompts/extract.st")
    private Resource extractPrompt;

    @Value("classpath:/prompts/tone.st")
    private Resource tonePrompt;

    public TextAiService(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    public record TextResult(String operation, String output, long durationMs) {}

    public TextResult summarize(String text) {
        long start = System.currentTimeMillis();
        String result = chatClient.prompt()
            .user(u -> u.text(summarizePrompt).param("text", text))
            .call()
            .content();
        return new TextResult("Summarize", result, System.currentTimeMillis() - start);
    }

    public TextResult extractTopics(String text) {
        long start = System.currentTimeMillis();
        String result = chatClient.prompt()
            .user(u -> u.text(extractPrompt).param("text", text))
            .call()
            .content();
        return new TextResult("Extract Topics", result, System.currentTimeMillis() - start);
    }

    public TextResult changeTone(String text, String tone) {
        long start = System.currentTimeMillis();
        String result = chatClient.prompt()
            .user(u -> u.text(tonePrompt).param("text", text).param("tone", tone))
            .call()
            .content();
        return new TextResult("Change Tone (" + tone + ")", result, System.currentTimeMillis() - start);
    }
}
```

- [ ] **Step 2: Verify build compiles**

Run: `./mvnw compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/databricks/jg/ai_tracing_otel/service/TextAiService.java
git commit -m "feat: add TextAiService with ChatClient-based AI operations"
```

---

### Task 7: Controllers

**Files:**
- Create: `src/main/java/com/databricks/jg/ai_tracing_otel/controller/HomeController.java`
- Create: `src/main/java/com/databricks/jg/ai_tracing_otel/controller/TextOperationsController.java`

- [ ] **Step 1: Create HomeController**

Create `src/main/java/com/databricks/jg/ai_tracing_otel/controller/HomeController.java`:

```java
package com.databricks.jg.ai_tracing_otel.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class HomeController {

    @GetMapping("/")
    public String home() {
        return "index";
    }
}
```

- [ ] **Step 2: Create TextOperationsController**

Create `src/main/java/com/databricks/jg/ai_tracing_otel/controller/TextOperationsController.java`:

```java
package com.databricks.jg.ai_tracing_otel.controller;

import com.databricks.jg.ai_tracing_otel.service.TextAiService;
import com.databricks.jg.ai_tracing_otel.service.TextAiService.TextResult;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
@RequestMapping("/api")
public class TextOperationsController {

    private final TextAiService textAiService;

    public TextOperationsController(TextAiService textAiService) {
        this.textAiService = textAiService;
    }

    @PostMapping("/summarize")
    public String summarize(@RequestParam String text, Model model) {
        TextResult result = textAiService.summarize(text);
        model.addAttribute("result", result);
        return "fragments/result-card";
    }

    @PostMapping("/extract")
    public String extract(@RequestParam String text, Model model) {
        TextResult result = textAiService.extractTopics(text);
        model.addAttribute("result", result);
        return "fragments/result-card";
    }

    @PostMapping("/tone")
    public String changeTone(
            @RequestParam String text,
            @RequestParam(defaultValue = "formal") String tone,
            Model model) {
        TextResult result = textAiService.changeTone(text, tone);
        model.addAttribute("result", result);
        return "fragments/result-card";
    }
}
```

- [ ] **Step 3: Verify build compiles**

Run: `./mvnw compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/databricks/jg/ai_tracing_otel/controller/
git commit -m "feat: add HomeController and TextOperationsController"
```

---

### Task 8: Thymeleaf Templates

**Files:**
- Create: `src/main/resources/templates/index.html`
- Create: `src/main/resources/templates/fragments/result-card.html`

- [ ] **Step 1: Create the result-card fragment**

Create `src/main/resources/templates/fragments/result-card.html`:

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<body>
<div class="result-card" th:fragment="~{}" >
    <div class="result-header">
        <span class="result-operation" th:text="${result.operation}">Operation</span>
        <span class="result-duration" th:text="${result.durationMs + 'ms'}">0ms</span>
    </div>
    <div class="result-body" th:text="${result.output}">
        Result text here...
    </div>
</div>
</body>
</html>
```

- [ ] **Step 2: Create the main index.html page**

Create `src/main/resources/templates/index.html`:

```html
<!DOCTYPE html>
<html lang="en" xmlns:th="http://www.thymeleaf.org">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>AI Text Toolkit</title>
    <link rel="stylesheet" th:href="@{/css/style.css}">
    <script src="https://unpkg.com/htmx.org@2.0.4"></script>
</head>
<body>
    <header>
        <h1>AI Text Toolkit</h1>
        <p class="subtitle">Powered by Databricks Foundation Model API &bull; Traced with OpenTelemetry</p>
    </header>

    <main>
        <section class="input-panel">
            <label for="text-input">Your Text</label>
            <textarea id="text-input" name="text" rows="12"
                      placeholder="Paste or type your text here..."></textarea>

            <div class="actions">
                <button class="btn btn-primary"
                        hx-post="/api/summarize"
                        hx-target="#summarize-result"
                        hx-include="#text-input"
                        hx-indicator="#summarize-loading">
                    Summarize
                </button>
                <button class="btn btn-primary"
                        hx-post="/api/extract"
                        hx-target="#extract-result"
                        hx-include="#text-input"
                        hx-indicator="#extract-loading">
                    Extract Topics
                </button>

                <div class="tone-group">
                    <select id="tone-select" name="tone" class="tone-select">
                        <option value="formal">Formal</option>
                        <option value="casual">Casual</option>
                    </select>
                    <button class="btn btn-primary"
                            hx-post="/api/tone"
                            hx-target="#tone-result"
                            hx-include="#text-input, #tone-select"
                            hx-indicator="#tone-loading">
                        Change Tone
                    </button>
                </div>
            </div>
        </section>

        <section class="results-panel">
            <div class="result-slot">
                <h3>Summary</h3>
                <div class="loading-indicator" id="summarize-loading">
                    <div class="spinner"></div>
                    <span>Generating summary...</span>
                </div>
                <div id="summarize-result"></div>
            </div>

            <div class="result-slot">
                <h3>Topics &amp; Entities</h3>
                <div class="loading-indicator" id="extract-loading">
                    <div class="spinner"></div>
                    <span>Extracting topics...</span>
                </div>
                <div id="extract-result"></div>
            </div>

            <div class="result-slot">
                <h3>Tone Rewrite</h3>
                <div class="loading-indicator" id="tone-loading">
                    <div class="spinner"></div>
                    <span>Rewriting tone...</span>
                </div>
                <div id="tone-result"></div>
            </div>
        </section>
    </main>

    <footer>
        <p>Demo: OpenTelemetry Ingestion to Databricks Unity Catalog</p>
    </footer>
</body>
</html>
```

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/templates/
git commit -m "feat: add Thymeleaf templates for main page and result fragments"
```

---

### Task 9: CSS Styling

**Files:**
- Create: `src/main/resources/static/css/style.css`

- [ ] **Step 1: Create polished CSS**

Create `src/main/resources/static/css/style.css`:

```css
/* === Reset & Base === */
*,
*::before,
*::after {
    margin: 0;
    padding: 0;
    box-sizing: border-box;
}

:root {
    --color-bg: #f8f9fb;
    --color-surface: #ffffff;
    --color-text: #1a1a2e;
    --color-text-secondary: #6b7280;
    --color-primary: #e8553d;
    --color-primary-hover: #d14832;
    --color-border: #e5e7eb;
    --color-accent-bg: #fef3f0;
    --shadow-sm: 0 1px 2px rgba(0, 0, 0, 0.05);
    --shadow-md: 0 4px 12px rgba(0, 0, 0, 0.08);
    --shadow-lg: 0 8px 24px rgba(0, 0, 0, 0.12);
    --radius: 12px;
    --radius-sm: 8px;
    --font-sans: 'Inter', -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
    --font-mono: 'JetBrains Mono', 'Fira Code', monospace;
    --transition: 200ms ease;
}

body {
    font-family: var(--font-sans);
    background: var(--color-bg);
    color: var(--color-text);
    line-height: 1.6;
    min-height: 100vh;
}

/* === Header === */
header {
    text-align: center;
    padding: 3rem 1rem 2rem;
}

header h1 {
    font-size: 2rem;
    font-weight: 700;
    letter-spacing: -0.02em;
}

.subtitle {
    color: var(--color-text-secondary);
    font-size: 0.95rem;
    margin-top: 0.5rem;
}

/* === Layout === */
main {
    max-width: 1200px;
    margin: 0 auto;
    padding: 0 1.5rem 3rem;
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 2rem;
    align-items: start;
}

@media (max-width: 800px) {
    main {
        grid-template-columns: 1fr;
    }
}

/* === Input Panel === */
.input-panel {
    background: var(--color-surface);
    border-radius: var(--radius);
    padding: 1.75rem;
    box-shadow: var(--shadow-md);
    position: sticky;
    top: 1.5rem;
}

.input-panel label {
    display: block;
    font-weight: 600;
    font-size: 0.9rem;
    margin-bottom: 0.5rem;
    color: var(--color-text-secondary);
    text-transform: uppercase;
    letter-spacing: 0.05em;
}

textarea {
    width: 100%;
    border: 1.5px solid var(--color-border);
    border-radius: var(--radius-sm);
    padding: 1rem;
    font-family: var(--font-sans);
    font-size: 0.95rem;
    line-height: 1.6;
    resize: vertical;
    transition: border-color var(--transition);
    background: var(--color-bg);
}

textarea:focus {
    outline: none;
    border-color: var(--color-primary);
    box-shadow: 0 0 0 3px rgba(232, 85, 61, 0.12);
}

/* === Action Buttons === */
.actions {
    display: flex;
    flex-wrap: wrap;
    gap: 0.75rem;
    margin-top: 1.25rem;
}

.btn {
    display: inline-flex;
    align-items: center;
    padding: 0.65rem 1.25rem;
    border: none;
    border-radius: var(--radius-sm);
    font-family: var(--font-sans);
    font-size: 0.9rem;
    font-weight: 600;
    cursor: pointer;
    transition: all var(--transition);
}

.btn-primary {
    background: var(--color-primary);
    color: white;
}

.btn-primary:hover {
    background: var(--color-primary-hover);
    transform: translateY(-1px);
    box-shadow: var(--shadow-sm);
}

.btn-primary:active {
    transform: translateY(0);
}

.btn.htmx-request {
    opacity: 0.7;
    pointer-events: none;
}

.tone-group {
    display: flex;
    gap: 0.5rem;
    align-items: center;
}

.tone-select {
    padding: 0.65rem 0.75rem;
    border: 1.5px solid var(--color-border);
    border-radius: var(--radius-sm);
    font-family: var(--font-sans);
    font-size: 0.9rem;
    background: var(--color-surface);
    cursor: pointer;
}

/* === Results Panel === */
.results-panel {
    display: flex;
    flex-direction: column;
    gap: 1.5rem;
}

.result-slot h3 {
    font-size: 0.85rem;
    font-weight: 600;
    color: var(--color-text-secondary);
    text-transform: uppercase;
    letter-spacing: 0.05em;
    margin-bottom: 0.75rem;
}

/* === Result Card === */
.result-card {
    background: var(--color-surface);
    border-radius: var(--radius);
    padding: 1.5rem;
    box-shadow: var(--shadow-md);
    animation: fadeIn 300ms ease;
}

.result-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-bottom: 1rem;
    padding-bottom: 0.75rem;
    border-bottom: 1px solid var(--color-border);
}

.result-operation {
    font-weight: 600;
    font-size: 0.95rem;
}

.result-duration {
    font-family: var(--font-mono);
    font-size: 0.8rem;
    color: var(--color-primary);
    background: var(--color-accent-bg);
    padding: 0.25rem 0.6rem;
    border-radius: 999px;
    font-weight: 600;
}

.result-body {
    font-size: 0.95rem;
    line-height: 1.7;
    white-space: pre-wrap;
}

/* === Loading Indicator === */
.loading-indicator {
    display: none;
    align-items: center;
    gap: 0.75rem;
    padding: 1.25rem;
    color: var(--color-text-secondary);
    font-size: 0.9rem;
}

.htmx-request .loading-indicator,
.htmx-request.loading-indicator {
    display: flex;
}

.spinner {
    width: 20px;
    height: 20px;
    border: 2.5px solid var(--color-border);
    border-top-color: var(--color-primary);
    border-radius: 50%;
    animation: spin 0.7s linear infinite;
}

/* === Footer === */
footer {
    text-align: center;
    padding: 2rem 1rem;
    color: var(--color-text-secondary);
    font-size: 0.85rem;
}

/* === Animations === */
@keyframes fadeIn {
    from {
        opacity: 0;
        transform: translateY(8px);
    }
    to {
        opacity: 1;
        transform: translateY(0);
    }
}

@keyframes spin {
    to {
        transform: rotate(360deg);
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/resources/static/css/style.css
git commit -m "feat: add polished CSS with Databricks-inspired styling"
```

---

### Task 10: Final Wiring and Smoke Test

**Files:**
- Modify: `src/main/java/com/databricks/jg/ai_tracing_otel/AiTracingOtelApplication.java` (if needed)

- [ ] **Step 1: Verify the full build compiles**

Run: `./mvnw compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 2: Check for any missing imports or wiring issues**

Run: `./mvnw spring-boot:run` (requires a valid `.databrickscfg` profile)

If no Databricks profile is available for local testing, verify the build is clean:

Run: `./mvnw verify -DskipTests -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Review all files for consistency**

Verify:
- `DatabricksProperties` field names match `application.properties` keys
- `TextOperationsController` parameter names (`text`, `tone`) match HTMX `hx-include` element names
- Prompt template placeholders (`{text}`, `{tone}`) match `.param()` calls in `TextAiService`
- Thymeleaf fragment path in controller (`fragments/result-card`) matches the file location
- HTMX `hx-post` paths match controller `@PostMapping` paths

- [ ] **Step 4: Commit any fixes**

```bash
git add -A
git commit -m "fix: resolve any wiring issues from integration review"
```

---

### Task 11: README with Setup Instructions

**Files:**
- Create: `README.md`

- [ ] **Step 1: Write README**

Create `README.md` with:

1. **What this is** — one paragraph description
2. **Prerequisites** — Java 25, Maven, Databricks workspace with UC, `.databrickscfg` profile, model serving endpoint
3. **Setup** — SQL DDL statements to create the three OTel tables (copy from the Google Doc appendix with placeholder variables)
4. **Configuration** — which properties to set in `application.properties` (profile, catalog, schema, table prefix, model name)
5. **Run** — `./mvnw spring-boot:run` then open `http://localhost:8080`
6. **Verify traces** — SQL query to run in Databricks to see ingested spans:
   ```sql
   SELECT trace_id, name, start_time_unix_nano, end_time_unix_nano, attributes
   FROM <catalog>.<schema>.<prefix>_otel_spans
   ORDER BY start_time_unix_nano DESC
   LIMIT 20;
   ```

- [ ] **Step 2: Commit**

```bash
git add README.md
git commit -m "docs: add README with setup and run instructions"
```
