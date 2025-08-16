#!/usr/bin/env python3
import csv
import sys
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt


def main():
    if len(sys.argv) < 4:
        print("Usage: plot_csv.py <csv_file> <x_column> <y_column> [output_file]", file=sys.stderr)
        sys.exit(1)
    csv_path = sys.argv[1]
    x_col = sys.argv[2]
    y_col = sys.argv[3]
    output_path = sys.argv[4] if len(sys.argv) > 4 else "plot.png"

    x_vals = []
    y_vals = []
    try:
        with open(csv_path, newline='') as f:
            reader = csv.DictReader(f)
            for row in reader:
                x_vals.append(float(row[x_col]))
                y_vals.append(float(row[y_col]))
    except FileNotFoundError:
        print(f"File not found: {csv_path}", file=sys.stderr)
        sys.exit(1)
    except KeyError as e:
        print(f"Missing column: {e}", file=sys.stderr)
        sys.exit(1)
    except ValueError as e:
        print(f"Invalid numeric value: {e}", file=sys.stderr)
        sys.exit(1)

    plt.figure()
    plt.plot(x_vals, y_vals)
    plt.xlabel(x_col)
    plt.ylabel(y_col)
    plt.tight_layout()
    plt.savefig(output_path)
    print(output_path)

if __name__ == "__main__":
    main()
