"use client";

import { useEffect, useMemo, useState } from "react";
import SiteHeader from "./SiteHeader";

const itemForecasts = [
  ["Roasted chicken rice", 81, 364.5],
  ["Steamed chicken rice", 68, 306],
  ["Chicken + char siew", 47, 305.5],
  ["Roast pork rice", 39, 214.5],
] as const;

const recentSales = [
  { label: "Sat, 19 Sep", amount: 1428, height: 58 },
  { label: "Sun, 20 Sep", amount: 1686, height: 72 },
  { label: "Mon, 21 Sep", amount: 1312, height: 49 },
  { label: "Tue, 22 Sep", amount: 1774, height: 78 },
  { label: "Wed, 23 Sep", amount: 1916, height: 86 },
  { label: "Thu, 24 Sep", amount: 1594, height: 68 },
  { label: "Fri, 25 Sep", amount: 2072, height: 92 },
];

const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080";

type ForecastApi = {
  predicted_revenue:number;
  predicted_orders:number;
  lower_bound:number;
  upper_bound:number;
  recent_average:number;
  model_name:string;
  training_cutoff:string;
  item_forecasts:{item:string; quantity:number; revenue:number}[];
};

export default function ForecastPage() {
  const [weather, setWeather] = useState("Cloudy");
  const [holiday, setHoliday] = useState(false);
  const [isOpen, setIsOpen] = useState(true);
  const [liveForecast, setLiveForecast] = useState<ForecastApi | null>(null);
  const [hoveredBar, setHoveredBar] = useState<number | null>(null);
  const [selectedBar, setSelectedBar] = useState<number | null>(null);

  useEffect(() => {
    const query = new URLSearchParams({ weather, holiday:String(holiday), stallOpen:String(isOpen) });
    const timeout = window.setTimeout(() => {
      fetch(`${API_BASE}/api/forecasts/tomorrow?${query}`)
        .then((response) => response.ok ? response.json() : Promise.reject())
        .then(setLiveForecast)
        .catch(() => setLiveForecast(null));
    }, 250);
    return () => window.clearTimeout(timeout);
  }, [weather, holiday, isOpen]);

  const forecast = useMemo(() => {
    if (liveForecast) return Math.round(liveForecast.predicted_revenue);
    if (!isOpen) return 0;
    const weatherAdjustment = weather === "Rain" ? -176 : weather === "Sunny" ? 124 : 0;
    return 2118 + weatherAdjustment + (holiday ? 284 : 0);
  }, [weather, holiday, isOpen, liveForecast]);

  const itemRows = liveForecast?.item_forecasts?.length
    ? liveForecast.item_forecasts.slice(0, 4).map((item) => [item.item, item.quantity, item.revenue] as const)
    : itemForecasts;
  const chartBars = [...recentSales, { label: "Tomorrow", amount: forecast, height: Math.min(96, Math.max(12, forecast / 27)) }];
  const highlightedBar = hoveredBar ?? selectedBar;

  return (
    <main className="app-shell">
      <SiteHeader active="forecast" status={liveForecast?.model_name ?? "Model ready"} />
      <div className="workspace">
        <section className="intro">
          <div><p>AH HUAT CHICKEN RICE</p><h1>Tomorrow</h1></div>
          <span>Sunday, 27 September</span>
        </section>

        <section className="forecast-layout">
          <div className="forecast-primary">
            <span className="quiet-label">Expected gross sales</span>
            <div className="forecast-value"><span>S$</span><strong>{forecast.toLocaleString("en-SG")}</strong></div>
            <div className="forecast-stats">
              <div><span>Range</span><strong>S$ {Math.round(liveForecast?.lower_bound ?? Math.max(0, forecast - 246)).toLocaleString("en-SG")} — {Math.round(liveForecast?.upper_bound ?? forecast + 238).toLocaleString("en-SG")}</strong></div>
              <div><span>Orders</span><strong>{liveForecast?.predicted_orders ?? (isOpen ? Math.round(forecast / 8.42) : 0)}</strong></div>
              <div><span>7-day avg.</span><strong className={forecast >= 1992 ? "positive" : "negative"}>{forecast >= 1992 ? "+" : ""}{Math.round(((forecast - 1992) / 1992) * 100)}%</strong></div>
            </div>
            <div className="sales-bars" aria-label="Recent sales and tomorrow forecast" onMouseLeave={() => setHoveredBar(null)}>
              {chartBars.map((bar, index) => {
                const active = highlightedBar === index;
                const dimmed = highlightedBar !== null && !active;
                return (
                  <button
                    key={bar.label}
                    className={`sales-bar ${index === chartBars.length - 1 ? "tomorrow-bar" : ""} ${active ? "active" : ""} ${dimmed ? "dimmed" : ""}`}
                    style={{height:`${bar.height}%`}}
                    onMouseEnter={() => setHoveredBar(index)}
                    onFocus={() => setHoveredBar(index)}
                    onBlur={() => setHoveredBar(null)}
                    onClick={() => setSelectedBar(selectedBar === index ? null : index)}
                    aria-label={`${bar.label}: S$${bar.amount.toLocaleString("en-SG")}`}
                    aria-pressed={selectedBar === index}
                  >
                    {active && <span className="bar-tooltip"><small>{bar.label}</small><strong>S$ {bar.amount.toLocaleString("en-SG")}</strong></span>}
                  </button>
                );
              })}
            </div>
            <div className="bar-caption"><span>Last 7 open days</span><span>Tomorrow</span></div>
          </div>

          <aside className="conditions">
            <h2>Conditions</h2>
            <label><span>Weather</span><select value={weather} onChange={(event) => setWeather(event.target.value)}><option>Sunny</option><option>Cloudy</option><option>Rain</option></select></label>
            <div className="condition-row"><span>Stall open</span><button className={`switch ${isOpen ? "on" : ""}`} onClick={() => setIsOpen(!isOpen)} aria-pressed={isOpen}><i /></button></div>
            <div className="condition-row"><span>Public holiday</span><button className={`switch ${holiday ? "on" : ""}`} onClick={() => setHoliday(!holiday)} aria-pressed={holiday}><i /></button></div>
            <dl><div><dt>Model</dt><dd>{liveForecast?.model_name ?? "Random Forest"}</dd></div><div><dt>Training data</dt><dd>to {liveForecast?.training_cutoff ?? "31 Aug 2026"}</dd></div></dl>
          </aside>
        </section>

        <section className="content-section item-section">
          <div className="section-title"><h2>Item forecast</h2><span>Top 4 by expected revenue</span></div>
          <div className="item-table">
            {itemRows.map(([name, quantity, revenue], index) => (
              <div className="item-row" key={name}><span>{String(index + 1).padStart(2,"0")}</span><strong>{name}</strong><div className="item-bar"><i style={{width:`${Math.min(100, quantity)}%`}} /></div><span>{quantity} portions</span><strong>S$ {revenue.toFixed(2)}</strong></div>
            ))}
          </div>
        </section>

        <footer><span>Chicken rice demo</span><span>Last refreshed just now</span></footer>
      </div>
    </main>
  );
}
