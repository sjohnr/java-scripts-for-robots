# Token cost analysis

Tokenizer: **tiktoken o200k_base (proxy)**

## Source size per implementation

| CLI | impl | bytes | lines | tokens |
|---|---|---:|---:|---:|
| noop | go | 333 | 19 | 100 |
| noop | python | 403 | 17 | 104 |
| noop | node | 324 | 13 | 85 |
| noop | java-classic | 304 | 10 | 70 |
| noop | java-compact | 212 | 8 | 58 |
| jarsrc | go | 11127 | 549 | 3492 |
| jarsrc | python | 10573 | 361 | 2741 |
| jarsrc | node | 12752 | 427 | 3701 |
| jarsrc | java-classic | 11432 | 264 | 2786 |
| jarsrc | java-compact | 11034 | 249 | 2709 |
| jarsrc | java-multi | 11983 | 281 | 2898 |
| logsum | go | 6086 | 287 | 2052 |
| logsum | python | 5581 | 193 | 1485 |
| logsum | node | 4465 | 160 | 1341 |
| logsum | java-classic | 5262 | 126 | 1324 |
| treesum | go | 5907 | 278 | 1899 |
| treesum | python | 5390 | 176 | 1393 |
| treesum | node | 4629 | 176 | 1409 |
| treesum | java-classic | 5326 | 121 | 1275 |
| jsonpick | go | 13520 | 694 | 4455 |
| jsonpick | python | 6545 | 237 | 1630 |
| jsonpick | node | 5192 | 188 | 1519 |
| jsonpick | java-classic | 13505 | 351 | 3077 |
| jsonpick | java-jbang | 6261 | 168 | 1478 |

## --help output

| CLI | impl | stdout tokens | stderr tokens |
|---|---|---:|---:|
| noop | go | 13 | 0 |
| noop | python | 13 | 0 |
| noop | node | 13 | 0 |
| jarsrc | go | 96 | 0 |
| jarsrc | python | 94 | 0 |
| jarsrc | node | 91 | 0 |
| logsum | go | 79 | 0 |
| logsum | python | 82 | 0 |
| logsum | node | 77 | 0 |
| treesum | go | 100 | 0 |
| treesum | python | 113 | 0 |
| treesum | node | 97 | 0 |
| jsonpick | go | 94 | 0 |
| jsonpick | python | 94 | 0 |
| jsonpick | node | 83 | 0 |

## Naive shell flailing vs. one CLI call

| Task | approach | commands | output tokens |
|---|---|---:|---:|
| jarsrc_task | naive shell | 3 | 749 |
| jarsrc_task | single CLI call | 1 | 29 |
| treesum_task | naive shell | 11 | 42 |
| treesum_task | single CLI call | 1 | 252 |
