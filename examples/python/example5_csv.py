#!/usr/bin/env python3
import csv
from dataclasses import dataclass
from pathlib import Path

@dataclass(frozen=True)
class Person:
    name: str
    age: int
    city: str

def main() -> None:
    path = Path(__file__).parent / "example5.csv"
    with path.open(newline="") as file:
        people = [Person(row["name"], int(row["age"]), row["city"]) for row in csv.DictReader(file)]
    for person in people:
        print(person)

if __name__ == "__main__":
    main()
