import { CSSProperties, useEffect, useRef } from 'react'
import * as echarts from 'echarts'

interface Props {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  option: any
  height?: number | string
  style?: CSSProperties
  className?: string
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  onEvents?: Record<string, (params: any, chart: echarts.ECharts) => void>
  onChartReady?: (chart: echarts.ECharts) => void
}

export function EChart({ option, height = 300, style, className, onEvents, onChartReady }: Props) {
  const divRef = useRef<HTMLDivElement>(null)
  const chartRef = useRef<echarts.ECharts | null>(null)

  useEffect(() => {
    if (!divRef.current) return
    const chart = echarts.init(divRef.current, null, { renderer: 'svg' })
    chartRef.current = chart
    onChartReady?.(chart)
    const obs = new ResizeObserver(() => chart.resize())
    obs.observe(divRef.current)
    return () => {
      obs.disconnect()
      chart.dispose()
      chartRef.current = null
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  useEffect(() => {
    const chart = chartRef.current
    if (!chart) return
    if (onEvents) {
      Object.entries(onEvents).forEach(([evt, handler]) => {
        chart.on(evt, (params) => handler(params, chart))
      })
    }
    return () => {
      if (onEvents) {
        Object.keys(onEvents).forEach((evt) => chart.off(evt))
      }
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  useEffect(() => {
    chartRef.current?.setOption(option, { notMerge: true })
  }, [option])

  const computedStyle: CSSProperties = {
    height: typeof height === 'number' ? `${height}px` : height,
    width: '100%',
    ...style,
  }

  return <div ref={divRef} className={className} style={computedStyle} />
}
