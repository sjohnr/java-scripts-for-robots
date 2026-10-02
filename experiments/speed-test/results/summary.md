| Launcher | Warm (mean +- sd) | vs. Python | 50 calls | First run | On disk | Wrapper |
|---|---:|---:|---:|---:|---:|---:|
| python | 21.1 ms +- 0.2 | 1.0x | 1.1 s | - | - | - |
| small-disk | 314.5 ms +- 7.3 | 14.9x | 15.7 s | - | 8 KB | 57 lines |
| portable | 67.7 ms +- 0.9 | 3.2x | 3.4 s | 489.7 ms | 16 KB | 98 lines |
| fast-start | 53.0 ms +- 1.8 | 2.5x | 2.6 s | 782.0 ms | 11.5 MB | 123 lines |
| native | 21.8 ms +- 0.3 | 1.0x | 1.1 s | 13.7 s | 10.8 MB | 149 lines |

Shared dependency jars (lib/): 984 KB

See env.txt for the machine and versions.
