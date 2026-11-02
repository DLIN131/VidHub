// 網站資料 → 介面用的格式。頁面資料由主程序抓（見 source.js / tsr.js），這裡只整理欄位。
// 網站改版時要改的是這個檔案（Android 版是 core 的 Api.kt）。

/** 影片 */
export const video = (v) => ({
  id: v.id,
  title: v.name || '',
  cover: v.coverImageUrl || '',
  duration: Number(v.duration) || 0,
  views: Number(v.viewCount) || 0,
  likes: Number(v.likeCount) || 0,
  tags: v.tags || [],
  date: v.createdAt || '',
  res: Math.max(0, ...(v.sources || []).map((s) => Number(s.resolution) || 0)),
  code: v.vid || '',
  seriesId: v.seriesId || null,
  episode: v.episode ?? null,
})

/** 劇集 */
export const series = (s) => ({
  id: s.id,
  title: s.name || '',
  cover: s.coverImageUrl || '',
  poster: !!s.isPoster,
  epLabel: s.epLabel || (s.episodeCount ? `${s.episodeCount} 集` : ''),
  status: s.status === 'COMPLETED' ? '已完結' : s.status === 'ONGOING' ? '連載中' : '',
  views: Number(s.viewCount) || 0,
  tags: s.tags || [],
  first: s.firstEpisodeId || s.coverVideoId || '',
  description: s.description || '',
})

/** 劇集裡的一集 */
const episode = (e) => ({ id: e.id, title: e.name || '', cover: e.coverImageUrl || '', duration: Number(e.duration) || 0, episode: e.episode ?? null, views: Number(e.viewCount) || 0 })

const get = (p) => window.hub.page(p)
const qs = (o) => {
  const s = new URLSearchParams(Object.entries(o).filter(([, v]) => v !== '' && v !== null && v !== undefined && v !== 1 && !(typeof v === 'number' && Number.isNaN(v)))).toString()
  return s ? `?${s}` : ''
}
const paged = (d, items) => ({ items, hasMore: (Number(d.pageNum) || 1) < (Number(d.totalPage) || 1), total: Number(d.totalVideoNum ?? d.total) || 0 })

/** 影片排序（影片庫、分類） */
export const ORDERS = [
  ['createdAt', '最新發布'],
  ['viewCount', '最多觀看'],
  ['likeCount', '最多喜歡'],
]

/** 劇集：狀態篩選、排序 */
export const SERIES_FILTERS = [
  ['all', '全部'],
  ['ongoing', '連載中'],
  ['completed', '已完結'],
  ['fresh', '本週更新'],
]
export const SERIES_SORTS = [
  ['updated', '最新更新'],
  ['hot', '最多觀看'],
  ['likes', '最多喜歡'],
  ['most', '集數最多'],
]

/** 搜尋：排序（分類篩選的選項從網站的分類一覽來，見 searchCats） */
export const SEARCH_SORTS = [
  ['', '相關度'],
  ['createdAt:desc', '最新發布'],
  ['viewCount:desc', '最多播放'],
  ['likeCount:desc', '最多喜歡'],
]

/**
 * 首頁的各個區塊。區塊的分類名稱不寫死在程式裡，從資料本身判斷：
 * 欄位名稱開頭 latest = 最新影片、daily = 今日熱門、hot = 最受歡迎、fresh/ranked = 劇集；
 * 分類是這一區影片最常見的第一個標籤。
 */
export async function home() {
  const d = await get('/home')
  const out = new Map()
  for (const [key, value] of Object.entries(d)) {
    if (key.startsWith('hero') || !Array.isArray(value) || !value.length || typeof value[0] !== 'object' || !value[0]) continue // hero 跟熱門劇集重複
    let shelf = null
    if ('episodeCount' in value[0]) {
      shelf = key.startsWith('fresh')
        ? { title: '本週更新劇集', kind: 's', more: ['series', { f: 'fresh' }], items: value.map(series) }
        : { title: '熱門劇集', kind: 's', more: ['series', { sort: 'hot' }], items: value.map(series) }
    } else if ('duration' in value[0]) {
      const items = value.map(video)
      if (key.startsWith('latest')) shelf = { title: '最新影片', kind: 'v', more: ['videos', {}], items }
      else {
        const count = {}
        for (const v of items) if (v.tags[0]) count[v.tags[0]] = (count[v.tags[0]] || 0) + 1
        const tag = Object.entries(count).sort((a, b) => b[1] - a[1])[0]?.[0]
        if (!tag) continue
        const hot = key.startsWith('hot')
        shelf = { title: `${hot ? '最受歡迎' : '今日熱門'}・${tag}`, kind: 'v', more: ['tag', hot ? { tag, order: 'likeCount' } : { tag }], items }
      }
    }
    if (shelf && !out.has(shelf.title)) out.set(shelf.title, shelf)
  }
  // 最新影片放最上面
  return [...out.values()].sort((a, b) => (a.more[0] === 'videos' ? 0 : 1) - (b.more[0] === 'videos' ? 0 : 1))
}

export async function videos(order, page) {
  const d = await get('/v' + qs({ order: order === 'createdAt' ? '' : order, page }))
  return paged(d, (d.videos || []).map(video))
}

export async function tag(name, order, page) {
  const d = await get(`/t/${encodeURIComponent(name)}` + qs({ order: order === 'createdAt' ? '' : order, page }))
  return { ...paged(d, (d.videos || []).map(video)), siblings: (d.siblings || []).map((s) => s.id), parent: d.tagParent || '' }
}

export async function seriesList({ f = 'all', sort = 'updated', tag = '' }, page) {
  const d = await get('/series' + qs({ f: f === 'all' ? '' : f, sort: sort === 'updated' ? '' : sort, tag, page }))
  return { ...paged(d, (d.list || []).map(series)), tags: (d.tagOptions || []).map((t) => t.id) }
}

export async function search({ q, t = '', tab = 'video', sort = '' }, page) {
  const d = await get('/search' + qs({ q, t, tab: tab === 'video' ? '' : tab, sort, page }))
  const items = tab === 'series' ? (d.seriesList || []).map(series) : (d.videos || []).map(video)
  return { ...paged(d, items), totalVideos: Number(d.totalVideoNum) || 0, totalSeries: Number(d.totalSeriesNum) || 0 }
}

let catsCache = null

/** 分類一覽（第一次抓完就記住，搜尋的分類篩選也用這份） */
export async function categories() {
  if (catsCache) return catsCache
  const d = await get('/cat')
  const t = d.taxonomy || {}
  const r = {
    cats: (t.cats || []).map((c) => ({ id: c.id, count: c.count, cover: c.coverImageUrl || '', kids: (t.byParent?.[c.id] || []).map((k) => ({ id: k.id, count: k.count })) })),
    genres: (t.genre || []).map((g) => ({ id: g.id, count: g.count })),
  }
  catsCache = r
  return r
}

/** 搜尋的分類篩選：全部 + 網站的主分類 */
export async function searchCats() {
  const c = await categories()
  return [['', '全部'], ...c.cats.map((x) => [x.id, x.id])]
}

export async function videoDetail(id) {
  const d = await get(`/v/${id}`)
  if (!d.video) throw new Error('找不到這部影片（可能已被刪除）')
  return {
    video: video(d.video),
    related: (d.relatedVideos || []).map(video),
    series: d.series ? series(d.series) : null,
    episodes: (d.episodes || []).map(episode),
    moreSeries: (d.moreSeries || []).map(series),
  }
}

export async function seriesDetail(id) {
  const d = await get(`/s/${id}`)
  if (!d.series) throw new Error('找不到這部劇集（可能已被刪除）')
  return { series: series(d.series), episodes: (d.episodes || []).map(episode), more: (d.more || []).map(series) }
}

/** 播放清單網址：主程序的 vh:// 代理會把包裝拆掉 */
export const streamUrl = (id) => `vh://hls/${id}`
