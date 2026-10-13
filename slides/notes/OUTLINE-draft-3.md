# Outline

## Structure

* Introduction
* Yesterday
* Today
* Tomorrow
* Conclusion

### Introduction

- Introduce the idea and tell the story
- Show the benchmark results of the LLM CLI bake-off from the abstract (small tools in multiple languages with comparisons)

### Yesterday

- Java "scripting" before JEP 330 (was not a thing?)
- The journey to Java "scripts" (JEP 330, JEP 458, JEP 512, JEP 511)
- Live demo of the Java "scripting" JEPs in action
- Question: Do these advancements get us all the way to Java "scripts"?

### Today

- Live demo various Java gaps and quirks (dependency management, assertions, CLI argument parsing, help text, HTTP clients, JSON parsing/formatting, CSV parsing/formatting)
- Introduce a real-world tool for comparison (generated with multiple prompts, models and languages)
- Compare prompts for generating the tool and briefly discuss steering the agent, model selection, and prompt engineering
- Live demo of the Java versions of the tool with different launch modes
- Discuss costs and performance tradeoffs (startup time, AOT cache size, stale cache bug)
- Compare with non-Java versions and provide a scorecard to honestly break down the results
- Question: How does Java stack up?
- Question: What can we do to improve these results?

**Notes:**

- Create a single cohesive problem: an idea for an interesting CLI tool to be generated
- The tool should exercise the various Java gaps and quirks and have non-trivial startup and runtime performance considerations
- Iterate on different prompts, models, and languages
- Different prompt examples may include:
  - Create a script to do X
  - Create a script to do X in Go
  - Create a script to do X in JavaScript
  - Create a script to do X in Java
  - Create a script to do X in Java with JBang
  - Create a script to do X in Java with no dependencies
  - Create a script to do X in Java with no build
  - Create a script to do X in Java with full Maven/Gradle build
  - Create a script to do X in Java with improvements in JDK 25
  - Create a script to do X in Java with compact source and module imports
  - Create a script to do X in Java with <detailed list of requirements>
  - Goal: I want to do X
  - Goal: I want to do X with a single command: <example>
  - Goal: I want a single command (<example command>) to produce the following output (<example output>)

### Tomorrow

- Show some quality of life enhancements such as generated shims, functions and helper scripts that remove dependencies, improve startup time, perform on-demand AOT caching, and provide better launch consistency
- Live demo of the Java tool orchestrated via helper scripts (e.g. a launch harness tool) for optimized zero-setup program execution
- Discuss wish list (JEP 540, AOT for source-launch, fail fast on AOT cache mismatch, smaller AOT cache for small programs, zero-tooling dependencies)
- Question: What's on your wish list?

### Conclusion

- Recap the scorecard: what works today, what it costs, what's still missing
- Answer the opening question: is "AI writes Python" a fact about AI or a fact about Java?
- Leave the audience with something to try this week (e.g. `java Tool.java`, a compact source file rewrite, a generated shim for faster startup)
- Call to action: try it on a real tool, share what you find, and tell the JDK community what's on your wish list
- Questions