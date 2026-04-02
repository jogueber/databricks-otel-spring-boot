# AI Text Toolkit — Design Spec

## Overview

A Spring Boot demo application showcasing two Databricks features:

1. **OpenTelemetry trace/log/metric ingestion** into Unity Catalog Delta tables
2. **Databricks Foundation Model API** consumed via Spring AI's OpenAI-compatible client

The app is an **AI-powered text toolkit** — users paste text and run operations (summarize, extract topics, change tone). Every interaction is traced end-to-end and exported to Databricks via OTLP.

**Target audience:** Customers/prospects and developers looking for a clean reference implementation.

## Architecture

```
Browser (HTMX)
    │
    ▼
Spring MVC Controllers (auto-instrumented spans)
    │
    ▼
TextAiService → Spring AI ChatClient → Databricks FMAPI (OpenAI-compatible)
    │
    ▼
OTel auto-instrumentation + manual spans
    │
    ▼
OTLP HTTP/protobuf exporter
    │
    ▼
Databricks OTel Collector → Unity Catalog Delta Tables
                             (_otel_spans, _otel_logs, _otel_metrics)
```

**Stateless, no database, no auth, no sessions.**

## Technology Stack

| Component | Technology | Version |
|-----------|-----------|---------|
| Runtime | Java | 25 |
| Framework | Spring Boot | 4.0.4 |
| AI Client | Spring AI (OpenAI starter) | 2.0.0-M3 |
| Frontend | HTMX + Thymeleaf | htmx-spring-boot 5.0.0 |
| Observability | spring-boot-starter-opentelemetry | (managed by Boot) |
| Auth/Token | Databricks SDK for Java | (resolve from .databrickscfg profile) |
| Build | Maven | (wrapper included) |

## Configuration Strategy

### Databricks Credentials

Use the Databricks SDK for Java to resolve credentials from a `.databrickscfg` profile. The SDK handles PAT tokens, OAuth, and other auth methods transparently. The resolved token is used for:

- Spring AI's OpenAI client (`Authorization: Bearer <token>`)
- OTel exporter headers (`Authorization: Bearer <token>`)

Configuration in `application.properties`:

```properties
# Databricks connection
databricks.profile=DEFAULT
databricks.workspace-url=https://<workspace>.databricks.com

# Spring AI pointed at Databricks FMAPI
spring.ai.openai.base-url=${databricks.workspace-url}/serving-endpoints
spring.ai.openai.api-key=${resolved-at-runtime}
spring.ai.openai.chat.options.model=<model-name>

# OTel export to Databricks
databricks.otel.catalog=<catalog>
databricks.otel.schema=<schema>
databricks.otel.table-prefix=<prefix>
```

All Databricks-specific values are externalized — the user provides workspace URL, profile name, catalog/schema/table prefix, and model name.

### OTel Export

Three signal types, each targeting a separate Delta table:

| Signal | Endpoint | Table |
|--------|----------|-------|
| Traces | `https://<workspace>/api/2.0/otel/v1/traces` | `<catalog>.<schema>.<prefix>_otel_spans` |
| Logs | `https://<workspace>/api/2.0/otel/v1/logs` | `<catalog>.<schema>.<prefix>_otel_logs` |
| Metrics | `https://<workspace>/api/2.0/otel/v1/metrics` | `<catalog>.<schema>.<prefix>_otel_metrics` |

Protocol: `http/protobuf` (required by Databricks).

Custom exporter beans inject the Databricks SDK-resolved token into the `Authorization` header and the UC table name into the `X-Databricks-UC-Table-Name` header at runtime.

## UI Design

### Layout

Single page, two-panel layout:

- **Left panel:** Text input area (`<textarea>`) + three action buttons (Summarize, Extract Topics, Change Tone) + tone selector (formal/casual)
- **Right panel:** Result cards, one per operation, appearing independently

### HTMX Interaction

Each button uses HTMX attributes:

```html
<button hx-post="/api/summarize"
        hx-target="#summarize-result"
        hx-include="#text-input"
        hx-indicator="#summarize-loading">
  Summarize
</button>
```

- Buttons fire independently — all three can run in parallel
- Server returns Thymeleaf HTML fragments (not JSON)
- HTMX swaps fragments into target divs
- Loading indicators shown via HTMX's built-in CSS class hooks

### Styling

Custom CSS — no framework. Design goals:

- Clean, modern look with professional typography
- Subtle shadows, rounded corners, smooth transitions
- Loading spinners/skeletons during HTMX requests
- Responsive layout suitable for demo screens and projectors
- Consistent color palette (neutral base with accent color for actions)
- Result cards with operation name, output text, and latency badge

## API Endpoints

| Method | Path | Description | HTMX Response |
|--------|------|-------------|---------------|
| GET | `/` | Main page | Full HTML (Thymeleaf template) |
| POST | `/api/summarize` | Summarize input text | HTML fragment (result card) |
| POST | `/api/extract` | Extract key topics/entities | HTML fragment (result card) |
| POST | `/api/tone` | Rewrite in formal/casual tone | HTML fragment (result card) |

All POST endpoints accept form-encoded body with `text` field (and `tone` for `/api/tone`).

## Project Structure

```
src/main/java/com/databricks/jg/ai_tracing_otel/
├── AiTracingOtelApplication.java
├── config/
│   ├── DatabricksConfig.java              # SDK profile resolution, token management
│   ├── AiClientConfig.java                # Spring AI ChatClient bean
│   └── OtelExporterConfig.java            # Custom OTel exporter beans with dynamic token
├── controller/
│   ├── HomeController.java                # GET / → index.html
│   └── TextOperationsController.java      # POST /api/* → HTML fragments
└── service/
    └── TextAiService.java                 # ChatClient calls with prompt templates

src/main/resources/
├── application.properties
├── prompts/
│   ├── summarize.st
│   ├── extract.st
│   └── tone.st
├── templates/
│   ├── index.html
│   └── fragments/
│       ├── result-card.html
│       └── loading.html
└── static/
    └── css/
        └── style.css
```

## Service Layer

`TextAiService` wraps Spring AI's `ChatClient`:

```java
public record TextResult(String operation, String output, long durationMs) {}
```

Three methods, each loading a prompt template from `resources/prompts/`:

- `summarize(String text)` → TextResult
- `extractTopics(String text)` → TextResult
- `changeTone(String text, String tone)` → TextResult

Each method measures wall-clock duration for the latency badge.

## OTel Observability Scope

### Auto-instrumented (via spring-boot-starter-opentelemetry)

- HTTP server spans (full request lifecycle)
- Spring MVC controller spans

### Custom/enhanced

- Spring AI client call spans (if not auto-instrumented by the starter)
- Custom span attributes: operation type, text length, model name

### Trace flow per request

```
HTTP POST /api/summarize (server span)
  └── TextAiService.summarize (custom span)
       └── ChatClient.call → Databricks FMAPI (client span)
```

This produces a 2-3 level trace tree per operation — enough to be interesting in Unity Catalog without being artificially deep.

## Prerequisites (User Responsibilities)

Before running the app, the user must:

1. Have a Databricks workspace with Unity Catalog enabled
2. Create a catalog and schema
3. Run the SQL DDL statements (provided in README) to create the three OTel tables
4. Have a model serving endpoint active on the workspace
5. Have a `.databrickscfg` profile configured with valid credentials
6. Grant `USE_CATALOG`, `USE_SCHEMA`, `MODIFY`, `SELECT` on the tables

## What This Demo Does NOT Include

- Authentication/authorization for the web UI
- Persistent storage or database
- User sessions
- Multiple pages or complex routing
- CSS framework (Tailwind, Bootstrap, etc.)
- JavaScript beyond HTMX
- Table creation automation
- Unit/integration tests (skeleton only)
