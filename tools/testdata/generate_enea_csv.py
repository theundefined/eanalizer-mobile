#!/usr/bin/env python3
"""Generates the synthetic Enea hourly CSVs used by the tests (no real customer data).

Deterministic (fixed seed): re-running it gives byte-identical files. Covers 2024-10-01 ..
2025-12-31 split into yearly files like eBOK downloads: PV export (summer surplus days, winter
import-only days), weekends and holidays (incl. 24.12.2025), the spring DST gap (2025-03-30 02:00
absent, as in Enea exports) and a real 6-hour data gap (2025-06-10 10:00-15:00).

Format quirks as in eBOK exports: `;` separator, `="..."` timestamps marking the END of the hour
(HH:59), comma decimals; 2024.csv additionally has a UTF-8 BOM and CRLF line endings.

Usage: tools/testdata/generate_enea_csv.py [output dir]
(default app/src/sharedTest/fixtures/enea). Regenerate eanalizer-golden.json afterwards.
"""

import math
import random
import sys
from datetime import date, datetime, timedelta
from pathlib import Path

HEADER = (
    "Data;Wolumen energii elektrycznej pobranej z sieci przed bilansowaniem godzinowym;"
    "Wolumen energii elektrycznej oddanej do sieci przed bilansowaniem godzinowym;"
    "Wolumen energii elektrycznej pobranej z sieci po bilansowaniu godzinowym;"
    "Wolumen energii elektrycznej oddanej do sieci po bilansowaniu godzinowym"
)
START = date(2024, 10, 1)
END = date(2025, 12, 31)
SPRING_GAP = datetime(2025, 3, 30, 2)
DATA_GAP = (datetime(2025, 6, 10, 10), datetime(2025, 6, 10, 15))


def consumption(rng: random.Random, ts: datetime) -> float:
    """Household load, kWh: base + morning/evening peaks, more in winter and at weekends."""
    h = ts.hour
    winter = 0.5 + 0.5 * math.cos(2 * math.pi * (ts.timetuple().tm_yday - 15) / 365)
    load = 0.18 + 0.25 * winter
    if 6 <= h < 9:
        load += 0.6
    if 17 <= h < 22:
        load += 0.9 + 0.6 * winter
    if ts.weekday() >= 5 and 10 <= h < 16:
        load += 0.5
    if rng.random() < 0.05:  # kettle, washing machine...
        load += rng.uniform(0.5, 2.0)
    return load * rng.uniform(0.8, 1.2)


def production(rng: random.Random, ts: datetime, cloud: float) -> float:
    """6 kWp PV: daylight bell scaled by season and the day's cloudiness, kWh."""
    doy = ts.timetuple().tm_yday
    season = 0.5 - 0.5 * math.cos(2 * math.pi * (doy - 10) / 365)  # 0 in Jan, 1 in Jul
    half_day = 4 + 4 * season  # hours from solar noon (12:30) to sunset
    x = (ts.hour + 0.5 - 12.5) / half_day
    if abs(x) >= 1:
        return 0.0
    return 6.0 * (0.25 + 0.75 * season) * math.cos(x * math.pi / 2) ** 2 * cloud * rng.uniform(0.9, 1.0)


def kwh(v: float) -> str:
    return f"{v:.3f}".replace(".", ",")


def rows(rng: random.Random):
    day = START
    while day <= END:
        cloud = rng.choice([0.15, 0.4, 0.7, 0.9, 1.0])
        for h in range(24):
            ts = datetime(day.year, day.month, day.day, h)
            cons = consumption(rng, ts)
            prod = production(rng, ts, cloud)
            if ts == SPRING_GAP or DATA_GAP[0] <= ts <= DATA_GAP[1]:
                continue
            # Within the hour part of the production is used directly; the meter sees the rest.
            direct = min(cons, prod) * rng.uniform(0.5, 0.9)
            pp = round(cons - direct, 3)
            op = round(prod - direct, 3)
            p = round(max(pp - op, 0.0), 3)
            o = round(max(op - pp, 0.0), 3)
            stamp = f'"=""{ts:%Y-%m-%d} {h:02d}:59"""'
            yield ts.year, f'{stamp};"{kwh(pp)}";"{kwh(op)}";"{kwh(p)}";"{kwh(o)}"'
        day += timedelta(days=1)


def main() -> None:
    out = Path(sys.argv[1] if len(sys.argv) > 1 else "app/src/sharedTest/fixtures/enea")
    out.mkdir(parents=True, exist_ok=True)
    by_year: dict[int, list[str]] = {}
    for year, line in rows(random.Random(20241001)):
        by_year.setdefault(year, []).append(line)
    for year, lines in by_year.items():
        bom, eol = ("﻿", "\r\n") if year == 2024 else ("", "\n")
        text = bom + eol.join([HEADER, *lines]) + eol
        (out / f"{year}.csv").write_bytes(text.encode("utf-8"))
        print(f"{out / f'{year}.csv'}: {len(lines)} rows")


if __name__ == "__main__":
    main()
