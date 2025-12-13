#!/usr/bin/env python3
import csv
import sys
import os
import platform
import subprocess
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from collections import defaultdict

def open_image(filepath):
    """Open the image file using the default viewer based on the OS"""
    try:
        if platform.system() == 'Windows':
            os.startfile(filepath)
        elif platform.system() == 'Darwin':  # macOS
            subprocess.run(['open', filepath], check=True)
        else:  # Linux and others
            subprocess.run(['xdg-open', filepath], check=True)
    except Exception as e:
        print(f"Could not open image: {e}", file=sys.stderr)

def main():
    if len(sys.argv) < 4:
        print("Usage: plot_csv.py <csv_file> <group_column> <value_column> [output_file]", file=sys.stderr)
        print("Example 1: plot_csv.py sales_report.csv Категория Доход category_income.png", file=sys.stderr)
        print("Example 2: plot_csv.py sales_report.csv Клиент Доход client_income.png", file=sys.stderr)
        print("Example 3: plot_csv.py sales_report.csv Клиент Количество client_quantity.png", file=sys.stderr)
        sys.exit(1)

    csv_path = sys.argv[1]
    group_col = sys.argv[2]
    value_col = sys.argv[3]
    output_path = sys.argv[4] if len(sys.argv) > 4 else "plot.png"

    data = defaultdict(float)
    try:
        with open(csv_path, newline='', encoding='utf-8') as f:
            reader = csv.DictReader(f)
            for row in reader:
                try:
                    group = row[group_col]
                    value = float(row[value_col])
                    data[group] += value
                except KeyError as e:
                    print(f"Missing column: {e}", file=sys.stderr)
                    sys.exit(1)
                except ValueError as e:
                    print(f"Invalid numeric value in {value_col}: {e}", file=sys.stderr)
                    sys.exit(1)
    except FileNotFoundError:
        print(f"File not found: {csv_path}", file=sys.stderr)
        sys.exit(1)

    if not data:
        print("No data found", file=sys.stderr)
        sys.exit(1)

    # Sort data by value in descending order
    sorted_data = sorted(data.items(), key=lambda x: x[1], reverse=True)
    groups = [item[0] for item in sorted_data]
    values = [item[1] for item in sorted_data]

    plt.figure(figsize=(12, 6))
    bars = plt.bar(groups, values)

    # Add value labels on top of bars
    for bar in bars:
        height = bar.get_height()
        plt.text(bar.get_x() + bar.get_width()/2., height,
                 f'{height:,.0f}',
                 ha='center', va='bottom', fontsize=8)

    plt.xlabel(group_col)
    plt.ylabel(value_col)
    plt.title(f"{value_col} по {group_col}")
    plt.xticks(rotation=45, ha='right')
    plt.tight_layout()
    plt.savefig(output_path)
    print(f"Chart saved to: {output_path}")

    # Open the image file
    open_image(output_path)

if __name__ == "__main__":
    main()