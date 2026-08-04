export interface FacetCount {
  key: string
  count: number
}

export interface Facets {
  totalItems: number
  issues: number
  prs: number
  classified: number
  openItems: number
  goodFirstIssueCount: number
  byType: FacetCount[]
  byArea: FacetCount[]
  byProvider: FacetCount[]
  byEnhancementKind: FacetCount[]
  bySeverity: FacetCount[]
  byVectorStore: FacetCount[]
  ageHistogram: FacetCount[]
}

export interface ItemView {
  number: number
  kind: string
  title: string
  url: string
  reactions: number
  comments: number
  type: string | null
  area: string | null
  providers: string[]
  severity: string | null
  goodFirstIssue: boolean | null
  summary: string | null
}

export interface BackfillStatus {
  running: boolean
  lastResult: string | null
}

export async function fetchFacets(): Promise<Facets> {
  const r = await fetch('/api/facets')
  if (!r.ok) throw new Error(`facets: ${r.status}`)
  return r.json()
}

export async function fetchItems(params?: {
  type?: string
  area?: string
  weekOf?: string
  enhancementKind?: string
  provider?: string
  vectorStore?: string
  severity?: string
  ageDaysMin?: number
  ageDaysMax?: number
  goodFirstIssue?: boolean
  kind?: string
  search?: string
  limit?: number
}): Promise<ItemView[]> {
  const q = new URLSearchParams()
  if (params?.type) q.set('type', params.type)
  if (params?.area) q.set('area', params.area)
  if (params?.weekOf) q.set('weekOf', params.weekOf)
  if (params?.enhancementKind) q.set('enhancementKind', params.enhancementKind)
  if (params?.provider) q.set('provider', params.provider)
  if (params?.vectorStore) q.set('vectorStore', params.vectorStore)
  if (params?.severity) q.set('severity', params.severity)
  if (params?.ageDaysMin != null) q.set('ageDaysMin', String(params.ageDaysMin))
  if (params?.ageDaysMax != null) q.set('ageDaysMax', String(params.ageDaysMax))
  if (params?.goodFirstIssue != null) q.set('goodFirstIssue', String(params.goodFirstIssue))
  if (params?.kind) q.set('kind', params.kind)
  if (params?.search) q.set('search', params.search)
  q.set('limit', String(params?.limit ?? 50))
  const r = await fetch(`/api/items?${q}`)
  if (!r.ok) throw new Error(`items: ${r.status}`)
  return r.json()
}

export async function fetchBackfillStatus(): Promise<BackfillStatus> {
  const r = await fetch('/api/backfill/status')
  if (!r.ok) throw new Error(`status: ${r.status}`)
  return r.json()
}

export async function triggerIngest(): Promise<{ ingested: number }> {
  const r = await fetch('/api/ingest', { method: 'POST' })
  if (!r.ok) throw new Error(`ingest: ${r.status}`)
  return r.json()
}

export async function triggerBackfill(): Promise<void> {
  const r = await fetch('/api/backfill', { method: 'POST' })
  if (!r.ok) throw new Error(`backfill: ${r.status}`)
}

export async function triggerEmbed(): Promise<{ embedded: number; total: number }> {
  const r = await fetch('/api/embed', { method: 'POST' })
  if (!r.ok) throw new Error(`embed: ${r.status}`)
  return r.json()
}

export async function triggerScanDuplicates(threshold = 0.75): Promise<{ candidates: number }> {
  const r = await fetch(`/api/scan-duplicates?threshold=${threshold}`, { method: 'POST' })
  if (!r.ok) throw new Error(`scan-duplicates: ${r.status}`)
  return r.json()
}

export async function triggerCluster(): Promise<{ clusters: number }> {
  const r = await fetch('/api/cluster', { method: 'POST' })
  if (!r.ok) throw new Error(`cluster: ${r.status}`)
  return r.json()
}

export interface SyncStatus {
  running: boolean
  lastResult: string
  cursor: string
  lastRunAt: string
}

export interface PrView {
  number: number
  title: string
  url: string
  author: string
  authorAssoc: string
  createdAt: string
  updatedAt: string
  comments: number
  reactions: number
  draft: boolean
  baseBranch: string | null
  area: string | null
  summary: string | null
  reviewComplexity: string | null
  reviewNotes: string | null
  mainBranchApplicable: string | null
  mainBranchNote: string | null
  daysSinceUpdate: number
}

export async function fetchPRs(): Promise<PrView[]> {
  const r = await fetch('/api/prs')
  if (!r.ok) throw new Error(`prs: ${r.status}`)
  return r.json()
}

export async function triggerIngestPrBranches(): Promise<{ updated: number }> {
  const r = await fetch('/api/ingest-pr-branches', { method: 'POST' })
  if (!r.ok) throw new Error(`ingest-pr-branches: ${r.status}`)
  return r.json()
}

export async function triggerSync(): Promise<void> {
  const r = await fetch('/api/sync', { method: 'POST' })
  if (!r.ok && r.status !== 409) throw new Error(`sync: ${r.status}`)
}

export async function fetchSyncStatus(): Promise<SyncStatus> {
  const r = await fetch('/api/sync/status')
  if (!r.ok) throw new Error(`sync-status: ${r.status}`)
  return r.json()
}

export interface PulseEntry {
  area: string
  volume: number
  velocity: number
  avgEngagement: number
  totalEngagement: number
  pulseScore: number
}

export interface ValueItem {
  number: number
  kind: string
  title: string
  url: string
  reactions: number
  comments: number
  type: string | null
  area: string | null
  providers: string[]
  severity: string | null
  goodFirstIssue: boolean | null
  summary: string | null
  ageDays: number
  valueScore: number
  duplicateCount: number
}

export async function fetchPulse(): Promise<PulseEntry[]> {
  const r = await fetch('/api/pulse')
  if (!r.ok) throw new Error(`pulse: ${r.status}`)
  return r.json()
}

export async function fetchValue(limit = 25): Promise<ValueItem[]> {
  const r = await fetch(`/api/value?limit=${limit}`)
  if (!r.ok) throw new Error(`value: ${r.status}`)
  return r.json()
}

// MVP 4 — Clusters & Heatmap

export interface ClusterEntry {
  id: number
  label: string
  size: number
  totalEngagement: number
  dominantArea: string | null
}

export interface ClustersResponse {
  clusters: ClusterEntry[]
  total: number
}

export interface HeatmapData {
  areas: string[]
  weeks: string[]
  data: [number, number, number][]
}

export async function fetchClusters(): Promise<ClustersResponse> {
  const r = await fetch('/api/clusters')
  if (!r.ok) throw new Error(`clusters: ${r.status}`)
  return r.json()
}

export async function fetchHeatmap(): Promise<HeatmapData> {
  const r = await fetch('/api/heatmap')
  if (!r.ok) throw new Error(`heatmap: ${r.status}`)
  return r.json()
}

export interface ClusterItem {
  number: number
  kind: string
  title: string
  url: string
  reactions: number
  comments: number
  type: string | null
  area: string | null
  severity: string | null
  summary: string | null
  goodFirstIssue: boolean | null
}

export async function fetchClusterItems(clusterId: number): Promise<ClusterItem[]> {
  const r = await fetch(`/api/clusters/${clusterId}/items`)
  if (!r.ok) throw new Error(`cluster-items: ${r.status}`)
  return r.json()
}

// MVP 4 — Duplicate Review

export interface DuplicateItemDetail {
  number: number
  kind: string
  title: string
  url: string
  area: string | null
  summary: string | null
}

export interface DuplicatePair {
  id: number
  type: string
  confidence: number
  source: string
  from: DuplicateItemDetail
  to: DuplicateItemDetail
}

export interface DuplicatesResponse {
  pairs: DuplicatePair[]
  total: number
}

export async function fetchDuplicates(params?: {
  type?: string
  limit?: number
  offset?: number
}): Promise<DuplicatesResponse> {
  const q = new URLSearchParams()
  if (params?.type) q.set('type', params.type)
  q.set('limit', String(params?.limit ?? 50))
  q.set('offset', String(params?.offset ?? 0))
  const r = await fetch(`/api/duplicates?${q}`)
  if (!r.ok) throw new Error(`duplicates: ${r.status}`)
  return r.json()
}

export async function confirmDuplicate(id: number): Promise<void> {
  const r = await fetch(`/api/duplicates/${id}/confirm`, { method: 'POST' })
  if (!r.ok) throw new Error(`confirm: ${r.status}`)
}

export async function dismissDuplicate(id: number): Promise<void> {
  const r = await fetch(`/api/duplicates/${id}/dismiss`, { method: 'POST' })
  if (!r.ok) throw new Error(`dismiss: ${r.status}`)
}
