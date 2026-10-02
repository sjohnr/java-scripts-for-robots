#!/usr/bin/env python3
import argparse

def main() -> None:
    parser = argparse.ArgumentParser(description="Demonstrate parsing command-line arguments.")
    parser.add_argument("-P", "--port", type=int, required=True, help="port number to use")
    parser.add_argument("--param1", required=True, help="a string parameter")
    parser.add_argument("--param2", type=int, required=True, help="an integer parameter")
    parser.add_argument("--enable-featureX", dest="enable_feature_x", action="store_true",
                        help="turn on feature X")
    args = parser.parse_args()

    print(f"port           = {args.port}")
    print(f"param1         = {args.param1}")
    print(f"param2         = {args.param2}")
    print(f"enableFeatureX = {args.enable_feature_x}")


if __name__ == "__main__":
    main()
