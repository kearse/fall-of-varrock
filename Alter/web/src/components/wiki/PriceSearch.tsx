"use client";

import { useMemo, useState } from "react";
import type { PriceRow } from "@/lib/prices";

export function PriceSearch({ rows }: { rows: PriceRow[] }) {
  const [q, setQ] = useState("");

  const filtered = useMemo(() => {
    const needle = q.trim().toLowerCase();
    if (!needle) return rows;
    return rows.filter(
      (r) =>
        r.item.toLowerCase().includes(needle) ||
        r.where.toLowerCase().includes(needle) ||
        r.currency.toLowerCase().includes(needle)
    );
  }, [q, rows]);

  return (
    <div>
      <input
        className="wiki-price-search"
        type="search"
        placeholder="Search items, e.g. 'claws', 'boots', 'blood money'"
        value={q}
        aria-label="Search item prices"
        onChange={(e) => setQ(e.target.value)}
      />
      <div className="wiki-price-count">
        {filtered.length} of {rows.length} items
      </div>
      <div className="wiki-table-scroll">
        <table className="wikitable">
          <thead>
            <tr>
              <th>Item</th>
              <th>Cost</th>
              <th>Currency</th>
              <th>Where</th>
            </tr>
          </thead>
          <tbody>
            {filtered.map((r, i) => (
              <tr key={i}>
                <td>{r.item}</td>
                <td>{r.cost}</td>
                <td>{r.currency}</td>
                <td>{r.where}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {filtered.length === 0 && <p>No items match &ldquo;{q}&rdquo;.</p>}
    </div>
  );
}
