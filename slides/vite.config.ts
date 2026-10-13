import { spawn } from 'node:child_process'
import { copyFile, mkdtemp, readdir, readFile, rm, writeFile } from 'node:fs/promises'
import { homedir, tmpdir } from 'node:os'
import { basename, dirname, join, resolve, sep } from 'node:path'
import { defineConfig, type Plugin } from 'vite'

type Launcher = 'java' | 'javac' | 'sh' | 'jbang' | 'javaw'

interface RunSpec {
  launcher?: string
  args?: string[]
  jdk?: number
}

// A stretch of output from one stream; a run's chunks are in the order they arrived, so stdout and stderr interleave
interface Chunk {
  stream: 'stdout' | 'stderr'
  text: string
}

interface Run {
  command: string
  output: Chunk[]
  exitCode: number | null
  millis: number
  jdk?: number
}

const noise = /^(Picked up (JAVA_TOOL_OPTIONS|_JAVA_OPTIONS|JDK_JAVA_OPTIONS)|NOTE: Picked up JDK_JAVA_OPTIONS|\[jbang\] |WARNING: Using incubator modules).*\n?/gm

// A command line as you'd type it, quoting arguments the shell would mangle ('**/*.json')
function shell(parts: string[]): string {
  return parts.map(p => /^[\w@%+=:,./-]+$/.test(p) ? p : `'${p.replaceAll('\'', '\'\\\'\'')}'`).join(' ')
}

// Runs in cwd; paths under `hide` are trimmed from stderr (temp dir, working dir).
// With javaHome, JAVA_HOME and PATH point at that JDK too (for jbang and sh).
function exec(cmd: string, args: string[], cwd: string, hide: string[] = [cwd], javaHome?: string): Promise<Omit<Run, 'command'>> {
  const env = { ...process.env }
  delete env.CLASSPATH // so `java Hello` finds the class in the working directory
  if (javaHome) {
    env.JAVA_HOME = javaHome
    env.PATH = `${join(javaHome, 'bin')}:${env.PATH}`
  }
  return new Promise((resolve) => {
    const started = performance.now()
    const child = spawn(cmd, args, { cwd, env, timeout: 20_000 })
    const output: Chunk[] = []
    const append = (stream: Chunk['stream'], text: string) => {
      const last = output.at(-1)
      if (last?.stream === stream)
        last.text += text
      else
        output.push({ stream, text })
    }
    const clean = (text: string) => hide.reduce((t, dir) => t.replaceAll(dir + '/', ''), text.replace(noise, ''))
    const done = (exitCode: number | null, error?: string) => resolve({
      output: error
        ? [{ stream: 'stderr', text: clean(error) }]
        : output.map(c => c.stream === 'stderr' ? { ...c, text: clean(c.text) } : c).filter(c => c.text),
      exitCode,
      millis: Math.round(performance.now() - started),
    })
    child.stdout.on('data', d => append('stdout', String(d)))
    child.stderr.on('data', d => append('stderr', String(d)))
    child.on('error', err => done(null, `could not run ${cmd}: ${err.message}`))
    child.on('close', exitCode => done(exitCode))
  })
}

// The java binary for a feature release: SLIDES_JAVA_<n> if set, else the newest ~/.sdkman/candidates/java/<n>*
async function findJava(feature: number): Promise<string> {
  const fromEnv = process.env[`SLIDES_JAVA_${feature}`]
  if (fromEnv)
    return fromEnv
  const candidates = join(homedir(), '.sdkman', 'candidates', 'java')
  const match = (await readdir(candidates).catch(() => [] as string[]))
    .filter(name => new RegExp(`^${feature}([.+-]|$)`).test(name))
    .sort()
    .at(-1)
  if (!match)
    throw new Error(`JDK ${feature} not found: set SLIDES_JAVA_${feature} or install it with SDKMAN`)
  return join(candidates, match, 'bin', 'java')
}

function resolveInRoot(root: string, path: string): string {
  const resolved = resolve(root, path)
  if (!resolved.startsWith(root + sep))
    throw new Error(`path escapes the slides root: ${path}`)
  return resolved
}

// A shebang (#!, or JBang's ///usr/bin/env jbang) or a JBang directive (//DEPS, //JAVA, ...)
const headerLine = /^(#!|\/\/\/|\/\/[A-Z_]+\b)/

/**
 * Puts the edited code in place of region `#name` of a file under the slides root (`// #region name` ... `// #endregion [name]`),
 * so a slide can show just that region (`<<< file#name`) and still run the whole program. Marker lines are dropped.
 * If the code starts with header lines (see headerLine), they replace the file's own header, so a slide can show
 * a subset of the header (the shebang and //DEPS) above the region and leave out the imports in between.
 */
async function spliceRegion(root: string, source: string, code: string): Promise<string> {
  const [path, region] = source.split('#')
  const src = resolveInRoot(root, path)
  if (!/^[\w-]+$/.test(region ?? ''))
    throw new Error(`source needs a #region name: ${source}`)
  const lines = (await readFile(src, 'utf8')).split('\n')
  const codeLines = code.split('\n')
  const codeHeader = codeLines.findIndex(line => !headerLine.test(line))
  const fileHeader = codeHeader > 0 ? lines.findIndex(line => !headerLine.test(line)) : 0
  if (codeHeader > 0) {
    lines.splice(0, fileHeader, ...codeLines.slice(0, codeHeader))
    code = codeLines.slice(codeHeader).join('\n').replace(/^\s*\n/, '')
  }
  const start = lines.findIndex(line => new RegExp(`^\\s*//\\s*#?region\\s+${region}\\s*$`).test(line))
  const end = lines.findIndex((line, i) => i > start && new RegExp(`^\\s*//\\s*#?endregion(\\s+${region})?\\s*$`).test(line))
  if (start < 0 || end < 0)
    throw new Error(`region #${region} not found in ${path}`)
  // Drop every marker line (nested regions too), as Slidev does, so stack-trace line numbers match the slide
  const marker = /^\s*\/\/\s*#?(end)?region\b/
  return [...lines.slice(0, start), code.trimEnd(), ...lines.slice(end + 1)].filter(line => !marker.test(line)).join('\n')
}

/**
 * Dev-server endpoint behind the `java` code runner (setup/code-runners.ts).
 * POST /__java {"code": "...", "args": [], "launchers": ["java"], "file": "Hello.java"} writes the code to a temp dir and runs it once per launcher.
 * Or "runs": [{"launcher": "java", "args": []}, ...] in place of args and launchers, for different args per run.
 * With "source": "snippets/x.java#main", the code replaces that region of the file (see spliceRegion).
 * With "cwd": "snippets/examples", runs in that directory (under the slides root) instead of the temp dir.
 * With "files": ["snippets/examples/java/example5.csv"], copies those files next to the source (for resources, JBang //FILES).
 * A run can pick another JDK with "jdk": 28 (see findJava); the default is SLIDES_JAVA, else JAVA_HOME, else java on the PATH.
 * The file name comes from `file`, else the public class, else Main.java:
 *   java   java Hello.java            (source launch, the default)
 *   javac  javac Hello.java && java Hello
 *   sh     sh Hello.java              (for polyglot files whose first line is shell)
 *   jbang  jbang Hello.java           ([jbang] progress lines are dropped)
 *   javaw  javaw Hello.java           (writes a wrapper script; options go before the file, e.g. "javaw -o hello")
 *   ./hello                           runs a file an earlier run wrote in the working directory, e.g. the javaw wrapper
 * A launcher can carry options after the name, e.g. "java -ea" runs java -ea Hello.java.
 * Only answers loopback requests, so `slidev --remote` can't run code from the network.
 */
function javaRunner(): Plugin {
  const java = process.env.SLIDES_JAVA ?? (process.env.JAVA_HOME ? join(process.env.JAVA_HOME, 'bin', 'java') : 'java')
  const javac = java.includes('/') ? join(dirname(java), 'javac') : 'javac'
  return {
    name: 'slides-java-runner',
    apply: 'serve',
    configureServer(server) {
      const root = server.config.root
      server.middlewares.use('/__java', (req, res) => {
        const remote = req.socket.remoteAddress ?? ''
        if (!['127.0.0.1', '::1', '::ffff:127.0.0.1'].includes(remote)) {
          res.statusCode = 403
          return res.end()
        }
        if (req.method !== 'POST') {
          res.statusCode = 405
          return res.end()
        }
        let body = ''
        req.on('data', chunk => (body += chunk))
        req.on('end', async () => {
          const { code = '', args = [], launchers = ['java'], runs: specs, file: requested, source, cwd: workdir, files = [] } = JSON.parse(body || '{}') as { code?: string, args?: string[], launchers?: string[], runs?: RunSpec[], file?: string, source?: string, cwd?: string, files?: string[] }
          const name = /^(\w+)\.java$/.exec(requested ?? '')?.[1] ?? /public\s+(?:final\s+)?class\s+(\w+)/.exec(code)?.[1] ?? 'Main'
          const file = `${name}.java`
          const dir = await mkdtemp(join(tmpdir(), 'slides-java-'))
          const runs: Run[] = []
          try {
            await writeFile(join(dir, file), source ? await spliceRegion(root, source, code) : code)
            for (const extra of files)
              await copyFile(resolveInRoot(root, extra), join(dir, basename(extra)))
            const cwd = workdir ? resolveInRoot(root, workdir) : dir
            const src = cwd === dir ? file : join(dir, file)
            for (const spec of specs ?? launchers.map((launcher): RunSpec => ({ launcher, args }))) {
              const [launcher, ...opts] = (spec.launcher ?? 'java').trim().split(/\s+/) as [Launcher, ...string[]]
              const args = spec.args ?? []
              const jdkJava = spec.jdk ? await findJava(spec.jdk) : java
              const jdkJavac = spec.jdk ? join(dirname(jdkJava), 'javac') : javac
              const javaHome = spec.jdk ? dirname(dirname(jdkJava)) : undefined
              const run = (cmd: string, argv: string[]) => exec(cmd, argv, cwd, [dir, cwd], javaHome)
              let result: Run
              if (launcher === 'javac') {
                const compiled = await run(jdkJavac, ['-d', dir, src])
                if (compiled.exitCode !== 0) {
                  result = { command: shell(['javac', file]), ...compiled }
                }
                else {
                  const ran = await run(jdkJava, [...opts, '-cp', dir, name, ...args])
                  result = { command: `${shell(['javac', file])} && ${shell(['java', ...opts, name, ...args])}`, ...ran, millis: compiled.millis + ran.millis }
                }
              }
              else if (launcher === 'jbang') {
                result = { command: shell(['jbang', ...opts, file, ...args]), ...await run('jbang', [...opts, src, ...args]) }
              }
              else if (launcher === 'javaw') {
                result = { command: shell(['javaw', ...opts, file, ...args]), ...await run('javaw', [...opts, src, ...args]) }
              }
              else if (launcher.startsWith('./')) {
                result = { command: shell([launcher, ...opts, ...args]), ...await run(resolveInRoot(cwd, launcher), [...opts, ...args]) }
              }
              else if (launcher === 'sh') {
                result = { command: shell(['sh', file, ...args]), ...await run('sh', [src, ...args]) }
              }
              else {
                result = { command: shell(['java', ...opts, file, ...args]), ...await run(jdkJava, [...opts, src, ...args]) }
              }
              runs.push({ ...result, jdk: spec.jdk })
            }
          }
          catch (e) {
            runs.push({ command: file, output: [{ stream: 'stderr', text: String(e) }], exitCode: null, millis: 0 })
          }
          finally {
            await rm(dir, { recursive: true, force: true })
          }
          res.setHeader('content-type', 'application/json')
          res.end(JSON.stringify({ runs }))
        })
      })
    },
  }
}

export default defineConfig({
  plugins: [javaRunner()],
  // Slidev 53 + Vite 8: lightningcss chokes on a UnoCSS rule when minifying CSS for `slidev build`.
  build: { cssMinify: false },
})
