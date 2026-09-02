import { useState } from 'react'
import type { EChartsOption } from 'echarts'
import type { Facets } from '../api'
import { EChart } from '../components/EChart'
import { StatCard } from '../components/StatCard'
import { escapeHtml } from '../html'
import { ItemDrawer } from '../components/ItemDrawer'
import { TodaysPicks } from '../components/TodaysPicks'

// Shared palette / text style for all charts
const BASE_TEXT = { color: '#8b949e', fontSize: 12, fontFamily: 'inherit' }
const SPLIT_LINE = { lineStyle: { color: '#30363d' } }

function ageOption(data: Facets['ageHistogram']): EChartsOption {
  return {
    backgroundColor: 'transparent',
    textStyle: BASE_TEXT,
    grid: { left: 16, right: 16, top: 16, bottom: 40, containLabel: true },
    xAxis: {
      type: 'category',
      data: data.map((d) => d.key),
      axisLine: { lineStyle: { color: '#30363d' } },
      axisTick: { show: false },
      axisLabel: { ...BASE_TEXT },
    },
    yAxis: {
      type: 'value',
      axisLabel: { ...BASE_TEXT },
      splitLine: SPLIT_LINE,
      axisLine: { show: false },
    },
    series: [
      {
        type: 'bar',
        data: data.map((d) => d.count),
        itemStyle: { color: '#1f6feb', borderRadius: [4, 4, 0, 0] },
        barMaxWidth: 56,
        label: {
          show: true,
          position: 'top',
          color: '#8b949e',
          fontSize: 11,
          formatter: '{c}',
        },
      },
    ],
    tooltip: {
      trigger: 'axis',
      backgroundColor: '#1c2128',
      borderColor: '#30363d',
      textStyle: { color: '#e6edf3', fontSize: 13 },
      formatter: (params: unknown) => {
        const p = (params as { name: string; value: number }[])[0]
        return `${escapeHtml(p.name)}: <b>${p.value}</b>`
      },
    },
  }
}

function enhancementKindOption(data: Facets['byEnhancementKind']): EChartsOption {
  const palette: Record<string, string> = {
    NEW_FEATURE: '#58a6ff',
    IMPROVEMENT: '#3fb950',
    NOT_APPLICABLE: '#8b949e',
    UNSET: '#30363d',
  }
  const filtered = data.filter((d) => d.key !== 'NOT_APPLICABLE' && d.key !== 'UNSET')
  return {
    backgroundColor: 'transparent',
    textStyle: BASE_TEXT,
    tooltip: {
      trigger: 'item',
      backgroundColor: '#1c2128',
      borderColor: '#30363d',
      textStyle: { color: '#e6edf3', fontSize: 13 },
      // function form: ECharts does not HTML-escape the '{b}' template placeholder
      formatter: (p: unknown) => {
        const { name, value, percent } = p as { name: string; value: number; percent: number }
        return `${escapeHtml(name)}: <b>${value}</b> (${percent}%)`
      },
    },
    legend: {
      bottom: 4,
      textStyle: { color: '#8b949e', fontSize: 12 },
    },
    series: [
      {
        type: 'pie',
        radius: ['40%', '68%'],
        center: ['50%', '44%'],
        data: filtered.map((d) => ({
          name: d.key === 'NEW_FEATURE' ? 'New Feature' : 'Improvement',
          value: d.count,
          itemStyle: { color: palette[d.key] ?? '#58a6ff' },
        })),
        label: { show: false },
        emphasis: { label: { show: false } },
      },
    ],
  }
}

interface Props {
  facets: Facets
}

// Maps the age-bucket label (x-axis) to ageDaysMin / ageDaysMax query params
const AGE_BUCKET: Record<string, { ageDaysMin?: number; ageDaysMax?: number }> = {
  '<1 week':     { ageDaysMax: 7 },
  '1-4 weeks':   { ageDaysMin: 7,   ageDaysMax: 30 },
  '1-6 months':  { ageDaysMin: 30,  ageDaysMax: 180 },
  '6-12 months': { ageDaysMin: 180, ageDaysMax: 365 },
  '>1 year':     { ageDaysMin: 365 },
}

// Donut labels → enhancement_kind DB values
const ENHANCEMENT_KEY: Record<string, string> = {
  'New Feature': 'NEW_FEATURE',
  'Improvement': 'IMPROVEMENT',
}

export function Overview({ facets: f }: Props) {
  const pctClassified = f.totalItems ? Math.round((f.classified / f.totalItems) * 100) : 0
  const totalEnhancements = f.byEnhancementKind.reduce((s, d) => s + d.count, 0)
  const [selectedArea, setSelectedArea] = useState<string | null>(null)
  const [selectedType, setSelectedType] = useState<string | null>(null)
  const [ageBucket, setAgeBucket] = useState<string | null>(null)
  const [enhancementKind, setEnhancementKind] = useState<string | null>(null)
  const [showGoodFirstIssue, setShowGoodFirstIssue] = useState(false)

  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  function onAgeClick(params: any) {
    if (params.name && AGE_BUCKET[params.name]) setAgeBucket(params.name)
  }
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  function onEnhancementClick(params: any) {
    if (params.name && ENHANCEMENT_KEY[params.name]) setEnhancementKind(params.name)
  }

  return (
    <div className="space-y-6">
      {/* Today's picks — the morning list */}
      <TodaysPicks />

      {/* Stat cards */}
      <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-7">
        <StatCard label="Total items" value={f.totalItems.toLocaleString()} />
        <StatCard label="Open" value={f.openItems.toLocaleString()} color="accent" />
        <StatCard label="Issues" value={f.issues.toLocaleString()} />
        <StatCard label="PRs" value={f.prs.toLocaleString()} />
        <StatCard label="Classified" value={f.classified.toLocaleString()} color="success" />
        <StatCard
          label="% classified"
          value={`${pctClassified}%`}
          color={pctClassified === 100 ? 'success' : pctClassified > 80 ? 'warn' : 'danger'}
        />
        <StatCard label="Good first issues" value={f.goodFirstIssueCount.toLocaleString()} color="purple" onClick={() => setShowGoodFirstIssue(true)} />
      </div>

      {/* Charts row */}
      <div className="grid grid-cols-1 gap-4 lg:grid-cols-3">
        {/* Backlog age histogram */}
        <div className="lg:col-span-2 rounded-lg border border-edge bg-surface p-4">
          <h2 className="mb-3 text-[11px] font-medium uppercase tracking-wider text-subtle">
            Backlog age — click a bar to browse items
          </h2>
          {f.ageHistogram.length > 0 ? (
            <EChart option={ageOption(f.ageHistogram)} height={220} onEvents={{ click: onAgeClick }} />
          ) : (
            <p className="text-subtle text-sm py-8 text-center">No data</p>
          )}
        </div>

        {/* Enhancement kind donut */}
        <div className="rounded-lg border border-edge bg-surface p-4">
          <h2 className="mb-1 text-[11px] font-medium uppercase tracking-wider text-subtle">
            Enhancement kind — click to browse
          </h2>
          <p className="mb-2 text-[11px] text-subtle">
            {totalEnhancements.toLocaleString()} enhancements total
          </p>
          {f.byEnhancementKind.filter((d) => d.key !== 'NOT_APPLICABLE' && d.key !== 'UNSET').length > 0 ? (
            <EChart option={enhancementKindOption(f.byEnhancementKind)} height={200} onEvents={{ click: onEnhancementClick }} />
          ) : (
            <p className="text-subtle text-sm py-8 text-center">No data</p>
          )}
        </div>
      </div>

      {/* Clickable breakdowns */}
      {(f.byArea.length > 0 || f.byType.length > 0) && (
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          {/* Top areas */}
          {f.byArea.length > 0 && (
            <div className="rounded-lg border border-edge bg-surface p-4">
              <h2 className="mb-3 text-[11px] font-medium uppercase tracking-wider text-subtle">
                Items by area
              </h2>
              <ul className="space-y-1">
                {f.byArea.slice(0, 12).map(d => {
                  const max = f.byArea[0].count
                  const pct = max > 0 ? Math.round((d.count / max) * 100) : 0
                  return (
                    <li
                      key={d.key}
                      className="flex cursor-pointer items-center gap-2 rounded-md px-2 py-1.5 hover:bg-[#1c2128] transition-colors"
                      onClick={() => setSelectedArea(d.key)}
                    >
                      <span className="w-28 shrink-0 truncate text-[12px] text-accent hover:underline">{d.key}</span>
                      <div className="flex-1 h-1.5 rounded-full bg-edge overflow-hidden">
                        <div className="h-full rounded-full bg-[#1f6feb]" style={{ width: `${pct}%` }} />
                      </div>
                      <span className="w-8 text-right tabular-nums text-[12px] text-subtle">{d.count}</span>
                    </li>
                  )
                })}
              </ul>
            </div>
          )}

          {/* Top types */}
          {f.byType.length > 0 && (
            <div className="rounded-lg border border-edge bg-surface p-4">
              <h2 className="mb-3 text-[11px] font-medium uppercase tracking-wider text-subtle">
                Items by type
              </h2>
              <ul className="space-y-1">
                {f.byType.slice(0, 12).map(d => {
                  const max = f.byType[0].count
                  const pct = max > 0 ? Math.round((d.count / max) * 100) : 0
                  return (
                    <li
                      key={d.key}
                      className="flex cursor-pointer items-center gap-2 rounded-md px-2 py-1.5 hover:bg-[#1c2128] transition-colors"
                      onClick={() => setSelectedType(d.key)}
                    >
                      <span className="w-28 shrink-0 truncate text-[12px] text-accent hover:underline">{d.key}</span>
                      <div className="flex-1 h-1.5 rounded-full bg-edge overflow-hidden">
                        <div className="h-full rounded-full bg-[#3fb950]" style={{ width: `${pct}%` }} />
                      </div>
                      <span className="w-8 text-right tabular-nums text-[12px] text-subtle">{d.count}</span>
                    </li>
                  )
                })}
              </ul>
            </div>
          )}
        </div>
      )}

      {selectedArea && (
        <ItemDrawer
          title={selectedArea}
          subtitle="Top items by engagement"
          filter={{ area: selectedArea, limit: 50 }}
          onClose={() => setSelectedArea(null)}
        />
      )}
      {selectedType && (
        <ItemDrawer
          title={selectedType}
          subtitle="Top items by engagement"
          filter={{ type: selectedType, limit: 50 }}
          onClose={() => setSelectedType(null)}
        />
      )}
      {ageBucket && (
        <ItemDrawer
          title={ageBucket}
          subtitle="Items in this age range"
          filter={{ ...AGE_BUCKET[ageBucket], limit: 50 }}
          onClose={() => setAgeBucket(null)}
        />
      )}
      {showGoodFirstIssue && (
        <ItemDrawer
          title="Good First Issues"
          subtitle="Approachable for first-time contributors"
          filter={{ goodFirstIssue: true, limit: 50 }}
          onClose={() => setShowGoodFirstIssue(false)}
        />
      )}
      {enhancementKind && (
        <ItemDrawer
          title={enhancementKind}
          subtitle="Enhancement items"
          filter={{ type: 'ENHANCEMENT', enhancementKind: ENHANCEMENT_KEY[enhancementKind], limit: 50 }}
          onClose={() => setEnhancementKind(null)}
        />
      )}

      {/* AI notice */}
      <p className="text-[11px] text-subtle">
        Counts and ordering are GitHub facts. Classification fields (type, area, providers) are{' '}
        <span className="rounded-full border border-[#6e40c9] px-2 py-0.5 text-[10px] text-purple">
          AI-suggested
        </span>{' '}
        — derived from item text, never from invented facts.
      </p>
    </div>
  )
}
