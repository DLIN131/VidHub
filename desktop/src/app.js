import { store } from './store.js'
import * as api from './api.js'

// ---------- 小工具 ----------

const $ = (s, root = document) => root.querySelector(s)
const view = $('#view')

/** 建立元素：h('div', { class, onclick, … }, 子元素…)；子元素可以是陣列、字串，null / false 會略過 */
function h(tag, attrs = {}, ...kids) {
  const el = document.createElement(tag)
  for (const [k, v] of Object.entries(attrs || {})) {
    if (v === null || v === undefined || v === false) continue
    if (k.startsWith('on')) el.addEventListener(k.slice(2), v)
    else if (k === 'class') el.className = v
    else if (k === 'style' && typeof v === 'object') Object.assign(el.style, v)
    else if (k in el && typeof v !== 'string') el[k] = v
    else el.setAttribute(k, v === true ? '' : v)
  }
  for (const kid of kids.flat(Infinity)) {
    if (kid === null || kid === undefined || kid === false) continue
    el.append(kid instanceof Node ? kid : String(kid))
  }
  return el
}

const errText = (e) => String(e?.message || e).replace(/^Error invoking remote method '[^']+': (Error: )?/, '')

let toastTimer = null
function toast(msg, ms = 2600) {
  const t = $('#toast')
  t.textContent = msg
  t.classList.remove('hidden')
  clearTimeout(toastTimer)
  toastTimer = setTimeout(() => t.classList.add('hidden'), ms)
}

const spinner = () => h('div', { class: 'spinner' })
const loading = () => h('div', { class: 'status' }, spinner())
const status = (msg) => h('div', { class: 'status' }, msg)
const errorBox = (e, retry) => h('div', { class: 'status error' }, h('div', {}, errText(e)), retry ? h('button', { class: 'btn', onclick: retry }, '重試') : null)

/** 秒數 → h:mm:ss */
function fmtTime(sec) {
  sec = Math.max(0, Math.floor(sec || 0))
  const hh = Math.floor(sec / 3600)
  const mm = Math.floor((sec % 3600) / 60)
  const ss = String(sec % 60).padStart(2, '0')
  return hh ? `${hh}:${String(mm).padStart(2, '0')}:${ss}` : `${mm}:${ss}`
}
/** 觀看次數：1.2 萬 */
const fmtCount = (n) => (n >= 10000 ? `${(n / 10000).toFixed(n >= 100000 ? 0 : 1)} 萬` : String(n || 0))
const fmtDate = (s) => (s ? String(s).slice(0, 10) : '')

// ---------- 本機資料：收藏、觀看紀錄、播放進度 ----------

/** 收藏和紀錄只存顯示卡片需要的欄位；kind：v = 影片、s = 劇集 */
const brief = (x, kind) => ({ kind, id: x.id, title: x.title || '', cover: x.cover || '', duration: x.duration || 0, epLabel: x.epLabel || '', poster: !!x.poster })

const favorites = () => store.get('favorites', [])
const isFav = (id) => favorites().some((x) => x.id === id)
function toggleFav(x, kind) {
  const list = favorites().filter((f) => f.id !== x.id)
  const on = list.length === favorites().length
  if (on) list.unshift(brief(x, kind))
  store.set('favorites', list)
  return on
}

const history = () => store.get('history', [])
function addHistory(v) {
  store.set('history', [brief(v, 'v'), ...history().filter((x) => x.id !== v.id)].slice(0, 500))
}

/** 播放進度（秒），看完會清掉 */
const getPos = (id) => store.get('pos', {})[id] || 0
function setPos(id, sec) {
  const all = { ...store.get('pos', {}) }
  if (sec > 0) all[id] = Math.floor(sec)
  else delete all[id]
  store.set('pos', all)
}

/** 每部劇集最後看到第幾集（集的影片 id） */
const lastEpisode = (seriesId) => store.get('lastEp', {})[seriesId] || ''
function setLastEpisode(seriesId, videoId) {
  store.set('lastEp', { ...store.get('lastEp', {}), [seriesId]: videoId })
}

// ---------- 導覽：頁面堆疊（返回時列表和捲動位置都還在） ----------

const TABS = [
  ['home', '首頁'],
  ['videos', '影片'],
  ['series', '劇集'],
  ['cats', '分類'],
  ['favorites', '收藏'],
  ['history', '觀看紀錄'],
  ['settings', '設定'],
]
let stack = []
let cur = null

function renderTabs() {
  $('#tabs').replaceChildren(...TABS.map(([k, label]) => h('button', { class: `tab${cur?.tab === k ? ' active' : ''}`, 'data-tab': k, onclick: () => nav(k, {}, { root: true }) }, label)))
}

/** 開新頁面；root = 從上方分頁點進來（清掉返回紀錄） */
function nav(kind, params = {}, { root = false } = {}) {
  if (cur) {
    cur.scroll = view.scrollTop
    cur.hide?.()
    if (root) {
      for (const p of stack) p.dispose?.()
      cur.dispose?.()
      stack = []
    } else stack.push(cur)
  }
  const page = PAGES[kind](params)
  page.kind = kind
  page.tab = TABS.some(([k]) => k === kind) ? kind : cur?.tab || 'home'
  cur = page
  view.replaceChildren(page.el)
  view.scrollTop = 0
  renderTabs()
  $('#navBack').disabled = !stack.length
}

function goBack() {
  if (!$('#player').classList.contains('hidden')) return closePlayer()
  if (!$('#overlay').classList.contains('hidden')) return closeOverlay()
  if (!stack.length) return
  cur.dispose?.()
  cur = stack.pop()
  view.replaceChildren(cur.el)
  view.scrollTop = cur.scroll || 0
  cur.show?.()
  renderTabs()
  $('#navBack').disabled = !stack.length
}

// 捲到底自動載下一頁
view.addEventListener('scroll', () => {
  if (view.scrollHeight - view.scrollTop - view.clientHeight < 900) cur?.more?.()
})

// ---------- 卡片、格狀列表 ----------

function videoCard(v) {
  const thumb = h('div', { class: 'thumb' }, v.cover ? h('img', { src: v.cover, loading: 'lazy', alt: '' }) : null, v.duration ? h('span', { class: 'dur' }, fmtTime(v.duration)) : null, v.res >= 720 ? h('span', { class: 'res' }, `${v.res}P`) : null)
  const prog = getPos(v.id)
  if (prog > 5) thumb.append(h('span', { class: 'resume' }, `看到 ${fmtTime(prog)}`))
  const meta = [v.views ? `${fmtCount(v.views)} 次觀看` : '', v.tags?.[0] || ''].filter(Boolean).join('・')
  return h('div', { class: 'card', title: v.title, onclick: () => openVideo(v) }, thumb, h('div', { class: 'card-title' }, v.title), meta ? h('div', { class: 'card-meta' }, meta) : null)
}

function seriesCard(s) {
  const thumb = h('div', { class: `thumb${s.poster ? ' poster' : ''}` }, s.cover ? h('img', { src: s.cover, loading: 'lazy', alt: '' }) : null, s.epLabel ? h('span', { class: 'dur' }, s.epLabel) : null)
  const meta = [s.status, s.views ? `${fmtCount(s.views)} 次觀看` : ''].filter(Boolean).join('・')
  return h('div', { class: 'card', title: s.title, onclick: () => nav('show', s) }, thumb, h('div', { class: 'card-title' }, s.title), meta ? h('div', { class: 'card-meta' }, meta) : null)
}

/** 收藏、紀錄裡的卡片（brief 格式） */
const savedCard = (x) => (x.kind === 's' ? seriesCard(x) : videoCard(x))

/**
 * 可以一直往下捲的格狀列表。loader(page) → { items, hasMore }。
 * onFirst(r) 會在第一頁回來時呼叫（拿總數、篩選選項之類的）。
 */
function feed(loader, card = videoCard, onFirst = null) {
  const grid = h('div', { class: 'grid' })
  const foot = h('div', { class: 'foot' })
  const seen = new Set()
  let page = 0
  let busy = false
  let end = false
  let token = 0

  async function more() {
    if (busy || end) return
    busy = true
    const my = token
    foot.replaceChildren(loading())
    try {
      const r = await loader(page + 1)
      if (my !== token) return
      page++
      if (page === 1) onFirst?.(r)
      const fresh = r.items.filter((x) => !seen.has(x.id))
      fresh.forEach((x) => seen.add(x.id))
      grid.append(...fresh.map(card))
      if (!r.hasMore || !r.items.length) end = true
      foot.replaceChildren(end && !grid.children.length ? status('沒有內容') : '')
    } catch (e) {
      if (my === token) foot.replaceChildren(errorBox(e, more))
    } finally {
      if (my === token) busy = false
    }
    // 畫面還沒填滿就繼續載
    if (my === token && !end)
      requestAnimationFrame(() => {
        if (grid.isConnected && view.scrollHeight - view.scrollTop - view.clientHeight < 900) more()
      })
  }

  return {
    el: h('div', {}, grid, foot),
    more,
    reset(newLoader) {
      token++
      loader = newLoader
      seen.clear()
      page = 0
      busy = false
      end = false
      grid.replaceChildren()
      more()
    },
  }
}

/** 一排單選標籤 */
function chips(options, selected, onSelect) {
  const row = h('div', { class: 'chips' })
  const draw = (sel) =>
    row.replaceChildren(
      ...options.map(([k, label]) =>
        h('button', {
          class: `chip${k === sel ? ' active' : ''}`,
          onclick: () => {
            if (k === sel) return
            draw(k)
            onSelect(k)
          },
        }, label),
      ),
    )
  draw(selected)
  return row
}

const pageHead = (title, ...right) => h('div', { class: 'page-head' }, h('h1', {}, title), h('div', { class: 'spacer' }), ...right)
const sortRow = (label, row) => h('div', { class: 'sort-row' }, h('span', { class: 'label' }, label), row)
const tagChip = (t) => h('button', { class: 'chip', onclick: () => nav('tag', { tag: t }) }, t)

// ---------- 頁面 ----------

const PAGES = {
  home() {
    const body = h('div', {}, loading())
    async function load() {
      body.replaceChildren(loading())
      try {
        const sections = await api.home()
        body.replaceChildren(
          ...sections.map((s) =>
            h(
              'section',
              { class: 'shelf' },
              h('div', { class: 'shelf-head' }, h('h2', {}, s.title), h('div', { class: 'spacer' }), h('button', { class: 'more', onclick: () => nav(s.more[0], s.more[1]) }, '更多 ›')),
              h('div', { class: `grid${s.kind === 's' ? ' sgrid' : ''}` }, s.items.map(s.kind === 's' ? seriesCard : videoCard)),
            ),
          ),
        )
      } catch (e) {
        body.replaceChildren(errorBox(e, load))
      }
    }
    load()
    return { el: h('div', { class: 'page' }, body) }
  },

  /** 影片庫 */
  videos() {
    let order = store.get('videosOrder', 'createdAt')
    const f = feed((p) => api.videos(order, p))
    const el = h(
      'div',
      { class: 'page' },
      pageHead('影片'),
      sortRow('排序', chips(api.ORDERS, order, (k) => {
        order = k
        store.set('videosOrder', k)
        f.reset((p) => api.videos(order, p))
      })),
      f.el,
    )
    f.more()
    return { el, more: f.more }
  },

  /** 某個分類（標籤）的影片 */
  tag({ tag, order = 'createdAt' }) {
    const sib = h('div')
    const count = h('span', { class: 'muted' })
    const f = feed(
      (p) => api.tag(tag, order, p),
      videoCard,
      (r) => {
        count.textContent = r.total ? `${r.total.toLocaleString()} 部` : ''
        sib.replaceChildren(r.siblings.length ? sortRow('相關', h('div', { class: 'chips' }, r.siblings.filter((s) => s !== tag).slice(0, 30).map(tagChip))) : '')
      },
    )
    const el = h('div', { class: 'page' }, pageHead(tag, count), sortRow('排序', chips(api.ORDERS, order, (k) => ((order = k), f.reset((p) => api.tag(tag, order, p))))), sib, f.el)
    f.more()
    return { el, more: f.more }
  },

  /** 劇集庫 */
  series({ f: filter = 'all', sort = 'updated', tag = '' } = {}) {
    const o = { f: filter, sort, tag }
    const tags = h('div')
    const drawTags = (list) => tags.replaceChildren(list.length ? sortRow('類型', chips([['', '全部'], ...list.map((t) => [t, t])], o.tag, (k) => ((o.tag = k), reload()))) : '')
    const fd = feed((p) => api.seriesList(o, p), seriesCard, (r) => !tags.childElementCount && drawTags(r.tags))
    const reload = () => fd.reset((p) => api.seriesList(o, p))
    const el = h(
      'div',
      { class: 'page' },
      pageHead('劇集'),
      sortRow('狀態', chips(api.SERIES_FILTERS, o.f, (k) => ((o.f = k), reload()))),
      sortRow('排序', chips(api.SERIES_SORTS, o.sort, (k) => ((o.sort = k), reload()))),
      tags,
      h('div', { class: 'sgrid-wrap' }, fd.el),
    )
    fd.more()
    return { el, more: fd.more }
  },

  /** 分類一覽 */
  cats() {
    const body = h('div', {}, loading())
    async function load() {
      body.replaceChildren(loading())
      try {
        const c = await api.categories()
        body.replaceChildren(
          h(
            'div',
            { class: 'cat-grid' },
            c.cats.map((x) =>
              h('div', { class: 'cat-tile', onclick: () => nav('tag', { tag: x.id }) }, x.cover ? h('img', { src: x.cover, loading: 'lazy', alt: '' }) : null, h('div', { class: 'cat-name' }, x.id, h('span', {}, `${x.count.toLocaleString()} 部`))),
            ),
          ),
          ...c.cats.filter((x) => x.kids.length).map((x) => h('section', { class: 'cat-sec' }, h('h2', {}, x.id), h('div', { class: 'chips' }, x.kids.map((k) => h('button', { class: 'chip', onclick: () => nav('tag', { tag: k.id }) }, k.id, h('span', { class: 'cnt' }, k.count)))))),
          c.genres.length ? h('section', { class: 'cat-sec' }, h('h2', {}, '類型'), h('div', { class: 'chips' }, c.genres.map((k) => h('button', { class: 'chip', onclick: () => nav('tag', { tag: k.id }) }, k.id, h('span', { class: 'cnt' }, k.count))))) : null,
        )
      } catch (e) {
        body.replaceChildren(errorBox(e, load))
      }
    }
    load()
    return { el: h('div', { class: 'page' }, pageHead('分類'), body) }
  },

  search({ q }) {
    const o = { q, t: '', tab: 'video', sort: '' }
    const count = h('span', { class: 'muted' })
    const tabs = h('div')
    // 分類篩選的選項從網站的分類一覽來
    const catRow = h('div')
    api.searchCats().then((cats) => catRow.replaceChildren(sortRow('分類', chips(cats, o.t, (k) => ((o.t = k), reload()))))).catch(() => {})
    const drawTabs = (r) =>
      tabs.replaceChildren(
        chips([['video', `影片 ${r.totalVideos.toLocaleString()}`], ['series', `劇集 ${r.totalSeries.toLocaleString()}`]], o.tab, (k) => {
          o.tab = k
          reload()
        }),
      )
    const f = feed((p) => api.search(o, p), (x) => (o.tab === 'series' ? seriesCard(x) : videoCard(x)), (r) => {
      drawTabs(r)
      count.textContent = ''
    })
    const reload = () => f.reset((p) => api.search(o, p))
    const el = h(
      'div',
      { class: 'page' },
      pageHead(`搜尋：${q}`, count),
      tabs,
      catRow,
      sortRow('排序', chips(api.SEARCH_SORTS, o.sort, (k) => ((o.sort = k), reload()))),
      f.el,
    )
    f.more()
    return { el, more: f.more }
  },

  favorites() {
    const body = h('div')
    const draw = () => {
      const list = favorites()
      const vs = list.filter((x) => x.kind !== 's')
      const ss = list.filter((x) => x.kind === 's')
      body.replaceChildren(
        list.length ? '' : status('還沒有收藏，在影片或劇集頁按「收藏」加入'),
        ss.length ? h('section', { class: 'shelf' }, h('div', { class: 'shelf-head' }, h('h2', {}, `劇集（${ss.length}）`)), h('div', { class: 'grid sgrid' }, ss.map(savedCard))) : '',
        vs.length ? h('section', { class: 'shelf' }, h('div', { class: 'shelf-head' }, h('h2', {}, `影片（${vs.length}）`)), h('div', { class: 'grid' }, vs.map(savedCard))) : '',
      )
    }
    draw()
    return { el: h('div', { class: 'page' }, pageHead('收藏'), body), show: draw }
  },

  history() {
    const body = h('div')
    const draw = () => body.replaceChildren(history().length ? h('div', { class: 'grid' }, history().map(savedCard)) : status('還沒有觀看紀錄'))
    const clear = h('button', {
      class: 'btn',
      onclick: () => {
        if (!confirm('清除所有觀看紀錄和播放進度？')) return
        store.set('history', [])
        store.set('pos', {})
        store.set('lastEp', {})
        draw()
      },
    }, '清除紀錄')
    draw()
    return { el: h('div', { class: 'page' }, pageHead('觀看紀錄', clear), body), show: draw }
  },

  settings() {
    const ver = h('b', {}, '…')
    window.hub.version().then((v) => (ver.textContent = `v${v}`))
    const input = h('input', { class: 'text-input', type: 'text', spellcheck: false, placeholder: 'https://…' })
    const note = h('span', { class: 'muted' })
    window.hub.base().then((b) => {
      input.value = b.base
      note.textContent = b.isDefault ? '（預設網址）' : ''
    })
    const saveBase = async (value) => {
      try {
        const b = await window.hub.setBase(value)
        input.value = b
        note.textContent = value ? '已儲存' : '已還原預設網址'
        toast('網址已更新')
      } catch (e) {
        note.textContent = errText(e)
      }
    }
    return {
      el: h(
        'div',
        { class: 'page settings' },
        pageHead('設定'),
        h(
          'section',
          {},
          h('h2', {}, '網址設定'),
          h('div', { class: 'muted small' }, '網站換網址時，在這裡改成新網址。'),
          h('div', { class: 'row' }, input, h('button', { class: 'btn primary', onclick: () => saveBase(input.value) }, '儲存'), h('button', { class: 'btn', onclick: () => saveBase('') }, '還原預設'), note),
        ),
        h('section', {}, h('h2', {}, '版本'), h('div', { class: 'row' }, ver, h('button', { class: 'btn', onclick: () => window.hub.checkUpdate() }, '檢查更新'))),
        h(
          'section',
          {},
          h('h2', {}, '播放器快捷鍵'),
          h(
            'ul',
            { class: 'keys' },
            h('li', {}, '空白鍵／K：暫停、播放'),
            h('li', {}, '← →：倒退、快轉 10 秒；Shift + ← →：1 分鐘'),
            h('li', {}, '↑ ↓：音量；M：靜音；F 或雙擊畫面中間：全螢幕'),
            h('li', {}, '畫面左右兩側：雙擊倒退／快轉 10 秒，三擊 1 分鐘'),
            h('li', {}, '按住畫面：2 倍速播放，放開恢復'),
            h('li', {}, 'N：下一集（劇集）；Esc：離開全螢幕／關閉播放器'),
          ),
        ),
      ),
    }
  },

  /** 影片頁 */
  video(v) {
    let d = null
    const cover = h('div', { class: 'vp-cover' }, v.cover ? h('img', { src: v.cover, alt: '' }) : null)
    const title = h('h1', { class: 'vp-title' }, v.title || '')
    const meta = h('div', { class: 'vp-meta' })
    const actions = h('div', { class: 'vp-actions' }, loading())
    const tags = h('div', { class: 'chips' })
    const extra = h('div')
    const favBtn = h('button', { class: 'btn' })
    const drawFav = () => {
      const on = isFav(v.id)
      favBtn.textContent = on ? '♥ 已收藏' : '♡ 收藏'
      favBtn.classList.toggle('on', on)
    }
    favBtn.onclick = () => {
      toggleFav(d?.video || v, 'v')
      drawFav()
    }
    drawFav()

    async function load() {
      actions.replaceChildren(loading())
      try {
        d = await api.videoDetail(v.id)
        const x = d.video
        title.textContent = x.title
        if (x.cover) cover.replaceChildren(h('img', { src: x.cover, alt: '' }))
        meta.textContent = [x.code, x.duration ? fmtTime(x.duration) : '', x.res ? `${x.res}P` : '', `${fmtCount(x.views)} 次觀看`, fmtDate(x.date)].filter(Boolean).join('・')
        const resume = getPos(x.id)
        const queue = d.episodes.length ? d.episodes.map((e) => ({ ...e, seriesId: d.series?.id })) : null
        actions.replaceChildren(h('button', { class: 'btn primary big', onclick: () => openPlayer(x, { queue, series: d.series }) }, resume > 5 ? `▶ 繼續播放（${fmtTime(resume)}）` : '▶ 播放'), favBtn)
        tags.replaceChildren(...x.tags.map(tagChip))
        extra.replaceChildren(
          d.series
            ? h(
                'section',
                { class: 'shelf' },
                h('div', { class: 'shelf-head' }, h('h2', {}, `${d.series.title}（${d.series.epLabel}）`), h('div', { class: 'spacer' }), h('button', { class: 'more', onclick: () => nav('show', d.series) }, '劇集頁 ›')),
                episodeList(d.episodes, x.id, (e) => openPlayer(e, { queue, series: d.series })),
              )
            : '',
          d.related.length ? h('section', { class: 'shelf' }, h('div', { class: 'shelf-head' }, h('h2', {}, '相關影片')), h('div', { class: 'grid' }, d.related.map(videoCard))) : '',
          d.moreSeries.length ? h('section', { class: 'shelf' }, h('div', { class: 'shelf-head' }, h('h2', {}, '更多劇集')), h('div', { class: 'grid sgrid' }, d.moreSeries.map(seriesCard))) : '',
        )
      } catch (e) {
        actions.replaceChildren(errorBox(e, load), favBtn)
      }
    }
    load()
    return {
      el: h('div', { class: 'page' }, h('div', { class: 'video-page' }, cover, h('div', { class: 'vp-info' }, title, meta, actions, tags)), extra),
      // 從播放器回來：更新「繼續播放」的時間
      show: () => d && load(),
    }
  },

  /** 劇集頁 */
  show(s) {
    let d = null
    const cover = h('div', { class: `vp-cover${s.poster ? ' poster' : ''}` }, s.cover ? h('img', { src: s.cover, alt: '' }) : null)
    const title = h('h1', { class: 'vp-title' }, s.title || '')
    const meta = h('div', { class: 'vp-meta' })
    const desc = h('div', { class: 'vp-desc' })
    const actions = h('div', { class: 'vp-actions' }, loading())
    const tags = h('div', { class: 'chips' })
    const extra = h('div')
    const favBtn = h('button', { class: 'btn' })
    const drawFav = () => {
      const on = isFav(s.id)
      favBtn.textContent = on ? '♥ 已收藏' : '♡ 收藏'
      favBtn.classList.toggle('on', on)
    }
    favBtn.onclick = () => {
      toggleFav(d?.series || s, 's')
      drawFav()
    }
    drawFav()

    async function load() {
      actions.replaceChildren(loading())
      try {
        d = await api.seriesDetail(s.id)
        const x = d.series
        title.textContent = x.title
        if (x.cover) cover.replaceChildren(h('img', { src: x.cover, alt: '' }))
        cover.classList.toggle('poster', x.poster)
        meta.textContent = [x.epLabel, x.status, `${fmtCount(x.views)} 次觀看`].filter(Boolean).join('・')
        desc.textContent = x.description
        const queue = d.episodes.map((e) => ({ ...e, seriesId: x.id }))
        const lastId = lastEpisode(x.id)
        const last = queue.find((e) => e.id === lastId)
        const start = last || queue[0]
        actions.replaceChildren(
          start ? h('button', { class: 'btn primary big', onclick: () => openPlayer(start, { queue, series: x }) }, last ? `▶ 繼續看第 ${last.episode ?? ''} 集` : '▶ 從第 1 集開始') : h('span', { class: 'muted' }, '還沒有任何一集'),
          favBtn,
        )
        tags.replaceChildren(...x.tags.map((t) => h('button', { class: 'chip', onclick: () => nav('series', { tag: t }) }, t)))
        extra.replaceChildren(
          h('section', { class: 'shelf' }, h('div', { class: 'shelf-head' }, h('h2', {}, '選集')), episodeList(queue, lastId, (e) => openPlayer(e, { queue, series: x }))),
          d.more.length ? h('section', { class: 'shelf' }, h('div', { class: 'shelf-head' }, h('h2', {}, '更多劇集')), h('div', { class: 'grid sgrid' }, d.more.map(seriesCard))) : '',
        )
      } catch (e) {
        actions.replaceChildren(errorBox(e, load), favBtn)
      }
    }
    load()
    return {
      el: h('div', { class: 'page' }, h('div', { class: 'video-page' }, cover, h('div', { class: 'vp-info' }, title, meta, actions, tags, desc)), extra),
      show: () => d && load(),
    }
  },
}

/** 劇集的選集格子；current 是目前／上次看的那一集 */
function episodeList(eps, current, onPick) {
  return h(
    'div',
    { class: 'ep-grid' },
    eps.map((e) => {
      const pos = getPos(e.id)
      return h(
        'button',
        { class: `ep${e.id === current ? ' current' : ''}`, title: e.title, onclick: () => onPick(e) },
        h('div', { class: 'ep-thumb' }, e.cover ? h('img', { src: e.cover, loading: 'lazy', alt: '' }) : null, e.duration ? h('span', { class: 'dur' }, fmtTime(e.duration)) : null, pos > 5 ? h('span', { class: 'resume' }, `看到 ${fmtTime(pos)}`) : null),
        h('div', { class: 'ep-name' }, e.episode != null ? `第 ${e.episode} 集` : e.title),
      )
    }),
  )
}

function openVideo(v) {
  nav('video', v)
}

// ---------- 播放器 ----------

const SPEEDS = [0.75, 1, 1.25, 1.5, 2]
let player = null

/**
 * 開啟播放器。video：{ id, title, … }；
 * queue：劇集的所有集（看完自動播下一集），series：所屬劇集（記住看到第幾集）
 */
function openPlayer(video, { queue = null, series = null } = {}) {
  closePlayer({ keepPage: true })
  addHistory(video)
  if (series) setLastEpisode(series.id, video.id)
  const root = $('#player')
  root.classList.remove('hidden')
  const v = h('video', { class: 'pv', autoplay: true })
  v.volume = store.get('volume', 1)
  v.muted = store.get('muted', false)
  const hint = h('div', { class: 'p-hint hidden' })
  const wait = h('div', { class: 'p-wait' }, spinner())
  const err = h('div', { class: 'p-err hidden' })
  const played = h('div', { class: 'seek-played' })
  const buffered = h('div', { class: 'seek-buf' })
  const tip = h('div', { class: 'seek-tip hidden' })
  const seek = h('div', { class: 'seek' }, buffered, played, tip)
  const time = h('span', { class: 'p-time' }, '0:00 / 0:00')
  const playBtn = h('button', { class: 'pbtn', title: '暫停／播放（空白鍵）' }, '▶')
  const speedSel = h('select', { class: 'psel', title: '播放速度' }, SPEEDS.map((s) => h('option', { value: s, selected: s === 1 }, s === 1 ? '1x 倍速' : `${s}x`)))
  const vol = h('input', { class: 'pvol', type: 'range', min: 0, max: 1, step: 0.05, value: v.muted ? 0 : v.volume, title: '音量（↑ ↓）' })
  const fsBtn = h('button', { class: 'pbtn', title: '全螢幕（F）' }, '⛶')
  const closeBtn = h('button', { class: 'pbtn', title: '關閉（Esc）' }, '‹ 返回')
  const idx = queue ? queue.findIndex((e) => e.id === video.id) : -1
  const next = idx >= 0 && idx < queue.length - 1 ? queue[idx + 1] : null
  const nextBtn = next ? h('button', { class: 'pbtn', title: '下一集（N）' }, `下一集 ›`) : null
  const name = series ? `${series.title}${video.episode != null ? `　第 ${video.episode} 集` : ''}` : video.title
  const top = h('div', { class: 'p-top' }, closeBtn, h('div', { class: 'p-title' }, name))
  const bottom = h(
    'div',
    { class: 'p-bottom' },
    seek,
    h(
      'div',
      { class: 'p-row' },
      playBtn,
      h('button', { class: 'pbtn', title: '倒退 10 秒（←）', onclick: () => seekBy(-10) }, '«10'),
      h('button', { class: 'pbtn', title: '快轉 10 秒（→）', onclick: () => seekBy(10) }, '10»'),
      time,
      h('div', { class: 'spacer' }),
      nextBtn,
      speedSel,
      vol,
      fsBtn,
    ),
  )
  root.replaceChildren(v, wait, hint, err, top, bottom)

  const p = { video, v, hls: null, timers: [], speed: 1, boosting: false }
  player = p
  const start = getPos(video.id)

  // ---- 載入 ----
  function showError(msg) {
    wait.classList.add('hidden')
    err.replaceChildren(h('div', {}, msg), h('button', { class: 'btn', onclick: () => openPlayer(video, { queue, series }) }, '重試'))
    err.classList.remove('hidden')
  }
  if (window.Hls?.isSupported()) {
    const hls = new window.Hls({ maxBufferLength: 60, startPosition: start > 5 ? start : -1 })
    p.hls = hls
    let netRetry = 0
    let mediaRetry = 0
    hls.on(window.Hls.Events.MANIFEST_PARSED, () => v.play().catch(() => {}))
    hls.on(window.Hls.Events.FRAG_LOADED, () => (netRetry = 0))
    hls.on(window.Hls.Events.ERROR, (_e, data) => {
      if (!data.fatal) return
      if (data.type === window.Hls.ErrorTypes.NETWORK_ERROR && netRetry++ < 3) {
        flash('網路不穩，重新連線中…')
        setTimeout(() => hls.startLoad(), 1500)
      } else if (data.type === window.Hls.ErrorTypes.MEDIA_ERROR && mediaRetry++ < 3) hls.recoverMediaError()
      else showError(`播放失敗（${data.details}）`)
    })
    hls.loadSource(api.streamUrl(video.id))
    hls.attachMedia(v)
  } else showError('這台電腦不支援這種影片格式')
  if (start > 5) flash(`從 ${fmtTime(start)} 繼續播放`)

  // ---- 狀態顯示 ----
  const sync = () => {
    playBtn.textContent = v.paused ? '▶' : '❚❚'
    const d = v.duration || 0
    time.textContent = `${fmtTime(v.currentTime)} / ${fmtTime(d)}`
    played.style.width = d ? `${(v.currentTime / d) * 100}%` : '0'
    const b = v.buffered.length ? v.buffered.end(v.buffered.length - 1) : 0
    buffered.style.width = d ? `${(b / d) * 100}%` : '0'
  }
  for (const ev of ['timeupdate', 'play', 'pause', 'durationchange', 'progress']) v.addEventListener(ev, sync)
  v.addEventListener('waiting', () => wait.classList.remove('hidden'))
  for (const ev of ['playing', 'canplay', 'seeked']) v.addEventListener(ev, () => wait.classList.add('hidden'))
  v.addEventListener('pause', () => savePos())
  v.addEventListener('ended', () => {
    setPos(video.id, 0)
    if (next) {
      toast(`接著播放第 ${next.episode ?? ''} 集`)
      openPlayer(next, { queue, series })
    } else showControls()
  })
  v.addEventListener('volumechange', () => {
    store.set('volume', v.volume)
    store.set('muted', v.muted)
    vol.value = v.muted ? 0 : v.volume
  })
  p.timers.push(setInterval(() => !v.paused && savePos(), 5000))

  function savePos() {
    const d = v.duration || 0
    if (!v.currentTime) return
    // 看到最後 30 秒內就當作看完，下次從頭開始
    setPos(video.id, d && v.currentTime > d - 30 ? 0 : v.currentTime)
  }
  p.savePos = savePos

  // ---- 操作 ----
  const togglePlay = () => (v.paused ? v.play().catch(() => {}) : v.pause())
  function seekBy(sec) {
    const d = v.duration || 0
    v.currentTime = Math.max(0, Math.min(d ? d - 0.5 : Infinity, v.currentTime + sec))
    flash(sec >= 60 ? '快轉 1 分鐘' : sec > 0 ? `快轉 ${sec} 秒` : sec <= -60 ? '倒退 1 分鐘' : `倒退 ${-sec} 秒`)
  }
  p.seekBy = seekBy
  p.togglePlay = togglePlay
  p.next = next ? () => openPlayer(next, { queue, series }) : null
  function flash(text, ms = 900) {
    hint.textContent = text
    hint.classList.remove('hidden')
    clearTimeout(p.hintTimer)
    if (ms) p.hintTimer = setTimeout(() => hint.classList.add('hidden'), ms)
  }
  p.flash = flash
  const toggleFs = () => (document.fullscreenElement ? document.exitFullscreen() : root.requestFullscreen()).catch(() => {})
  p.toggleFs = toggleFs

  playBtn.onclick = togglePlay
  fsBtn.onclick = toggleFs
  closeBtn.onclick = () => closePlayer()
  if (nextBtn) nextBtn.onclick = p.next
  speedSel.onchange = () => {
    p.speed = Number(speedSel.value)
    v.playbackRate = p.speed
  }
  vol.oninput = () => {
    v.volume = Number(vol.value)
    v.muted = v.volume === 0
  }

  // 進度條：點、拖曳；滑過顯示時間
  const seekAt = (e) => {
    const r = seek.getBoundingClientRect()
    return Math.max(0, Math.min(1, (e.clientX - r.left) / r.width)) * (v.duration || 0)
  }
  seek.addEventListener('pointerdown', (e) => {
    seek.setPointerCapture(e.pointerId)
    v.currentTime = seekAt(e)
    const move = (ev) => (v.currentTime = seekAt(ev))
    seek.addEventListener('pointermove', move)
    seek.addEventListener('pointerup', () => seek.removeEventListener('pointermove', move), { once: true })
  })
  seek.addEventListener('mousemove', (e) => {
    const r = seek.getBoundingClientRect()
    tip.textContent = fmtTime(seekAt(e))
    tip.style.left = `${e.clientX - r.left}px`
    tip.classList.remove('hidden')
  })
  seek.addEventListener('mouseleave', () => tip.classList.add('hidden'))

  // 畫面上的滑鼠：按住 = 2 倍速；點一下 = 暫停／播放；左右兩側雙擊 ±10 秒、三擊 ±1 分鐘；中間雙擊 = 全螢幕
  let holdTimer = null
  let clicks = 0
  let clickTimer = null
  let lastX = 0
  v.addEventListener('pointerdown', (e) => {
    if (e.button !== 0) return
    holdTimer = setTimeout(() => {
      p.boosting = true
      v.playbackRate = 2
      if (v.paused) v.play().catch(() => {})
      flash('2 倍速播放中 ▶▶', 0)
    }, 450)
  })
  const endHold = () => {
    clearTimeout(holdTimer)
    if (!p.boosting) return false
    p.boosting = false
    v.playbackRate = p.speed
    hint.classList.add('hidden')
    return true
  }
  v.addEventListener('pointerleave', endHold)
  v.addEventListener('pointerup', (e) => {
    if (e.button !== 0) return
    if (endHold()) return
    clicks++
    lastX = e.clientX / root.clientWidth
    clearTimeout(clickTimer)
    const resolve = () => {
      const n = clicks
      clicks = 0
      const side = lastX < 0.33 ? -1 : lastX > 0.67 ? 1 : 0
      if (n === 1) togglePlay()
      else if (side === 0) toggleFs()
      else seekBy(side * (n >= 3 ? 60 : 10))
    }
    if (clicks >= 3) resolve()
    else clickTimer = setTimeout(resolve, 260)
  })
  v.addEventListener('dblclick', (e) => e.preventDefault())

  // 控制列：滑鼠一段時間沒動就藏起來（暫停時一直顯示）
  function showControls() {
    root.classList.remove('idle')
    clearTimeout(p.idleTimer)
    p.idleTimer = setTimeout(() => !v.paused && root.classList.add('idle'), 2500)
  }
  p.showControls = showControls
  // 換下一集時會重建播放器，用 onmousemove 才不會疊好幾個
  root.onmousemove = showControls
  showControls()
  sync()
}

function closePlayer({ keepPage = false } = {}) {
  const p = player
  if (!p) return
  player = null
  p.savePos?.()
  p.timers.forEach(clearInterval)
  clearTimeout(p.idleTimer)
  clearTimeout(p.hintTimer)
  p.hls?.destroy()
  p.v.pause()
  p.v.removeAttribute('src')
  p.v.load()
  if (keepPage) return
  if (document.fullscreenElement) document.exitFullscreen().catch(() => {})
  const root = $('#player')
  root.classList.add('hidden')
  root.replaceChildren()
  // 影片頁的「繼續播放」時間要更新
  cur?.show?.()
}

document.addEventListener(
  'keydown',
  (e) => {
    const p = player
    if (!p) return
    if (e.target.tagName === 'INPUT' && e.target.type !== 'range') return
    const v = p.v
    const k = e.key
    let handled = true
    if (k === ' ' || k === 'k' || k === 'K') p.togglePlay()
    else if (k === 'ArrowLeft' || k === 'j' || k === 'J') p.seekBy(e.shiftKey ? -60 : -10)
    else if (k === 'ArrowRight' || k === 'l' || k === 'L') p.seekBy(e.shiftKey ? 60 : 10)
    else if (k === 'ArrowUp') {
      v.muted = false
      v.volume = Math.min(1, v.volume + 0.1)
      p.flash(`音量 ${Math.round(v.volume * 100)}%`)
    } else if (k === 'ArrowDown') {
      v.volume = Math.max(0, v.volume - 0.1)
      p.flash(`音量 ${Math.round(v.volume * 100)}%`)
    } else if (k === 'm' || k === 'M') {
      v.muted = !v.muted
      p.flash(v.muted ? '靜音' : '取消靜音')
    } else if (k === 'f' || k === 'F') p.toggleFs()
    else if ((k === 'n' || k === 'N') && p.next) p.next()
    else if (k === 'Escape') {
      if (document.fullscreenElement) document.exitFullscreen().catch(() => {})
      else closePlayer()
    } else handled = false
    if (handled) {
      e.preventDefault()
      e.stopPropagation()
      p.showControls()
    }
  },
  true,
)

// ---------- 返回鍵 ----------

document.addEventListener('keydown', (e) => {
  if (player) return
  const typing = e.target.tagName === 'INPUT' || e.target.tagName === 'TEXTAREA'
  if ((e.altKey && e.key === 'ArrowLeft') || (e.key === 'Backspace' && !typing)) {
    e.preventDefault()
    goBack()
  } else if (e.key === 'Escape' && !$('#overlay').classList.contains('hidden')) closeOverlay()
})
window.addEventListener('mouseup', (e) => {
  if (e.button === 3) {
    e.preventDefault()
    goBack()
  }
})
window.hub.onBack(goBack)

// ---------- 對話框 ----------

function closeOverlay() {
  $('#overlay').classList.add('hidden')
  $('#overlay').replaceChildren()
}
$('#overlay').addEventListener('click', (e) => e.target.id === 'overlay' && closeOverlay())

// ---------- 啟動 ----------

$('.brand').onclick = () => nav('home', {}, { root: true })
$('#navBack').onclick = goBack

$('#searchForm').addEventListener('submit', (e) => {
  e.preventDefault()
  const q = $('#searchInput').value.trim()
  if (!q) return
  nav('search', { q })
})

window.addEventListener('beforeunload', () => player?.savePos?.())

nav('home', {}, { root: true })

// 測試用
window.__app = { nav, goBack, openVideo, openPlayer, closePlayer, get player() { return player } }
