# Slides

Built with [Slidev](https://sli.dev). Requires Node 22.12 or newer, plus a JDK on the `PATH` (or `JAVA_HOME`) for live Java slides.

| Command | What it does |
|---|---|
| `npm install` | Install once |
| `npm run dev` | Present and edit with live reload. Presenter view at `/presenter`. |
| `npm run build` | Static site in `dist/` (live Java runner disabled) |
| `npm run export` | PDF export |

## Layout

| Path | Contents |
|---|---|
| `slides.md` | Title slide, imports one file per section |
| `pages/01-introduction.md` ... `05-conclusion.md` | Sections from `OUTLINE-draft-3.md` |
| `components/Scorecard.vue` | Scorecard table: `<Scorecard :rows="bakeoff" stepped />` reveals one row per click |
| `data/scorecard.ts` | Scorecard rows and grades (`win`, `ok`, `lose`, `na`) |
| `setup/code-runners.ts` | `java` runner for ` ```java {monaco-run} ` blocks |
| `vite.config.ts` | Dev-server endpoint that runs the Java (loopback only) |

## Handy syntax

| Want | Syntax |
|---|---|
| Import code from the experiments | `<<< @/snippets/javaw/results/<run>/Javaw.java java {*}{maxHeight:'360px'}` |
| Animate between code versions | ` ````md magic-move ` wrapping several fenced blocks |
| Step through highlighted lines | ` ```java {1\|3-5\|all} ` |
| Editable, runnable Java | ` ```java {monaco-run} {autorun: true} ` |
| Run one file several ways | ` ```java {monaco-run} {autorun: true, runnerOptions: opts} ` plus `<script setup>const opts = { launchers: ['sh', 'java'], args: ['KC'] }</script>` on the slide. Fence options can't nest braces. Launchers: `java`, `javac`, `sh` |
| Speaker notes | `<!-- ... -->` at the end of a slide |

Set `SLIDES_JAVA=/path/to/java` to pick a specific JDK for the runner.
