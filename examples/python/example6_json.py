#!/usr/bin/env python3
import json
from pathlib import Path

def main() -> None:
    path = Path(__file__).parent / "example6.json"
    data = json.loads(path.read_text())
    print(data["message"])

if __name__ == "__main__":
    main()
