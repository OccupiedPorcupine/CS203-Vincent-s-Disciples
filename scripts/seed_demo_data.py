"""Fill a local account with fake chicken rice stall sales, for demos and development.

The script talks to the running backend the same way the browser does: it fetches a CSRF
token, signs up (or logs in if the account exists), creates any missing dishes and adds the
sales for every date that has none yet, with leftovers and the odd sell-out. It also gives each
dish a price and cost (if it has none) and a recipe, so revenue, profit and the prep list's
ingredients have something to show. Running it again only fills in what is missing.

    python3 scripts/seed_demo_data.py                      # 120 days ending yesterday
    python3 scripts/seed_demo_data.py --days 60 --csv demo_sales.csv
    python3 scripts/seed_demo_data.py --csv-only --csv demo_sales.csv   # no backend needed

The numbers are invented. They follow a residential hawker centre pattern (busier at the
weekend, quieter on rainy days, slowly growing) so the dashboard and forecast have
something believable to show. Uses only the Python standard library.
"""

import argparse
import csv
import json
import random
import sys
import urllib.error
import urllib.request
from datetime import date, datetime, timedelta, timezone
from http.cookiejar import CookieJar

SINGAPORE = timezone(timedelta(hours=8))

# Typical portions sold on an ordinary weekday.
DISHES = {
    "Roasted chicken rice": 55,
    "Steamed chicken rice": 48,
    "Char siew rice": 30,
    "Chicken drumstick rice": 18,
    "Lemon chicken rice": 14,
}

# Selling price and cost of one portion, in S$.
PRICES = {
    "Roasted chicken rice": ("4.50", "1.80"),
    "Steamed chicken rice": ("4.50", "1.70"),
    "Char siew rice": ("5.00", "2.10"),
    "Chicken drumstick rice": ("6.00", "2.60"),
    "Lemon chicken rice": ("5.50", "2.30"),
}

# What one portion uses (cooked amounts). Each ingredient keeps one unit across dishes.
RECIPES = {
    "Roasted chicken rice": [("Chicken", "g", 150), ("Rice", "g", 200), ("Cucumber", "g", 30), ("Chilli sauce", "ml", 20)],
    "Steamed chicken rice": [("Chicken", "g", 150), ("Rice", "g", 200), ("Cucumber", "g", 30), ("Chilli sauce", "ml", 20)],
    "Char siew rice": [("Pork shoulder", "g", 120), ("Rice", "g", 200), ("Char siew sauce", "ml", 30), ("Cucumber", "g", 30)],
    "Chicken drumstick rice": [("Chicken drumstick", "pcs", 1), ("Rice", "g", 200), ("Chilli sauce", "ml", 20)],
    "Lemon chicken rice": [("Chicken thigh", "g", 140), ("Rice", "g", 200), ("Lemon sauce", "ml", 40)],
}

# Ingredients whose cooked weight differs from what is bought, and the app's default doesn't know:
# rice roughly doubles and a half when cooked. Only set while the owner hasn't customised them.
YIELDS = {"Rice": 250}

# The smaller dishes sometimes sell out at the weekend lunch rush; other days leave a few over.
SELL_OUT_DISHES = {"Char siew rice", "Lemon chicken rice"}
WEEKEND_SELL_OUT_CHANCE = 0.3
LEFTOVER_MEAN = 2.5

# Monday first, matching date.weekday().
WEEKDAY_FACTOR = [0.90, 0.92, 0.95, 0.97, 1.05, 1.25, 1.20]
RAINY_DAY_CHANCE = 0.15
RAINY_DAY_FACTOR = 0.85
GROWTH_OVER_PERIOD = 0.05
NOISE = 0.08


def generate_sales(end_date, days, seed):
    """Returns {dish name: [(date, quantity, sold out at or None, leftover), ...]}, oldest first."""
    rng = random.Random(seed)
    dates = [end_date - timedelta(days=offset) for offset in range(days - 1, -1, -1)]
    sales = {name: [] for name in DISHES}

    for index, day in enumerate(dates):
        # Weather and growth affect the whole stall; noise is per dish.
        stall_factor = WEEKDAY_FACTOR[day.weekday()] * (1 + GROWTH_OVER_PERIOD * index / max(days - 1, 1))
        if rng.random() < RAINY_DAY_CHANCE:
            stall_factor *= RAINY_DAY_FACTOR

        for name, base in DISHES.items():
            quantity = max(round(base * stall_factor * rng.gauss(1, NOISE)), 0)
            if name in SELL_OUT_DISHES and day.weekday() >= 5 and rng.random() < WEEKEND_SELL_OUT_CHANCE:
                sold_out_at = f"{rng.randint(12, 13)}:{rng.choice(['05', '20', '35', '50'])}"
                sales[name].append((day, quantity, sold_out_at, 0))
            else:
                leftover = max(round(rng.gauss(LEFTOVER_MEAN, 1.5)), 0)
                sales[name].append((day, quantity, None, leftover))

    return sales


def write_csv(path, sales):
    rows = sorted(
        (day.isoformat(), name, quantity)
        for name, records in sales.items()
        for day, quantity, _, _ in records
    )
    with open(path, "w", newline="", encoding="utf-8") as file:
        writer = csv.writer(file)
        writer.writerow(["date", "dish", "quantity_sold"])
        writer.writerows(rows)
    print(f"Wrote {len(rows)} rows to {path}")


class Api:
    def __init__(self, base_url):
        self.base_url = base_url.rstrip("/")
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(CookieJar()))

    def request(self, method, path, body=None):
        headers = {"Accept": "application/json"}
        data = None

        if method != "GET":
            csrf = self.request("GET", "/api/csrf")
            headers[csrf["headerName"]] = csrf["token"]

        if body is not None:
            headers["Content-Type"] = "application/json"
            data = json.dumps(body).encode("utf-8")

        request = urllib.request.Request(self.base_url + path, data=data, headers=headers, method=method)
        with self.opener.open(request) as response:
            return json.loads(response.read() or b"null")


def sign_in(api, email, password, name):
    try:
        api.request("POST", "/api/auth/signup", {"email": email, "password": password, "name": name})
        print(f"Created account {email}")
    except urllib.error.HTTPError as error:
        if error.code != 409:
            raise
        api.request("POST", "/api/auth/login", {"email": email, "password": password})
        print(f"Logged in as {email}")


def seed(api, sales):
    dishes = {dish["name"]: dish["id"] for dish in api.request("GET", "/api/dishes")}

    for name, records in sales.items():
        if name not in dishes:
            dishes[name] = api.request("POST", "/api/dishes", {"name": name})["id"]
            print(f"Created dish {name}")

        recorded = {sale["date"] for sale in api.request("GET", f"/api/dishes/{dishes[name]}/sales")}
        missing = [
            {"date": day.isoformat(), "quantitySold": quantity, "soldOutAt": sold_out_at, "leftoverQuantity": leftover}
            for day, quantity, sold_out_at, leftover in records
            if day.isoformat() not in recorded
        ]

        if missing:
            added = api.request("POST", f"/api/dishes/{dishes[name]}/sales", {"sales": missing})["added"]
            print(f"{name}: added {added} days")
        else:
            print(f"{name}: already complete")

        if name in PRICES and not api.request("GET", f"/api/dishes/{dishes[name]}/prices"):
            price, cost = PRICES[name]
            api.request("PUT", f"/api/dishes/{dishes[name]}/price",
                        {"price": price, "unitCost": cost, "effectiveFrom": records[0][0].isoformat()})
            print(f"{name}: price S${price}, cost S${cost}")
        if name in RECIPES:
            items = [{"ingredient": ingredient, "unit": unit, "quantity": quantity}
                     for ingredient, unit, quantity in RECIPES[name]]
            api.request("PUT", f"/api/dishes/{dishes[name]}/recipe", {"items": items})

    for ingredient in api.request("GET", "/api/ingredients"):
        if ingredient["name"] in YIELDS and not ingredient["customised"]:
            api.request("PUT", f"/api/ingredients/{ingredient['id']}",
                        {"yieldPercent": YIELDS[ingredient["name"]], "perishable": ingredient["perishable"]})
            print(f"{ingredient['name']}: {YIELDS[ingredient['name']]}% yield")


def main():
    yesterday = datetime.now(SINGAPORE).date() - timedelta(days=1)

    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--api", default="http://localhost:8080", help="backend base URL")
    parser.add_argument("--email", default="demo@chicky.test")
    parser.add_argument("--password", default="chicky-demo-password", help="at least 12 characters")
    parser.add_argument("--name", default="Demo Hawker")
    parser.add_argument("--days", type=int, default=120, help="days of history (forecasting needs 28+)")
    parser.add_argument("--end-date", type=date.fromisoformat, default=yesterday,
                        help="last day of sales, YYYY-MM-DD (default: yesterday in Singapore)")
    parser.add_argument("--seed", type=int, default=7, help="random seed, so runs are repeatable")
    parser.add_argument("--csv", help="also write the sales to this CSV file")
    parser.add_argument("--csv-only", action="store_true", help="only write the CSV; don't call the backend")
    args = parser.parse_args()

    if args.csv_only and not args.csv:
        parser.error("--csv-only needs --csv PATH")

    sales = generate_sales(args.end_date, args.days, args.seed)

    if args.csv:
        write_csv(args.csv, sales)
    if args.csv_only:
        return

    api = Api(args.api)
    try:
        sign_in(api, args.email, args.password, args.name)
        seed(api, sales)
    except urllib.error.HTTPError as error:
        sys.exit(f"{error.code} from {error.url}: {error.read().decode('utf-8', 'replace')}")
    except urllib.error.URLError as error:
        sys.exit(f"Could not reach the backend at {args.api} ({error.reason}). Is it running?")

    print(f"Done. Sign in as {args.email} / {args.password} to see the data.")


if __name__ == "__main__":
    main()
