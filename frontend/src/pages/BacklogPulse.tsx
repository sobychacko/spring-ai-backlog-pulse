import { useEffect, useMemo, useState } from 'react'
import { fetchPulse, type PulseEntry } from '../api'
import { ItemDrawer } from '../components/ItemDrawer'

// ── Score computation ────────────────────────────────────────────────────────

function computeScores(
  entries: PulseEntry[],
  wVol: number,
  wVel: number,
  wEng: number,
): (PulseEntry & { score: number })[] {
  const total = wVol + wVel + wEng
  if (total === 0 || entries.length === 0) return entries.map((e) => ({ ...e, score: 0 }))

  const maxVol = Math.max(...entries.map((e) => e.volume), 1)
  const maxVel = Math.max(...entries.map((e) => e.velocity), 1)
  const maxEng = Math.max(...entries.map((e) => e.avgEngagement), 1)

  return entries
    .map((e) => ({
      ...e,
      score: Math.round(
        ((wVol / total) * (e.volume / maxVol) +
          (wVel / total) * (e.velocity / maxVel) +
          (wEng / total) * (e.avgEngagement / maxEng)) *
          100,
      ),
    }))
    .sort((a, b) => b.score - a.score)
}

// ── Sub-components ───────────────────────────────────────────────────────────

function ScoreBar({ score, max = 100 }: { score: number; max?: number }) {
  const pct = max > 0 ? Math.round((score / max) * 100) : 0
  const color = pct >= 70 ? '#f85149' : pct >= 45 ? '#d29922' : '#1f6feb'
  return (
    <div className="flex items-center gap-2">
      <div className="h-2 w-28 rounded-full bg-[#21262d] overflow-hidden">
        <div className="h-full rounded-full transition-all duration-300" style={{ width: `${pct}%`, backgroundColor: color }} />
      </div>
      <span className="w-6 text-right text-[12px] tabular-nums" style={{ color }}>
        {score}
      </span>
    </div>
  )
}

interface SliderProps {
  label: string
  value: number
  effectivePct: number
  onChange: (v: number) => void
  color: string
}

function WeightSlider({ label, value, effectivePct, onChange, color }: SliderProps) {
  return (
    <div className="flex flex-col gap-1.5">
      <div className="flex items-center justify-between text-[12px]">
        <span className="text-subtle">{label}</span>
        <span className="tabular-nums font-medium" style={{ color }}>
          {effectivePct}%
        </span>
      </div>
      <input
        type="range"
        min={0}
        max={100}
        value={value}
        onChange={(e) => onChange(Number(e.target.value))}
        className="w-full accent-[--slider-color] h-1.5 rounded-full cursor-pointer"
        style={{ ['--slider-color' as string]: color }}
      />
    </div>
  )
}

// ── Main page ────────────────────────────────────────────────────────────────

export function BacklogPulse() {
  const [entries, setEntries] = useState<PulseEntry[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [selectedArea, setSelectedArea] = useState<string | null>(null)

  // Weight knobs (raw slider values; effective weight = value / sum)
  const [wVol, setWVol] = useState(35)
  const [wVel, setWVel] = useState(35)
  const [wEng, setWEng] = useState(30)

  useEffect(() => {
    fetchPulse()
      .then(setEntries)
      .catch((e) => setError(String(e)))
      .finally(() => setLoading(false))
  }, [])

  const total = wVol + wVel + wEng || 1
  const pVol = Math.round((wVol / total) * 100)
  const pVel = Math.round((wVel / total) * 100)
  const pEng = 100 - pVol - pVel // avoid rounding drift

  const ranked = useMemo(
    () => computeScores(entries, wVol, wVel, wEng),
    [entries, wVol, wVel, wEng],
  )
  const maxScore = ranked[0]?.score ?? 100

  if (loading) return <div className="py-16 text-center text-subtle">Loading…</div>
  if (error) return <div className="rounded-lg border border-danger bg-surface p-4 text-danger">{error}</div>

  return (
    <div className="space-y-4">
      {/* Header */}
      <div>
        <h2 className="text-[15px] font-semibold">Backlog Pulse by Area</h2>
        <p className="mt-1 text-[12px] text-subtle">
          Tune the weights below to change what "hot" means. All counts are GitHub facts — the LLM contributes zero numbers.
        </p>
      </div>

      {/* Weight knobs */}
      <div className="rounded-lg border border-edge bg-surface p-4">
        <p className="mb-3 text-[11px] font-medium uppercase tracking-wider text-subtle">
          Score weights — drag to retune, ranking updates instantly
        </p>
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
          <WeightSlider
            label="Volume (all open items)"
            value={wVol}
            effectivePct={pVol}
            onChange={setWVol}
            color="#1f6feb"
          />
          <WeightSlider
            label="Velocity (new in last 30d)"
            value={wVel}
            effectivePct={pVel}
            onChange={setWVel}
            color="#3fb950"
          />
          <WeightSlider
            label="Avg engagement (reactions + comments)"
            value={wEng}
            effectivePct={pEng}
            onChange={setWEng}
            color="#d29922"
          />
        </div>
        <p className="mt-3 text-[11px] text-subtle">
          Formula:{' '}
          <code className="rounded bg-[#21262d] px-1.5 py-0.5">
            {pVol}% × volume + {pVel}% × velocity + {pEng}% × avg_engagement
          </code>
          {' '}(each normalized to the per-dataset max)
        </p>
      </div>

      {/* Ranked table */}
      <div className="rounded-lg border border-edge bg-surface overflow-hidden">
        <table className="w-full text-[13px]">
          <thead>
            <tr className="border-b border-edge">
              <th className="px-4 py-2.5 text-left text-[11px] font-medium uppercase tracking-wider text-subtle w-6">#</th>
              <th className="px-4 py-2.5 text-left text-[11px] font-medium uppercase tracking-wider text-subtle">Area</th>
              <th className="px-4 py-2.5 text-left text-[11px] font-medium uppercase tracking-wider text-subtle">Score</th>
              <th className="px-4 py-2.5 text-right text-[11px] font-medium uppercase tracking-wider text-[#1f6feb]">Volume</th>
              <th className="px-4 py-2.5 text-right text-[11px] font-medium uppercase tracking-wider text-[#3fb950]">New (30d)</th>
              <th className="px-4 py-2.5 text-right text-[11px] font-medium uppercase tracking-wider text-[#d29922]">Avg eng.</th>
              <th className="px-4 py-2.5 text-right text-[11px] font-medium uppercase tracking-wider text-subtle">Total eng.</th>
            </tr>
          </thead>
          <tbody>
            {ranked.map((e, i) => (
              <tr
                key={e.area}
                className="border-b border-edge last:border-0 hover:bg-[#1c2128] transition-colors cursor-pointer"
                onClick={() => setSelectedArea(e.area)}
                title={`View top items in ${e.area}`}
              >
                <td className="px-4 py-2.5 text-subtle tabular-nums">{i + 1}</td>
                <td className="px-4 py-2.5 font-medium text-accent hover:underline">{e.area}</td>
                <td className="px-4 py-2.5">
                  <ScoreBar score={e.score} max={maxScore} />
                </td>
                <td className="px-4 py-2.5 text-right tabular-nums text-subtle">{e.volume.toLocaleString()}</td>
                <td className="px-4 py-2.5 text-right tabular-nums">
                  <span className={e.velocity > 0 ? 'text-success' : 'text-subtle'}>
                    {e.velocity > 0 ? `+${e.velocity}` : '—'}
                  </span>
                </td>
                <td className="px-4 py-2.5 text-right tabular-nums text-subtle">{e.avgEngagement.toFixed(1)}</td>
                <td className="px-4 py-2.5 text-right tabular-nums text-subtle">{e.totalEngagement.toLocaleString()}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {selectedArea && (
        <ItemDrawer
          title={selectedArea}
          subtitle="Top items by engagement"
          filter={{ area: selectedArea, limit: 50 }}
          onClose={() => setSelectedArea(null)}
        />
      )}

      <p className="text-[11px] text-subtle">
        {entries.length} areas · area labels are{' '}
        <span className="rounded-full border border-[#6e40c9] px-2 py-0.5 text-[10px] text-purple">AI-suggested</span>
        {' '}· score aggregation is pure SQL + client-side math
      </p>
    </div>
  )
}
