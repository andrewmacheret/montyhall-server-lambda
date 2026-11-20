#!/usr/bin/env python3
"""Plays a bunch of Monty Hall games against the API and prints how staying and switching did.

Every game finished here also counts in the API's shared stats, so point it at a
local server (see the README) if you don't want to change the public numbers.

    scripts/play.py                       # 1000 games, half stay and half switch
    scripts/play.py -n 300 --strategy switch
    scripts/play.py --api http://localhost:8080
"""

import argparse
import json
import random
import sys
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed

DEFAULT_API = "https://montyhall.andrewmacheret.com"


def call(api, path, method="GET", body=None):
    data = json.dumps(body).encode() if body is not None else None
    request = urllib.request.Request(api + path, data=data, method=method)
    if data is not None:
        request.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return json.load(response)
    except urllib.error.HTTPError as e:
        raise RuntimeError(f"{method} {path} failed with {e.code}: {e.read().decode()}") from None


def play(api, doors, switch):
    game = call(api, "/games", "POST", {"numberOfDoors": doors})
    game_id = game["id"]
    try:
        first = random.randint(1, doors)
        call(api, f"/games/{game_id}/doors/{first}", "PUT", {"state": "SELECTED"})

        if switch:
            game = call(api, f"/games/{game_id}")
            choices = [d["number"] for d in game["doors"] if d["state"] == "UNSELECTED"]
            final = random.choice(choices)
        else:
            final = first
        call(api, f"/games/{game_id}/doors/{final}", "PUT", {"state": "OPENED"})

        return call(api, f"/games/{game_id}")["prize"] == "WIN"
    finally:
        # the stats are already counted, this just tidies up (games expire after a day anyway)
        try:
            call(api, f"/games/{game_id}", "DELETE")
        except Exception:
            pass


def percent(wins, total):
    return f"{100 * wins / total:5.1f}%" if total else "  n/a"


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("-n", "--games", type=int, default=1000, help="how many games to play (default 1000)")
    parser.add_argument("--strategy", choices=["mix", "stay", "switch"], default="mix",
                        help="mix picks stay or switch at random for each game (default mix)")
    parser.add_argument("--doors", type=int, default=3, help="doors per game, 3 to 100 (default 3)")
    parser.add_argument("--parallel", type=int, default=10, help="games in flight at once (default 10)")
    parser.add_argument("--api", default=DEFAULT_API, help=f"API base URL (default {DEFAULT_API})")
    args = parser.parse_args()
    api = args.api.rstrip("/")

    def wants_switch():
        return args.strategy == "switch" or (args.strategy == "mix" and random.random() < 0.5)

    print(f"Playing {args.games} games with {args.doors} doors against {api}")
    results = {"stay": [0, 0], "switch": [0, 0]}  # [wins, games]
    failures = 0
    started = time.monotonic()

    with ThreadPoolExecutor(max_workers=args.parallel) as pool:
        futures = {}
        for _ in range(args.games):
            switch = wants_switch()
            futures[pool.submit(play, api, args.doors, switch)] = "switch" if switch else "stay"
        for done, future in enumerate(as_completed(futures), 1):
            try:
                won = future.result()
            except Exception as e:
                failures += 1
                print(f"\n{e}", file=sys.stderr)
                continue
            tally = results[futures[future]]
            tally[0] += won
            tally[1] += 1
            if sys.stdout.isatty():
                print(f"\r{done}/{args.games}", end="", flush=True)

    elapsed = time.monotonic() - started
    if sys.stdout.isatty():
        print("\r\033[K", end="")
    print(f"Finished in {elapsed:.1f}s ({args.games / elapsed:.1f} games/s)\n")

    expected = {"stay": 1 / args.doors, "switch": (args.doors - 1) / (args.doors * (args.doors - 2))}
    print(f"{'':8}{'games':>7}{'cars':>7}{'win rate':>10}{'theory':>9}")
    for name in ("stay", "switch"):
        wins, games = results[name]
        if games:
            print(f"{name:8}{games:7}{wins:7}{percent(wins, games):>10}{100 * expected[name]:8.1f}%")
    if failures:
        print(f"\n{failures} games failed")

    try:
        stats = call(api, "/stats")
        print("\nAll-time stats on the server:")
        for name in ("stayed", "switched", "totals"):
            wins, losses = stats.get(f"{name}-win", 0), stats.get(f"{name}-lose", 0)
            print(f"  {name:9}{wins + losses:8} games  {percent(wins, wins + losses)} cars")
    except Exception as e:
        print(f"\nCouldn't load server stats: {e}", file=sys.stderr)

    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
