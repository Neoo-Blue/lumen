#!/usr/bin/env python3
"""Live Google Flights search for Lumen: flights.py FROM TO YYYY-MM-DD [adults] -> JSON on stdout."""
import json, sys
from fast_flights import FlightQuery, Passengers, create_query, get_flights

src, dst, date = sys.argv[1].upper(), sys.argv[2].upper(), sys.argv[3]
adults = int(sys.argv[4]) if len(sys.argv) > 4 else 1
q = create_query(flights=[FlightQuery(date=date, from_airport=src, to_airport=dst)], seat="economy", trip="one-way",
                 passengers=Passengers(adults=adults), currency="USD")
out = []
for it in get_flights(q):
    legs = it.flights
    if not legs:
        continue
    d, a = legs[0].departure, legs[-1].arrival
    hhmm = lambda t: f"{t[0]:02d}:{t[1]:02d}" if t else None
    out.append({
        "airline": ", ".join(it.airlines),
        "flight": " / ".join(filter(None, [getattr(l, "flight_number", None) for l in legs])) or None,
        "depart": hhmm(d.time), "arrive": hhmm(a.time),
        "depart_date": "-".join(f"{x:02d}" if i else str(x) for i, x in enumerate(d.date)) if d.date else date,
        "arrive_date": "-".join(f"{x:02d}" if i else str(x) for i, x in enumerate(a.date)) if a.date else None,
        "stops": len(legs) - 1,
        "via": [l.to_airport.code for l in legs[:-1]],
        "minutes": sum(l.duration or 0 for l in legs),
        "price": it.price,
    })
print(json.dumps({"currency": "USD", "flights": out[:12]}))
