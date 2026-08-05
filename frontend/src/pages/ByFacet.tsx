import { useState } from 'react'
import type { EChartsOption } from 'echarts'
import type { Facets, FacetCount } from '../api'
import { EChart } from '../components/EChart'
import { ItemDrawer } from '../components/ItemDrawer'
import { escapeHtml } from '../html'

const BASE_TEXT = { color: '#8b949e', fontSize: 12, fontFamily: 'inherit' }
const SPLIT_LINE = { lineStyle: { color: '#30363d' } }
const TOOLTIP_STYLE = {
  backgroundColor: '#1c2128',
  borderColor: '#30363d',
  textStyle: { color: '#e6edf3', fontSize: 13 },
}

function horizBarOption(data: FacetCount[], color = '#1f6feb', topN = 20): EChartsOption {
  const slice = data.slice(0, topN)
  const keys = slice.map((d) => d.key).reverse()
  const vals = slice.map((d) => d.count).reverse()
  return {
    backgroundColor: 'transparent',
    textStyle: BASE_TEXT,
    grid: { left: 8, right: 24, top: 8, bottom: 8, containLabel: true },
    xAxis: {
      type: 'value',
      axisLabel: { ...BASE_TEXT },
      splitLine: SPLIT_LINE,
      axisLine: { show: false },
    },
    yAxis: {
      type: 'category',
      data: keys,
      axisLabel: { ...BASE_TEXT, width: 120, overflow: 'truncate' },
      axisLine: { show: false },
      axisTick: { show: false },
    },
    series: [
      {
        type: 'bar',
        data: vals,
        itemStyle: { color, borderRadius: [0, 4, 4, 0] },
        barMaxWidth: 20,
        label: {
          show: true,
          position: 'right',
          color: '#8b949e',
          fontSize: 11,
          formatter: '{c}',
        },
      },
    ],
    tooltip: {
      trigger: 'axis',
      ...TOOLTIP_STYLE,
      formatter: (params: unknown) => {
        const p = (params as { name: string; value: number }[])[0]
        return `${escapeHtml(p.name)}: <b>${p.value}</b>`
      },
    },
  }
}

function enhancementKindOption(data: FacetCount[]): EChartsOption {
  const palette: Record<string, string> = {
    NEW_FEATURE: '#58a6ff',
    IMPROVEMENT: '#3fb950',
    NOT_APPLICABLE: '#30363d',
    UNSET: '#21262d',
  }
  const labels: Record<string, string> = {
    NEW_FEATURE: 'New Feature',
    IMPROVEMENT: 'Improvement',
    NOT_APPLICABLE: 'Not Applicable',
    UNSET: 'Unset',
  }
  return {
    backgroundColor: 'transparent',
    textStyle: BASE_TEXT,
    tooltip: {
      trigger: 'item',
      ...TOOLTIP_STYLE,
      // function form: ECharts does not HTML-escape the '{b}' template placeholder
      formatter: (p: unknown) => {
        const { name, value, percent } = p as { name: string; value: number; percent: number }
        return `${escapeHtml(name)}: <b>${value}</b> (${percent}%)`
      },
    },
    legend: {
      right: 0,
      top: 'center',
      orient: 'vertical',
      textStyle: { color: '#8b949e', fontSize: 12 },
    },
    series: [
      {
        type: 'pie',
        radius: ['38%', '65%'],
        center: ['38%', '50%'],
        data: data.map((d) => ({
          name: labels[d.key] ?? d.key,
          value: d.count,
          itemStyle: { color: palette[d.key] ?? '#58a6ff' },
        })),
        label: { show: false },
        emphasis: { label: { show: false } },
      },
    ],
  }
}

function Panel({
  title,
  children,
  className = '',
}: {
  title: string
  children: React.ReactNode
  className?: string
}) {
  return (
    <div className={`rounded-lg border border-edge bg-surface p-4 ${className}`}>
      <h2 className="mb-3 text-[11px] font-medium uppercase tracking-wider text-subtle">{title}</h2>
      {children}
    </div>
  )
}

function horizHeight(data: FacetCount[], topN = 20) {
  return Math.max(160, Math.min(topN, data.length) * 28 + 40)
}

interface Props {
  facets: Facets
}

export function ByFacet({ facets: f }: Props) {
  const enhancementTotal = f.byEnhancementKind.reduce((s, d) => s + d.count, 0)
  type DrawerFilter = { type?: string; area?: string; provider?: string; vectorStore?: string; severity?: string; enhancementKind?: string }
  const [drawer, setDrawer] = useState<{ title: string; filter: DrawerFilter } | null>(null)

  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const areaClick = (params: any) => { if (params.name) setDrawer({ title: params.name, filter: { area: params.name } }) }
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const typeClick = (params: any) => { if (params.name) setDrawer({ title: params.name, filter: { type: params.name } }) }
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const providerClick = (params: any) => { if (params.name) setDrawer({ title: params.name, filter: { provider: params.name } }) }
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const vectorStoreClick = (params: any) => { if (params.name) setDrawer({ title: params.name, filter: { vectorStore: params.name } }) }
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const severityClick = (params: any) => { if (params.name) setDrawer({ title: params.name, filter: { severity: params.name } }) }
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const enhancementClick = (params: any) => {
    const KEY: Record<string, string> = { 'New Feature': 'NEW_FEATURE', 'Improvement': 'IMPROVEMENT' }
    if (params.name && KEY[params.name]) setDrawer({ title: params.name, filter: { type: 'ENHANCEMENT', enhancementKind: KEY[params.name] } })
  }

  return (
    <div className="space-y-4">
      {drawer && (
        <ItemDrawer
          title={drawer.title}
          subtitle="Top items by engagement"
          filter={{ ...drawer.filter, limit: 50 }}
          onClose={() => setDrawer(null)}
        />
      )}

      {/* Top row: type + enhancement kind */}
      <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
        <Panel title="By type — click a bar to browse items">
          <EChart option={horizBarOption(f.byType, '#58a6ff')} height={horizHeight(f.byType)} onEvents={{ click: typeClick }} />
        </Panel>
        <Panel title={`Enhancement kind — click to browse`}>
          <EChart option={enhancementKindOption(f.byEnhancementKind)} height={200} onEvents={{ click: enhancementClick }} />
        </Panel>
      </div>

      {/* By area — full width, tall */}
      <Panel title={`By area — top ${Math.min(20, f.byArea.length)} · click a bar to browse items`}>
        <EChart option={horizBarOption(f.byArea, '#1f6feb', 20)} height={horizHeight(f.byArea, 20)} onEvents={{ click: areaClick }} />
      </Panel>

      {/* Provider + vector store */}
      <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
        <Panel title={`By provider — top ${Math.min(12, f.byProvider.length)} · click to browse`}>
          {f.byProvider.length > 0 ? (
            <EChart
              option={horizBarOption(f.byProvider, '#3fb950', 12)}
              height={horizHeight(f.byProvider, 12)}
              onEvents={{ click: providerClick }}
            />
          ) : (
            <p className="py-6 text-center text-sm text-subtle">No provider data</p>
          )}
        </Panel>
        <Panel title="By vector store — click to browse">
          {f.byVectorStore.length > 0 ? (
            <EChart
              option={horizBarOption(f.byVectorStore, '#d29922', 12)}
              height={horizHeight(f.byVectorStore, 12)}
              onEvents={{ click: vectorStoreClick }}
            />
          ) : (
            <p className="py-6 text-center text-sm text-subtle">No vector store data</p>
          )}
        </Panel>
      </div>

      {/* Severity */}
      <Panel title="By severity — click to browse">
        <EChart option={horizBarOption(f.bySeverity, '#f85149', 8)} height={horizHeight(f.bySeverity, 8)} onEvents={{ click: severityClick }} />
      </Panel>
    </div>
  )
}
