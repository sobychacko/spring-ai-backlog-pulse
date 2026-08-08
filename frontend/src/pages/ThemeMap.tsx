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
  'image':             '#f778ba',
  'audio':             '#39c5cf',
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

// ── Theme treemap (hero) ──────────────────────────────────────────────────────
interface AreaGroupMeta {
  area: string
  themes: number
  items: number
  engagement: number
}

function ThemeTreemap({
  clusters,
  sizeBy,
  onSelect,
  onAreaSelect,
}: {
  clusters: ClusterEntry[]
  sizeBy: 'engagement' | 'items'
  onSelect: (c: ClusterEntry) => void
  onAreaSelect: (area: string) => void
}) {
  const option = useMemo(() => {
    // Two levels: area group (labeled header strip) -> theme clusters
    const byArea = new Map<string, ClusterEntry[]>()
    for (const c of clusters) {
      const a = c.dominantArea ?? 'other'
      const list = byArea.get(a)
      if (list) list.push(c)
      else byArea.set(a, [c])
    }
    const data = Array.from(byArea.entries()).map(([area, list]) => {
      const color = areaColor(area)
      const meta: AreaGroupMeta = {
        area,
        themes: list.length,
        items: list.reduce((s, c) => s + c.size, 0),
        engagement: list.reduce((s, c) => s + c.totalEngagement, 0),
      }
      return {
        name: area,
        areaGroup: meta,
        itemStyle: { color: `${color}0f`, borderColor: `${color}2b` },
        children: list.map(c => ({
          name: c.label,
          // Floor keeps zero/near-zero-engagement themes visible enough to hover
          value: sizeBy === 'engagement' ? Math.max(c.totalEngagement, 3) : c.size,
          cluster: c,
          itemStyle: { color: `${color}33` },
          emphasis: { itemStyle: { color: `${color}59` } },
        })),
      }
    })
    return {
      backgroundColor: 'transparent',
      tooltip: {
        backgroundColor: '#161b22',
        borderColor: '#30363d',
        borderWidth: 1,
        textStyle: { color: '#e6edf3', fontSize: 12 },
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        formatter: (p: any) => {
          const c: ClusterEntry | undefined = p.data?.cluster
          if (c) {
            const area = c.dominantArea ?? 'other'
            const sized =
              sizeBy === 'engagement'
                ? `<b>${c.totalEngagement} engagement</b> · ${c.size} items`
                : `<b>${c.size} items</b> · ${c.totalEngagement} engagement`
            return (
              `<div style="max-width:280px">` +
              `<div style="font-weight:600;margin-bottom:3px;white-space:normal">${escapeHtml(c.label)}</div>` +
              `<span style="color:${areaColor(area)}">${escapeHtml(area)}</span>` +
              `<span style="color:#8b949e"> · ${sized}</span>` +
              `<div style="color:#6e7681;margin-top:3px">click to view items</div>` +
              `</div>`
            )
          }
          const g: AreaGroupMeta | undefined = p.data?.areaGroup
          if (g) {
            return (
              `<span style="color:${areaColor(g.area)};font-weight:600">${escapeHtml(g.area)}</span>` +
              `<span style="color:#8b949e"> · ${g.themes} themes · ${g.items} items · ${g.engagement} engagement</span>` +
              `<div style="color:#6e7681;margin-top:3px">click to focus this area</div>`
            )
          }
          return ''
        },
      },
      series: [
        {
          type: 'treemap',
          left: 0,
          top: 0,
          right: 0,
          bottom: 0,
          roam: false,
          nodeClick: false,
          breadcrumb: { show: false },
          data,
          upperLabel: {
            show: true,
            height: 26,
            fontSize: 11,
            fontWeight: 600,
            color: '#e6edf3',
            backgroundColor: 'rgba(1,4,9,0.72)',
            padding: [3, 8],
            borderRadius: 4,
            overflow: 'truncate',
            ellipsis: '…',
            // eslint-disable-next-line @typescript-eslint/no-explicit-any
            formatter: (p: any) => {
              const g: AreaGroupMeta | undefined = p.data?.areaGroup
              return g ? `${g.area.toUpperCase()}  ·  ${g.themes}` : ''
            },
          },
          label: {
            show: true,
            position: 'insideTopLeft',
            padding: 8,
            overflow: 'break',
            lineOverflow: 'truncate',
            fontSize: 12,
            fontWeight: 600,
            color: '#e6edf3',
            lineHeight: 16,
            // eslint-disable-next-line @typescript-eslint/no-explicit-any
            formatter: (p: any) => p.data?.cluster?.label ?? '',
          },
          // Blocks too small to fit a word get no label at all (tooltip covers them)
          // eslint-disable-next-line @typescript-eslint/no-explicit-any
          labelLayout: (p: any) =>
            p.rect && (p.rect.width < 64 || p.rect.height < 30) ? { fontSize: 0 } : {},
          levels: [
            // level 0: virtual root
            { itemStyle: { borderWidth: 0, gapWidth: 4 }, upperLabel: { show: false } },
            // level 1: area groups — header strip + breathing room around children
            { itemStyle: { borderColor: '#0d1117', borderWidth: 4, gapWidth: 4 } },
            // level 2: theme blocks
            { itemStyle: { borderColor: '#0d1117', borderWidth: 1, gapWidth: 2 } },
          ],
          emphasis: {
            label: { color: '#ffffff' },
            itemStyle: { shadowBlur: 18, shadowColor: 'rgba(88,166,255,0.28)' },
          },
        },
      ],
    }
  }, [clusters, sizeBy])

  return (
    <EChart
      option={option}
      style={{ height: 'calc(100vh - 290px)', minHeight: 460 }}
      onEvents={{
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        click: (params: any) => {
          const c: ClusterEntry | undefined = params.data?.cluster
          if (c) {
            onSelect(c)
            return
          }
          // Group nodes are only hittable on their header strip (children cover the rest)
          const g: AreaGroupMeta | undefined = params.data?.areaGroup
          if (g) onAreaSelect(g.area)
        },
      }}
    />
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
      style={{ height: Math.max(300, data.areas.length * 28 + 90) }}
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
type View = 'map' | 'heatmap'

export function ThemeMap() {
  const [clusters, setClusters] = useState<ClusterEntry[]>([])
  const [heatmap, setHeatmap] = useState<HeatmapData | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [view, setView] = useState<View>('map')
  const [sizeBy, setSizeBy] = useState<'engagement' | 'items'>('engagement')
  const [areaFilter, setAreaFilter] = useState<string | null>(null)
  const [selectedCluster, setSelectedCluster] = useState<ClusterEntry | null>(null)
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

  // Areas present in the clusters, ordered by summed engagement (for legend chips)
  const areas = useMemo(() => {
    const eng = new Map<string, number>()
    const count = new Map<string, number>()
    for (const c of clusters) {
      const a = c.dominantArea ?? 'other'
      eng.set(a, (eng.get(a) ?? 0) + c.totalEngagement)
      count.set(a, (count.get(a) ?? 0) + 1)
    }
    return Array.from(count.entries())
      .map(([area, n]) => ({ area, count: n, engagement: eng.get(area) ?? 0 }))
      .sort((a, b) => b.engagement - a.engagement)
  }, [clusters])

  const visibleClusters = useMemo(
    () => (areaFilter ? clusters.filter(c => (c.dominantArea ?? 'other') === areaFilter) : clusters),
    [clusters, areaFilter]
  )

  if (loading) return <div className="py-16 text-center text-subtle">Loading…</div>
  if (error)
    return <div className="rounded-lg border border-danger bg-surface p-4 text-danger">{error}</div>

  const totalEngagement = clusters.reduce((s, c) => s + c.totalEngagement, 0)
  const topCluster = [...clusters].sort((a, b) => b.totalEngagement - a.totalEngagement)[0]

  return (
    <div className="space-y-5">
      {/* ── Header: view switcher + context ── */}
      <div className="flex flex-wrap items-center gap-3">
        <div className="flex gap-2">
          <button
            onClick={() => setView('map')}
            className={`rounded-xl px-4 py-2 text-left transition-all duration-150 ${
              view === 'map'
                ? 'bg-[#58a6ff1a] shadow-[0_0_24px_rgba(88,166,255,0.18)] ring-1 ring-[#58a6ff66]'
                : 'border border-edge opacity-60 hover:opacity-100'
            }`}
          >
            <span className={`block text-[13px] font-semibold ${view === 'map' ? 'text-[#79c0ff]' : 'text-body'}`}>
              ◈ Theme Map
            </span>
            <span className="block text-[10px] text-subtle">emergent themes, sized by engagement</span>
          </button>
          <button
            onClick={() => setView('heatmap')}
            className={`rounded-xl px-4 py-2 text-left transition-all duration-150 ${
              view === 'heatmap'
                ? 'bg-[#39d3531a] shadow-[0_0_24px_rgba(57,211,83,0.18)] ring-1 ring-[#39d35366]'
                : 'border border-edge opacity-60 hover:opacity-100'
            }`}
          >
            <span className={`block text-[13px] font-semibold ${view === 'heatmap' ? 'text-[#56d364]' : 'text-body'}`}>
              ▦ Pulse Heatmap
            </span>
            <span className="block text-[10px] text-subtle">weekly inflow per area, 26 weeks</span>
          </button>
        </div>

        {view === 'map' ? (
          <span className="ml-auto text-[11px] text-subtle">
            theme labels are{' '}
            <span className="rounded-full border border-[#6e40c9] px-1.5 py-0.5 text-[10px] text-purple">
              AI-generated
            </span>
          </span>
        ) : (
          <span className="ml-auto text-[11px] text-subtle">
            new issues &amp; PRs per area per week — last 26 weeks
          </span>
        )}
      </div>

      {view === 'map' ? (
        clusters.length === 0 ? (
          <div className="py-16 text-center text-[13px] text-subtle">
            No clusters yet — run{' '}
            <strong className="text-accent">Admin → Embed items</strong> then{' '}
            <strong className="text-accent">Build theme clusters</strong>.
          </div>
        ) : (
          <>
            {/* Stats + size-by toggle */}
            <div className="flex flex-wrap items-end gap-6">
              <div>
                <div className="text-[22px] font-bold tabular-nums leading-none text-body">{clusters.length}</div>
                <div className="mt-0.5 text-[11px] text-subtle">themes</div>
              </div>
              <div>
                <div className="text-[22px] font-bold tabular-nums leading-none text-body">{areas.length}</div>
                <div className="mt-0.5 text-[11px] text-subtle">areas</div>
              </div>
              <div>
                <div className="text-[22px] font-bold tabular-nums leading-none text-body">
                  {totalEngagement.toLocaleString()}
                </div>
                <div className="mt-0.5 text-[11px] text-subtle">total engagement</div>
              </div>
              {topCluster && (
                <div className="min-w-0">
                  <div
                    className="truncate text-[14px] font-semibold leading-none"
                    style={{ color: areaColor(topCluster.dominantArea ?? 'other') }}
                  >
                    {topCluster.label}
                  </div>
                  <div className="mt-0.5 text-[11px] text-subtle">top theme by engagement</div>
                </div>
              )}

              <div className="ml-auto flex items-center gap-2">
                <span className="text-[11px] text-subtle">block size</span>
                <div className="flex overflow-hidden rounded-md border border-edge text-[11px]">
                  <button
                    onClick={() => setSizeBy('engagement')}
                    className={`px-2.5 py-1 transition-colors ${
                      sizeBy === 'engagement' ? 'bg-surface text-body' : 'text-subtle hover:text-body'
                    }`}
                  >
                    Engagement
                  </button>
                  <button
                    onClick={() => setSizeBy('items')}
                    className={`border-l border-edge px-2.5 py-1 transition-colors ${
                      sizeBy === 'items' ? 'bg-surface text-body' : 'text-subtle hover:text-body'
                    }`}
                  >
                    Items
                  </button>
                </div>
              </div>
            </div>

            {/* Area legend chips (click to focus one area) */}
            <div className="flex flex-wrap gap-1.5">
              <button
                onClick={() => setAreaFilter(null)}
                className={`rounded-full px-2.5 py-0.5 text-[11px] transition-colors ${
                  !areaFilter ? 'bg-primary text-white' : 'border border-edge text-subtle hover:text-body'
                }`}
              >
                All <span className="opacity-60">({clusters.length})</span>
              </button>
              {areas.map(({ area, count }) => (
                <button
                  key={area}
                  onClick={() => setAreaFilter(areaFilter === area ? null : area)}
                  className="inline-flex items-center gap-1.5 rounded-full px-2.5 py-0.5 text-[11px] transition-colors"
                  style={
                    areaFilter === area
                      ? { background: areaColor(area), color: '#fff' }
                      : {
                          border: `1px solid ${areaColor(area)}40`,
                          color: areaFilter ? '#6e7681' : areaColor(area),
                        }
                  }
                >
                  <span
                    className="inline-block h-1.5 w-1.5 rounded-full"
                    style={{ background: areaFilter === area ? '#fff' : areaColor(area) }}
                  />
                  {area} <span className="opacity-60">({count})</span>
                </button>
              ))}
            </div>

            {/* The map */}
            <div className="overflow-hidden rounded-xl border border-edge bg-[#0d1117] p-2">
              <ThemeTreemap
                clusters={visibleClusters}
                sizeBy={sizeBy}
                onSelect={setSelectedCluster}
                onAreaSelect={area => setAreaFilter(prev => (prev === area ? null : area))}
              />
            </div>
          </>
        )
      ) : (
        <div className="overflow-hidden rounded-xl border border-edge bg-[#0d1117] p-4">
          {heatmap ? (
            <PulseHeatmap data={heatmap} onCellClick={(area, weekOf) => setHeatmapCell({ area, weekOf })} />
          ) : (
            <div className="py-8 text-center text-subtle">No data</div>
          )}
        </div>
      )}

      {selectedCluster && (
        <ClusterDrawer cluster={selectedCluster} onClose={() => setSelectedCluster(null)} />
      )}

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
