---
layout: center
class: text-center
---

# When a tree falls in the forest

<div v-click class="text-2xl pt-6">
...and nobody is around to hear it
</div>

<div v-click class="text-3xl pt-6">
Does it make a sound?
</div>

<!--
You're all familiar with this classic question, right?
-->

---
layout: center
class: text-center
---

# When you ask AI to build a CLI...

<div v-click class="text-2xl pt-6">
...and it writes Python
</div>

<div v-click class="text-3xl pt-6 op-80">
Is that a statement about AI?
</div>

<div v-click class="text-3xl pt-6 op-80">
Or about Java?
</div>

<!--
The hook. Let the first line sit, then [click] Python, then [click] the questions.
-->

---

# The story so far...

<v-click>

- I was building small CLI tools for coding agents
- One command to replace a flurry of `ls`, `find` and `grep`
- I wanted fewer turns, tokens, and generated one-off scripts
- I also wanted accurate, repeatable results

</v-click>

<v-clicks>

- These tools came back in Python. **Every time.**
- And they were pretty fast

</v-clicks>

<!--
Let me tell you the story behind this talk, and how I got here.
-->

---

# What about Java?

<div v-click class="pt-6 text-xl op-80">
Let's get scientific!
</div>

<div class="grid grid-cols-4 gap-5 pt-10">
  <div v-click class="stat"><div class="n">4</div><div>CLI tools</div></div>
  <div v-click class="stat"><div class="n">3</div><div>models</div></div>
  <div v-click class="stat"><div class="n">2</div><div>prompts</div></div>
  <div v-click class="stat"><div class="n">{{ intro.runs.measured }}</div><div>runs</div></div>
</div>

<div v-click class="pt-10 text-xl op-80">
Which language? How fast is it? Does it work? What does it cost?
</div>

<Status />

<style>
.stat { @apply rounded-lg border border-gray-400/30 p-6 text-center; }
.stat .n { @apply text-5xl font-bold pb-2; }
</style>

<script setup>
import { intro } from '../data/scorecard'
</script>

<!--
I asked myself, "What about Java?" We use Java for everything else. Why not for CLI tools?

I queued up an experiment.

- Tasks: 4 CLI tools
- Models: Haiku, Sonnet, Opus
- Prompt #1: "Build the `<cli>` command-line tool described in the spec."
- Prompt #2: +"Write it in Java."
-->

---

# My predictions

<VerdictCard v-click :hypotheses="[1]" />

<LanguageBar v-click :items="intro.default.languages" :always="['java']" />

<Status />

<script setup>
import { intro } from '../data/scorecard'
</script>

<!--
My first prediction was...

- The results were not totally surprising. It backs my personal experience with hard data.
- But 36 out of 36 runs [click] is 100% Python.
-->

---

# Prompt engineering

<div v-click class="prompt">Build the <code>&lt;cli&gt;</code> command-line tool described in SPEC.md. <b>Write it in Java.</b></div>

<!--
<div class="grid grid-cols-2 gap-8 pt-10">
  <div v-click class="stat big"><div class="n">{{ intro.java.java }}/{{ intro.java.n }}</div><div>wrote Java</div></div>
  <div v-click class="stat big"><div class="n">{{ intro.java.pass }}/{{ intro.java.java }}</div><div>pass the tests</div></div>
</div>
-->

<Status />

<script setup>
import { intro } from '../data/scorecard'
</script>

<style>
.prompt { @apply mt-4 rounded-md px-4 py-3 font-mono text-sm bg-gray-400/10 border-l-4 border-gray-400/50; }
.stat { @apply rounded-lg border border-gray-400/30 p-6 text-center; }
.stat .n { @apply text-6xl font-bold pb-2; color: #c2410c; }
html.dark .stat .n { color: #fb923c; }
</style>

---

# My predictions

<VerdictCard :hypotheses="[2, 3]" />

<Status />

<!--
- The second prediction is fairly straight forward.
- The third prediction is where it gets really interesting.
  - Distinct Java program styles (below)
  - Programs using the most common style [click]

Notes:
- Hypothesis 2. "Pass" means every conformance case for the task passes.
- Hypothesis 3 "distinct styles" counts (build, launch) combinations.
-->

---

# Build and launch styles

The same prompt, {{ intro.java.java }} times, {{ intro.java.combinations.length }} different ways to build and run them

<div class="pt-5">
<CountBars :items="intro.java.combinations" :total="intro.java.java" />
</div>

<div class="grid grid-cols-3 gap-4 pt-8 text-center">
  <div v-click class="fact"><b>{{ count('wrapper_dim', 'wrapper script') }} of {{ intro.java.java }}</b><br>wrote a shell script just to launch it</div>
  <div v-click class="fact"><b>{{ count('source', 'compact source') }} of {{ intro.java.java }}</b><br>used compact source files</div>
  <div v-click class="fact"><b>{{ count('launch', 'source') }} of {{ intro.java.java }}</b><br>used source launch</div>
</div>

<Status />

<script setup>
import { intro } from '../data/scorecard';
const count = (dim, name) => intro.java.dims[dim]?.find(d => d.name === name)?.count ?? 0;
</script>

<style>
.fact { @apply rounded-lg border border-gray-400/30 p-3 text-sm; }
.fact b { @apply text-2xl; }
</style>

<!--
22 of 48 used the same build/launch style. Observations: [click]
- shell scripts? quite a few [click]
- compact source? nope [click]
- source launch? nope [click]

**Note:** Both of these were available:
- JEP 511 module imports
- JEP 512 compact source
-->

---

# My predictions

<VerdictCard :hypotheses="[4]" />

<Status />

<!--
- My last prediction is a little depressing. Java loses...
- Why does performance matter for CLI tools? It doesn't always...
- Apparently, it costs more tokens to avoid dependencies.
- Interestingly, no runs used any. So that wasn't testable.
- And optimizing should help. How much is the question.
-->

---
layout: center
class: text-center
---

# The scorecard

<Scorecard :rows="introRows" stepped />

<script setup>
import { introRows } from '../data/scorecard'
</script>

<!--
Here's the scorecard I promised in the abstract. I also promised that it would be honest. [run these quickly] [click]

- Startup doesn't always matter [click]
- Optimized programs are in the ballpark [click]
- It costs 80% to avoid dependencies [click]
- We know that typical Java projects cost more disk space [click]
- Of course Go and Java need build steps. [click]
- But I don't know why a launcher script is required. `java -jar`?
-->

---

# What would it take for Java to be a real answer?

<div class="flex justify-center gap-10 pt-10 text-xl">
  <div v-click>Yesterday<div class="text-sm op-60">how we got here</div></div>
  <div v-click><b>Today</b><div class="text-sm op-60">what works and what it costs</div></div>
  <div v-click>Tomorrow<div class="text-sm op-60">what's still missing</div></div>
</div>
