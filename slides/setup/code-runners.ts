import { defineCodeRunnersSetup } from '@slidev/types'

// Runs ```java {monaco-run} blocks through the dev server (see vite.config.ts).
// Options, via {runnerOptions: opts} with `opts` defined in the slide's <script setup>
// (code-fence options can't contain nested braces):
//   args:      program arguments, e.g. ['a', 'b']
//   file:      source file name, e.g. 'Assert.java' (default: the public class name, else Main.java)
//   source:    'snippets/x.java#main' runs that whole file with the editor's code in place of region #main,
//              for slides that import only the region (<<< @/snippets/x.java#main)
//   cwd:       working directory under the slides root, e.g. 'snippets/examples' (default: a fresh temp dir)
//   files:     files under the slides root to copy next to the source, e.g. ['snippets/examples/java/example5.csv']
//   launchers: run the same file several ways, in order: 'java' (default), 'javac', 'sh', 'jbang', 'javaw'.
//              A launcher can carry options: 'java -ea', 'javaw -o tool'. './tool' runs a file an earlier run wrote.
//   runs:      in place of args and launchers, one entry per run: [{ launcher: 'java -ea', args: ['--help'] }]
//              A run can add jdk: 28 to use another JDK (SLIDES_JAVA_28, else the newest SDKMAN 28*).
//   With launchers or runs set, each run is labeled with its command line.
// Slidev renders text outputs as inline spans, which collapses newlines and tabs, so output goes out as <pre>,
// stdout and stderr interleaved in the order they were written
function pre(text: string, cls = ''): string {
  const escaped = text.trimEnd().replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;')
  return `<pre class="${cls}" style="margin: 0.25rem 0; font: inherit; white-space: pre-wrap; background: transparent">${escaped}</pre>`
}

export default defineCodeRunnersSetup(() => ({
  async java(code, ctx) {
    const args = (ctx.options.args as string[] | undefined) ?? []
    const launchers = ctx.options.launchers as string[] | undefined
    const specs = ctx.options.runs as { launcher?: string, args?: string[], jdk?: number }[] | undefined
    const file = ctx.options.file as string | undefined
    const source = ctx.options.source as string | undefined
    const cwd = ctx.options.cwd as string | undefined
    const files = ctx.options.files as string[] | undefined
    let response: Response
    try {
      response = await fetch('/__java', {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ code, args, launchers, runs: specs, file, source, cwd, files }),
      })
    }
    catch (e) {
      return { error: `Java runner unreachable: ${e}` }
    }
    if (!response.ok)
      return { error: `Java runner unavailable (HTTP ${response.status}). It only works under npm run dev.` }
    const { runs } = await response.json() as { runs: { command: string, output: { stream: 'stdout' | 'stderr', text: string }[], exitCode: number | null, millis: number, jdk?: number }[] }
    return runs.flatMap(({ command, output, exitCode, millis, jdk }) => [
      ...(launchers || specs ? [{ text: `$ ${command}${jdk ? `  # JDK ${jdk}` : ''}`, class: 'op-60' }] : []),
      ...output.map(({ stream, text }) => ({ html: pre(text, stream === 'stderr' ? 'text-red-400' : '') })),
      { text: `exit ${exitCode ?? '?'} · ${millis} ms`, class: 'op-50 text-xs' },
    ])
  },
}))
