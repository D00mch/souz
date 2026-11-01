"""Flights and trains between two cities in one call, for "how do I get to ..." questions.

The agent only sees skill ids in its inventory and answers vague travel questions from memory,
so this skill's id matches the question and it simply runs the flight and train skills that live
next to it (../../avia-tickets, ../../train-tickets) and prints both outputs.
No shebang on purpose: a bare `/usr/bin/...` line trips the bundle validator.
"""
import argparse
import datetime
import os
import subprocess
import sys

SKILLS = os.path.join(os.path.dirname(os.path.abspath(__file__)), os.pardir, os.pardir)


def run(label, script, args):
    print("=== %s" % label)
    path = os.path.join(SKILLS, script)
    if not os.path.exists(path):
        print("Error: %s skill is not installed" % label.lower())
        return
    try:
        done = subprocess.run([sys.executable, path] + args, capture_output=True, text=True, timeout=45)
        print((done.stdout or done.stderr or "Error: no output").strip())
    except subprocess.TimeoutExpired:
        print("Error: %s search timed out" % label.lower())


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--from", dest="origin", required=True)
    parser.add_argument("--to", dest="destination", required=True)
    parser.add_argument("--date")
    args = parser.parse_args()

    when = args.date or datetime.date.today().strftime("%Y-%m")
    run("FLIGHTS", "avia-tickets/scripts/avia.py",
        ["--from", args.origin, "--to", args.destination, "--when", when, "--limit", "3"])
    trains = ["--from", args.origin, "--to", args.destination, "--limit", "3", "--sort", "duration"]
    if args.date:
        trains += ["--date", args.date]
    run("TRAINS", "train-tickets/scripts/trains.py", trains)


if __name__ == "__main__":
    main()
