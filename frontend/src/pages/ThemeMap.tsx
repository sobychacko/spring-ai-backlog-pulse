import { useEffect, useMemo, useRef, useState } from 'react'
import { EChart } from '../components/EChart'
import { escapeHtml } from '../html'
import {
  fetchClusterItems,
  fetchClusters,
  fetchHeatmap,
  type ClusterEntry,
  type ClusterItem,
  type HeatmapData,
} from '../api'
import { ItemDrawer } from '../components/ItemDrawer'

const AREA_COLORS: Record<string, string> = {
  'mcp':               '#a371f7',
  'tool-calling':      '#f78166',
  'chat-client':       '#58a6ff',
  'chat-model':        '#79c0ff',
  'vector-store':      '#3fb950',
  'rag':               '#56d364',
  'embedding':         '#388bfd',
  'agents':            '#d2a8ff',
  'auto-config':       '#e3b341',
  'observability':     '#ffa657',
  'chat-memory':       '#2ea043',
  'structured-output': '#bc8cff',
  'core':              '#8b949e',
  'docs':              '#6e7681',
  'testing':           '#c9d1d9',
  'build':             '#484f58',
  'other':             '#6e7681',
}

function areaColor(area: string): string {
  return AREA_COLORS[area] ?? '#58a6ff'
}

const KIND_ICON: Record<string, string> = { issue: '◎', pr: '⇄' }

// ── Cluster drawer ────────────────────────────────────────────────────────────
function ClusterDrawer({
  cluster,
  onClose,
}: {
  cluster: ClusterEntry
  onClose: () => void
}) {
  const [items, setItems] = useState<ClusterItem[]>([])
  const [loading, setLoading] = useState(true)
  const area = cluster.dominantArea ?? 'other'
  const color = areaColor(area)
  const panelRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    fetchClusterItems(cluster.id)
      .then(setItems)
      .finally(() => setLoading(false))
  }, [cluster.id])

  // Close on ESC
  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if (e.key === 'Escape') onClose()
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [onClose])

  // Lock background scroll while drawer is open
  useEffect(() => {
    document.body.style.overflow = 'hidden'
    return () => { document.body.style.overflow = '' }
  }, [])

  // Trap focus inside drawer (accessibility)
  useEffect(() => {
    panelRef.current?.focus()
  }, [])

  return (
    <>
      {/* Backdrop */}
      <div
        className="fixed inset-0 z-40 bg-black/50 backdrop-blur-sm"
        onClick={onClose}
      />
      {/* Panel */}
      <div
        ref={panelRef}
        tabIndex={-1}
        className="fixed right-0 top-0 z-50 flex h-full w-full max-w-[520px] flex-col border-l border-edge bg-base outline-none shadow-2xl"
        style={{ animation: 'slideIn 180ms ease-out' }}
      >
        {/* Header */}
        <div className="flex shrink-0 items-start gap-3 border-b border-edge px-5 py-4">
          <div className="flex-1 min-w-0">
            <div className="mb-1 flex items-center gap-2">
              <span
                className="inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-[10px] font-medium"
                style={{ background: `${color}18`, color, border: `1px solid ${color}35` }}
              >
                <span className="inline-block h-1.5 w-1.5 rounded-full" style={{ background: color }} />
                {area}
              </span>
              <span className="text-[11px] text-subtle">{cluster.size} items · {cluster.totalEngagement} engagement</span>
            </div>
            <h3 className="text-[15px] font-semibold leading-snug text-body">{cluster.label}</h3>
          </div>
          <button
            onClick={onClose}
            className="shrink-0 rounded-md p-1 text-subtle transition-colors hover:bg-surface hover:text-body"
            aria-label="Close"
          >
            <svg width="16" height="16" viewBox="0 0 16 16" fill="currentColor">
              <path d="M3.72 3.72a.75.75 0 0 1 1.06 0L8 6.94l3.22-3.22a.749.749 0 1 1 1.06 1.06L9.06 8l3.22 3.22a.749.749 0 1 1-1.06 1.06L8 9.06l-3.22 3.22a.749.749 0 1 1-1.06-1.06L6.94 8 3.72 4.78a.75.75 0 0 1 0-1.06Z" />
            </svg>
          </button>
        </div>

        {/* Item list */}
        <div className="flex-1 overflow-y-auto">
          {loading ? (
            <div className="flex h-32 items-center justify-center text-[13px] text-subtle">Loading…</div>
          ) : items.length === 0 ? (
            <div className="flex h-32 items-center justify-center text-[13px] text-subtle">No items found.</div>
          ) : (
            <ul className="divide-y divide-edge">
              {items.map(item => (
                <li key={item.number} className="px-5 py-3.5 hover:bg-surface transition-colors">
                  {/* Kind + number + tags */}
                  <div className="mb-1 flex flex-wrap items-center gap-1.5 text-[11px] text-subtle">
                    <span title={item.kind}>{KIND_ICON[item.kind] ?? '○'}</span>
                    <span className="tabular-nums">#{item.number}</span>
                    {item.type && (
                      <span className="rounded-full border border-edge px-1.5 py-0.5">{item.type}</span>
                    )}
                    {item.severity && item.severity !== 'LOW' && (
                      <span className="rounded-full border border-warn px-1.5 py-0.5 text-warn">{item.severity}</span>
                    )}
                    {item.goodFirstIssue && (
                      <span className="rounded-full border border-success px-1.5 py-0.5 text-success">good first issue</span>
                    )}
                    <span className="ml-auto flex items-center gap-2">
                      <span>👍 {item.reactions}</span>
                      <span>💬 {item.comments}</span>
                    </span>
                  </div>
                  {/* Title — linked */}
                  <a
                    href={item.url}
                    target="_blank"
                    rel="noopener noreferrer"
                    className="block text-[13px] font-medium leading-snug text-accent hover:underline"
                  >
                    {item.title}
                  </a>
                  {/* Summary */}
                  {item.summary && (
                    <p className="mt-1 text-[12px] leading-relaxed text-subtle line-clamp-2">{item.summary}</p>
                  )}
                </li>
              ))}
            </ul>
          )}
        </div>
      </div>

      <style>{`
        @keyframes slideIn {
          from { transform: translateX(100%); opacity: 0; }
          to   { transform: translateX(0);    opacity: 1; }
        }
      `}</style>
    </>
  )
}

// ── Cluster card ──────────────────────────────────────────────────────────────
function ClusterCard({
  cluster,
  maxEngagement,
  onClick,
}: {
  cluster: ClusterEntry
  maxEngagement: number
  onClick: () => void
}) {
  const area = cluster.dominantArea ?? 'other'
  const color = areaColor(area)
  const barPct = maxEngagement > 0 ? Math.round((cluster.totalEngagement / maxEngagement) * 100) : 0

  return (
    <div
      role="button"
      tabIndex={0}
      onClick={onClick}
      onKeyDown={e => (e.key === 'Enter' || e.key === ' ') && onClick()}
      className="group flex cursor-pointer flex-col rounded-lg border border-edge bg-surface p-3.5 transition-all duration-150 hover:border-[#58a6ff44] hover:shadow-md focus:outline-none focus:ring-1 focus:ring-[#58a6ff]"
      style={{ ['--area-color' as string]: color }}
    >
      {/* Top row: area pill + item count */}
      <div className="mb-2 flex items-center gap-2">
        <span
          className="inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-[10px] font-medium"
          style={{ background: `${color}18`, color, border: `1px solid ${color}35` }}
        >
          <span className="inline-block h-1.5 w-1.5 rounded-full" style={{ background: color }} />
          {area}
        </span>
        <span className="ml-auto text-[11px] tabular-nums text-subtle">
          {cluster.size} item{cluster.size !== 1 ? 's' : ''}
        </span>
      </div>

      {/* Cluster name */}
      <p className="flex-1 text-[13px] font-medium leading-snug text-body">{cluster.label}</p>

      {/* Engagement bar */}
      <div className="mt-3">
        <div className="h-[3px] w-full overflow-hidden rounded-full bg-edge">
          <div
            className="h-full rounded-full transition-all duration-500"
            style={{ width: `${barPct}%`, background: color }}
          />
        </div>
        <div className="mt-1 flex items-center justify-between">
          <span className="text-[10px] text-subtle">engagement</span>
          <span className="text-[10px] tabular-nums text-subtle">{cluster.totalEngagement}</span>
        </div>
      </div>
    </div>
  )
}

// ── Card wall ─────────────────────────────────────────────────────────────────
function ClusterWall({ clusters }: { clusters: ClusterEntry[] }) {
  const [areaFilter, setAreaFilter] = useState<string | null>(null)
  const [sort, setSort] = useState<'engagement' | 'size'>('engagement')
  const [search, setSearch] = useState('')
  const [selectedCluster, setSelectedCluster] = useState<ClusterEntry | null>(null)

  // Areas with counts, sorted by total engagement desc
  const areas = useMemo(() => {
    const map = new Map<string, number>()
    for (const c of clusters) {
      const a = c.dominantArea ?? 'other'
      map.set(a, (map.get(a) ?? 0) + 1)
    }
    return Array.from(map.entries())
      .sort((a, b) => {
        const engA = clusters.filter(c => (c.dominantArea ?? 'other') === a[0]).reduce((s, c) => s + c.totalEngagement, 0)
        const engB = clusters.filter(c => (c.dominantArea ?? 'other') === b[0]).reduce((s, c) => s + c.totalEngagement, 0)
        return engB - engA
      })
  }, [clusters])

  const filtered = useMemo(() => {
    const q = search.trim().toLowerCase()
    return clusters
      .filter(c => !areaFilter || (c.dominantArea ?? 'other') === areaFilter)
      .filter(c => !q || c.label.toLowerCase().includes(q))
      .sort((a, b) =>
        sort === 'engagement'
          ? b.totalEngagement - a.totalEngagement
          : b.size - a.size
      )
  }, [clusters, areaFilter, sort, search])

  const maxEngagement = useMemo(
    () => Math.max(...clusters.map(c => c.totalEngagement), 1),
    [clusters]
  )

  if (clusters.length === 0) {
    return (
      <div className="py-16 text-center text-[13px] text-subtle">
        No clusters yet — run{' '}
        <strong className="text-accent">Admin → Embed items</strong> then{' '}
        <strong className="text-accent">Build theme clusters</strong>.
      </div>
    )
  }

  return (
    <div className="space-y-4">
      {/* Controls */}
      <div className="flex flex-wrap items-center gap-2">
        {/* Search */}
        <input
          type="search"
          placeholder="Search clusters…"
          value={search}
          onChange={e => setSearch(e.target.value)}
          className="h-7 w-48 rounded-md border border-edge bg-transparent px-2.5 text-[12px] text-body placeholder-subtle outline-none focus:border-[#58a6ff55]"
        />

        {/* Area filter chips */}
        <div className="flex flex-wrap gap-1.5">
          <button
            onClick={() => setAreaFilter(null)}
            className={`rounded-full px-2.5 py-0.5 text-[11px] transition-colors ${
              !areaFilter
                ? 'bg-primary text-white'
                : 'border border-edge text-subtle hover:text-body'
            }`}
          >
            All <span className="opacity-60">({clusters.length})</span>
          </button>
          {areas.map(([area, count]) => (
            <button
              key={area}
              onClick={() => setAreaFilter(areaFilter === area ? null : area)}
              className="rounded-full px-2.5 py-0.5 text-[11px] transition-colors"
              style={
                areaFilter === area
                  ? { background: areaColor(area), color: '#fff' }
                  : {
                      border: `1px solid ${areaColor(area)}40`,
                      color: areaFilter ? '#6e7681' : areaColor(area),
                    }
              }
            >
              {area} <span className="opacity-60">({count})</span>
            </button>
          ))}
        </div>

        {/* Sort toggle */}
        <div className="ml-auto flex rounded-md border border-edge text-[11px] overflow-hidden">
          <button
            onClick={() => setSort('engagement')}
            className={`px-2.5 py-1 transition-colors ${
              sort === 'engagement' ? 'bg-surface text-body' : 'text-subtle hover:text-body'
            }`}
          >
            Engagement
          </button>
          <button
            onClick={() => setSort('size')}
            className={`px-2.5 py-1 border-l border-edge transition-colors ${
              sort === 'size' ? 'bg-surface text-body' : 'text-subtle hover:text-body'
            }`}
          >
            Size
          </button>
        </div>
      </div>

      {/* Result count */}
      {(areaFilter || search) && (
        <p className="text-[12px] text-subtle">
          {filtered.length} cluster{filtered.length !== 1 ? 's' : ''}
          {areaFilter && (
            <span> in <span style={{ color: areaColor(areaFilter) }}>{areaFilter}</span></span>
          )}
          {search && <span> matching "{search}"</span>}
        </p>
      )}

      {/* Card grid */}
      {filtered.length === 0 ? (
        <p className="py-10 text-center text-[13px] text-subtle">No clusters match your filter.</p>
      ) : (
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3">
          {filtered.map(c => (
            <ClusterCard
              key={c.label}
              cluster={c}
              maxEngagement={maxEngagement}
              onClick={() => setSelectedCluster(c)}
            />
          ))}
        </div>
      )}

      {selectedCluster && (
        <ClusterDrawer cluster={selectedCluster} onClose={() => setSelectedCluster(null)} />
      )}
    </div>
  )
}

// ── Pulse heatmap ─────────────────────────────────────────────────────────────
function PulseHeatmap({ data, onCellClick }: { data: HeatmapData; onCellClick: (area: string, weekOf: string) => void }) {
  if (data.areas.length === 0 || data.weeks.length === 0) {
    return (
      <div className="flex h-40 items-center justify-center text-[13px] text-subtle">
        No heatmap data yet — classify items first.
      </div>
    )
  }

  const cutoff = Math.max(0, data.weeks.length - 26)
  const visWeeks = data.weeks.slice(cutoff)
  const visData = data.data
    .filter(d => d[0] >= cutoff)
    .map(d => [d[0] - cutoff, d[1], d[2]] as [number, number, number])
  const maxVal = Math.max(...visData.map(d => d[2]), 1)

  const option = {
    backgroundColor: 'transparent',
    tooltip: {
      backgroundColor: '#161b22',
      borderColor: '#30363d',
      borderWidth: 1,
      textStyle: { color: '#e6edf3', fontSize: 12 },
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      formatter: (p: { value: any }) =>
        `<span style="color:${areaColor(data.areas[p.value[1]])};font-weight:600">${escapeHtml(data.areas[p.value[1]])}</span>` +
        `<br/>${escapeHtml(visWeeks[p.value[0]])}<br/><span style="color:#8b949e">${p.value[2]} items</span>`,
    },
    grid: { top: 8, left: 110, right: 16, bottom: 56 },
    xAxis: {
      type: 'category',
      data: visWeeks,
      splitLine: { show: false },
      axisLine: { lineStyle: { color: '#21262d' } },
      axisTick: { show: false },
      axisLabel: { color: '#6e7681', fontSize: 10, rotate: 40, interval: 3 },
    },
    yAxis: {
      type: 'category',
      data: data.areas,
      splitLine: { show: false },
      axisLine: { lineStyle: { color: '#21262d' } },
      axisTick: { show: false },
      axisLabel: { color: '#8b949e', fontSize: 11 },
    },
    visualMap: {
      min: 0,
      max: maxVal,
      show: false,
      inRange: { color: ['#0d1117', '#0e4429', '#006d32', '#26a641', '#39d353'] },
    },
    series: [
      {
        type: 'heatmap',
        data: visData,
        label: { show: false },
        emphasis: { itemStyle: { shadowBlur: 12, shadowColor: 'rgba(57,211,83,0.4)' } },
        itemStyle: { borderRadius: 2 },
      },
    ],
  }

  return (
    <EChart
      option={option}
      style={{ height: Math.max(220, data.areas.length * 24 + 80) }}
      onEvents={{
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        click: (params: any) => {
          if (params.data && Array.isArray(params.data)) {
            const weekIdx = params.data[0] as number
            const areaIdx = params.data[1] as number
            const area = data.areas[areaIdx]
            const weekOf = visWeeks[weekIdx]
            if (area && weekOf) onCellClick(area, weekOf)
          }
        },
      }}
    />
  )
}

// ── Page ──────────────────────────────────────────────────────────────────────
export function ThemeMap() {
  const [clusters, setClusters] = useState<ClusterEntry[]>([])
  const [heatmap, setHeatmap] = useState<HeatmapData | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [heatmapCell, setHeatmapCell] = useState<{ area: string; weekOf: string } | null>(null)

  useEffect(() => {
    Promise.all([fetchClusters(), fetchHeatmap()])
      .then(([c, h]) => {
        setClusters(c.clusters)
        setHeatmap(h)
      })
      .catch(e => setError(String(e)))
      .finally(() => setLoading(false))
  }, [])

  if (loading) return <div className="py-16 text-center text-subtle">Loading…</div>
  if (error)
    return <div className="rounded-lg border border-danger bg-surface p-4 text-danger">{error}</div>

  const totalEngagement = clusters.reduce((s, c) => s + c.totalEngagement, 0)
  const topCluster = [...clusters].sort((a, b) => b.totalEngagement - a.totalEngagement)[0]
  const areaCount = new Set(clusters.map(c => c.dominantArea ?? 'other')).size

  return (
    <div className="space-y-10">
      {/* ── Theme map ── */}
      <section>
        <div className="mb-5">
          <div className="flex items-baseline justify-between gap-4">
            <h2 className="text-[15px] font-semibold">Theme Map</h2>
            <span className="text-[11px] text-subtle">
              labels are{' '}
              <span className="rounded-full border border-[#6e40c9] px-1.5 py-0.5 text-[10px] text-purple">
                AI-generated
              </span>
            </span>
          </div>

          {/* Stats row */}
          {clusters.length > 0 && (
            <div className="mt-3 flex flex-wrap gap-6">
              <div>
                <div className="text-[22px] font-bold tabular-nums leading-none text-body">
                  {clusters.length}
                </div>
                <div className="mt-0.5 text-[11px] text-subtle">clusters</div>
              </div>
              <div>
                <div className="text-[22px] font-bold tabular-nums leading-none text-body">
                  {areaCount}
                </div>
                <div className="mt-0.5 text-[11px] text-subtle">areas</div>
              </div>
              <div>
                <div className="text-[22px] font-bold tabular-nums leading-none text-body">
                  {totalEngagement.toLocaleString()}
                </div>
                <div className="mt-0.5 text-[11px] text-subtle">total engagement</div>
              </div>
              {topCluster && (
                <div>
                  <div
                    className="text-[14px] font-semibold leading-none"
                    style={{ color: areaColor(topCluster.dominantArea ?? 'other') }}
                  >
                    {topCluster.label}
                  </div>
                  <div className="mt-0.5 text-[11px] text-subtle">top cluster by engagement</div>
                </div>
              )}
            </div>
          )}
        </div>

        <ClusterWall clusters={clusters} />
      </section>

      {/* ── Pulse heatmap ── */}
      <section>
        <div className="mb-4">
          <h2 className="text-[15px] font-semibold">Pulse Heatmap</h2>
          <p className="mt-1 text-[12px] text-subtle">
            New issues &amp; PRs per area per week — last 26 weeks. Brighter green = more inflow.
          </p>
        </div>
        <div className="overflow-hidden rounded-xl border border-edge bg-[#0d1117] p-4">
          {heatmap ? (
            <PulseHeatmap data={heatmap} onCellClick={(area, weekOf) => setHeatmapCell({ area, weekOf })} />
          ) : (
            <div className="py-8 text-center text-subtle">No data</div>
          )}
        </div>
      </section>

      {heatmapCell && (
        <ItemDrawer
          title={heatmapCell.area}
          subtitle={`Week of ${heatmapCell.weekOf}`}
          filter={{ area: heatmapCell.area, weekOf: heatmapCell.weekOf, limit: 50 }}
          onClose={() => setHeatmapCell(null)}
        />
      )}
    </div>
  )
}
