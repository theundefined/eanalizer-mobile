#!/usr/bin/env python3
"""Computes reference results of the Python eanalizer for the synthetic test data.

The JVM test `GoldenDatasetTest` checks that the app's domain code gives the same numbers for the
same CSVs and tariffs (port parity). Run with the eanalizer virtualenv, from this repo's root:

    ~/programowanie/eanalizer/.venv/bin/python tools/testdata/eanalizer_golden.py \
        ~/programowanie/eanalizer

Writes app/src/sharedTest/fixtures/eanalizer-golden.json. Net-billing uses synthetic RCE/RCEm
prices (formulas below, mirrored in the Kotlin test), so nothing is downloaded from PSE.
"""

import contextlib
import io
import json
import re
import sys
from datetime import datetime
from pathlib import Path

FIXTURES = Path("app/src/sharedTest/fixtures")
TARIFFS = ["G11", "G12", "G12w"]


def rce_price(ts: datetime) -> float:
    """Synthetic hourly RCE, zł/kWh, -0.15..0.84 (includes negative prices)."""
    return ((ts.hour * 37 + ts.timetuple().tm_yday * 11) % 100 - 15) / 100.0


def rcem_price(year: int, month: int) -> float:
    """Synthetic monthly RCEm, zł/kWh."""
    return 0.20 + ((year * 12 + month) % 7) * 0.05


def rounded(v):
    """Plain floats rounded to 6 decimals (drops float noise and numpy types)."""
    if isinstance(v, dict):
        return {k: rounded(x) for k, x in v.items()}
    if isinstance(v, list):
        return [rounded(x) for x in v]
    if isinstance(v, (str, int)) and not isinstance(v, bool):
        return v
    return round(float(v), 6)


def main() -> None:
    eanalizer = Path(sys.argv[1]).expanduser().resolve()
    sys.path.insert(0, str(eanalizer))
    from eanalizer import core, netbilling
    from eanalizer.data_loader import load_from_enea_csv
    from eanalizer.tariffs import TariffManager

    quiet = contextlib.redirect_stdout(io.StringIO())
    with quiet:
        recs = []
        for f in sorted((FIXTURES / "enea").glob("*.csv")):
            recs += load_from_enea_csv(str(f))
    recs.sort(key=lambda r: r.timestamp)
    years = range(recs[0].timestamp.year, recs[-1].timestamp.year + 1)
    tm = TariffManager(str(eanalizer / "config" / "tariffs.csv"), years)

    def run(data, tariff, capacity=0.0, ratio=None, efficiency=1.0):
        with contextlib.redirect_stdout(io.StringIO()):
            stats, sim = core.run_full_analysis(data, capacity, tm, tariff, ratio, efficiency)
        return stats, sim

    def summary(stats, sim):
        out = {
            "totalCost": stats["calkowity_koszt"],
            "fixedFees": stats["oplaty_stale"],
            "savings": stats["oszczednosc"],
            "gridImport": float(sim["pobor_z_sieci"].sum()),
            "gridExport": float(sim["oddanie_do_sieci"].sum()),
            "zones": {
                z: {"pobor": s["pobor_z_sieci"], "oddanie": s["oddanie_do_sieci"], "cost": s["koszt_poboru"]}
                for z, s in stats["strefy"].items()
            },
        }
        if "niewykorzystany_kredyt_koncowy" in stats:
            out["unusedCredit"] = stats["niewykorzystany_kredyt_koncowy"]
        return out

    def printed(fn, *args):
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            fn(*args)
        return buf.getvalue()

    daily = core.aggregate_daily_data(recs)
    monthly = core.aggregate_monthly_data(recs)
    trends = printed(core.analyze_daily_trends, daily)
    rce = {r.timestamp: rce_price(r.timestamp) for r in recs}
    rcem = {f"{y}-{m:02d}": rcem_price(y, m) for y in years for m in range(1, 13)}

    with contextlib.redirect_stdout(io.StringIO()):
        year2025 = core.filter_data_by_date(recs, "2025-01-01", "2025-12-31")

    golden = {
        "records": len(recs),
        "first": str(recs[0].timestamp),
        "last": str(recs[-1].timestamp),
        "totals": {
            k: float(monthly[k].sum()) for k in ["pobor_przed", "oddanie_przed", "pobor", "oddanie"]
        },
        "monthly": {
            row.miesiac: [row.pobor_przed, row.oddanie_przed, row.pobor, row.oddanie]
            for row in monthly.itertuples()
        },
        "days": len(daily),
        "surplusDays": int(re.search(r"(\d+) z \d+ dni", trends).group(1)),
        "tariffs": {},
    }
    for t in TARIFFS:
        stats, sim = run(recs, t)
        entry = {
            "plain": summary(stats, sim),
            "netMetering08": summary(*run(recs, t, ratio=0.8)),
            "netMetering07": summary(*run(recs, t, ratio=0.7)),
            "storage10": summary(*run(recs, t, capacity=10.0, efficiency=0.9)),
            "year2025": summary(*run(year2025, t)),
            "optimalCapacity": float(
                re.search(r"Wynik: ([\d.]+)", printed(core.calculate_optimal_capacity, recs, daily, tm, t)).group(1)
            ),
        }
        for wycena in ["rcem", "rce"]:
            nb = netbilling.settle_net_billing(
                sim, tm, t, rce_prices=rce, rcem_prices=rcem, wycena=wycena, fixed_fee=stats["oplaty_stale"]
            )
            assert nb["brakujace_ceny_rce_godziny"] == 0 and not nb["brakujace_ceny_rcem"]
            entry[f"netBilling_{wycena}"] = {
                k: nb[k]
                for k in [
                    "calkowity_koszt", "koszt_energii", "energia_do_zaplaty", "koszt_dystrybucji",
                    "wartosc_depozytu", "pokryte_depozytem", "zwrot_nadplaty", "przepadly_depozyt",
                    "depozyt_pozostaly",
                ]
            }
        golden["tariffs"][t] = entry

    out = FIXTURES / "eanalizer-golden.json"
    out.write_text(json.dumps(rounded(golden), indent=1, sort_keys=True) + "\n")
    print(f"{out}: {len(recs)} records")


if __name__ == "__main__":
    main()
