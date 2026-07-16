# Agents

Forage creates AI agents with configurable chat models, memory providers, and guardrails for LangChain4j integration.

## Quick Start

```properties
forage.myAgent.agent.model.kind=ollama
forage.myAgent.agent.model.name=granite4:3b
forage.myAgent.agent.base.url=http://localhost:11434
forage.myAgent.agent.features=memory
forage.myAgent.agent.memory.kind=message-window
forage.myAgent.agent.memory.max.messages=20
```

```yaml
- to:
    uri: langchain4j-agent:myAgent
    parameters:
      agent: "#myAgent"
```

## Properties

{{ forage_properties("Agent") }}

## Available Chat Models

{{ forage_beans_table("Agent", "Chat Model") }}

## Available Memory Providers

{{ forage_beans_table("Agent", "Memory") }}

## Guardrails

Guardrails validate agent inputs and outputs (e.g., PII detection, keyword filtering). They must be **explicitly enabled** — adding a guardrail jar to the classpath is not enough.

### Configuration

List guardrails by their `@ForageBean` value (comma-separated):

```properties
forage.myAgent.agent.guardrails.input=pii-detector,keyword-filter
forage.myAgent.agent.guardrails.output=pii-redactor
```

For `MultiAgentFactory` (shared across agents):

```properties
forage.guardrails.input=pii-detector
forage.guardrails.output=pii-redactor
```

!!! warning "Fail-closed semantics"
    If a selected guardrail cannot be created (missing dependency, misconfiguration), the application
    fails at startup with a descriptive error. Guardrails are security controls — they are never
    silently skipped.

### Available Input Guardrails

{{ forage_beans_table("Agent", "Input Guardrail") }}

### Available Output Guardrails

{{ forage_beans_table("Agent", "Output Guardrail") }}

## Multimodal Content

Agents support multimodal inputs (images, PDFs) in both memory and memoryless modes. When memory is enabled, multimodal content is preserved alongside text in the conversation history.

## Memory Isolation

Each agent bean gets its own isolated memory store. Two agents using the same `memory.kind` do not share conversation history — even if they use the same `memory.kind` and `memory.max.messages` values.

## Provider Selection

When multiple providers of the same type are on the classpath, selection is deterministic: providers are matched by their `@ForageBean` value against the `model.kind` (or equivalent) property. If no match is found, Forage fails fast with an error listing available providers.
