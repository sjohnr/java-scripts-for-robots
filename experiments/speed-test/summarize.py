"""Turns the hyperfine JSON and sizes.txt in a results directory into a markdown summary."""
import json, sys
from pathlib import Path

out = Path(sys.argv[1])
warm = {r["command"]: r for r in json.loads((out / "warm.json").read_text())["results"]}
first = {}
for f in out.glob("first-csv-*.json"):
    r = json.loads(f.read_text())["results"][0]
    first[r["command"].removeprefix("csv-")] = r["mean"]
sizes = {}
for line in (out / "sizes.txt").read_text().split("\n"):
    if line.strip():
        name, kb, lines = line.split()
        sizes[name.removeprefix("csv-")] = (int(kb), int(lines))

def ms(s): return f"{s * 1000:.1f} ms" if s < 1 else f"{s:.1f} s"
def disk(kb): return "0" if kb == 0 else (f"{kb} KB" if kb < 1024 else f"{kb / 1024:.1f} MB")

base = warm["python"]["mean"]
print("| Launcher | Warm (mean +- sd) | vs. Python | 50 calls | First run | On disk | Wrapper |")
print("|---|---:|---:|---:|---:|---:|---:|")
for name in ["python", "small-disk", "portable", "fast-start", "native"]:
    r = warm[name]
    kb, lines = sizes.get(name, (0, 0))
    print(f"| {name} | {ms(r['mean'])} +- {r['stddev'] * 1000:.1f} | {r['mean'] / base:.1f}x | {ms(r['mean'] * 50)}"
          f" | {ms(first[name]) if name in first else '-'} | {disk(kb) if name != 'python' else '-'}"
          f" | {str(lines) + ' lines' if lines else '-'} |")
print(f"\nShared dependency jars (lib/): {disk(sizes['lib'][0])}")
print("\nSee env.txt for the machine and versions.")
