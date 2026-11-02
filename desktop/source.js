// 主程序的網路：抓網站頁面取出資料、播放用的串流代理。
// 程式不開網站本身，網站的廣告和腳本完全不會載入。
const { net, protocol } = require('electron')
const zlib = require('zlib')
const site = require('./site')
const { pageData } = require('./tsr')

const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36'

/** 抓一頁，回傳頁面裡的資料；path 例如 /v?order=createdAt&page=2 */
async function page(path) {
  const url = site.base() + path
  const res = await net.fetch(url, { headers: { 'User-Agent': UA, 'Accept-Language': 'zh-TW,zh;q=0.9' } })
  if (res.status === 404) throw new Error('找不到這個頁面（可能已被刪除）')
  if (!res.ok) throw new Error(`網站回應錯誤 HTTP ${res.status}`)
  const data = pageData(await res.text())
  if (!data) throw new Error('讀不到頁面內容（網站可能改版或換網址，可以到「設定 → 網址設定」修改）')
  return data
}

// ---------- 串流 ----------
// 播放清單和影片片段都包在 PNG 圖檔裡（真正的內容在一個自訂類型的區塊，旗標 bit0 代表有 zlib 壓縮），
// 這裡拆出來再交給播放器，播放器看到的就是一般的 HLS。

const PNG = Buffer.from([137, 80, 78, 71, 13, 10, 26, 10])
const PAYLOAD = 0x726f5564

function unwrap(buf) {
  if (buf.length < 8 || !buf.subarray(0, 8).equals(PNG)) return buf
  let i = 8
  while (i + 8 <= buf.length) {
    const len = buf.readUInt32BE(i)
    const type = buf.readUInt32BE(i + 4)
    const d = i + 8
    if (d + len > buf.length) break
    if (type === PAYLOAD) {
      const body = buf.subarray(d + 1, d + len)
      return buf[d] & 1 ? zlib.inflateSync(body) : body
    }
    i = d + len + 4
  }
  throw new Error('影片資料格式不認得')
}

const SCHEME = 'vh'
const segUrl = (abs) => `${SCHEME}://seg/?u=${encodeURIComponent(abs)}`

/** 播放清單裡的網址（片段、子清單、金鑰）都改成走這個代理 */
function rewritePlaylist(text, baseUrl) {
  const abs = (u) => new URL(u, baseUrl).toString()
  return text
    .split(/\r?\n/)
    .map((line) => {
      const t = line.trim()
      if (!t) return line
      if (t.startsWith('#')) return line.replace(/URI="([^"]+)"/g, (_m, u) => `URI="${segUrl(abs(u))}"`)
      return segUrl(abs(t))
    })
    .join('\n')
}

const isPlaylist = (buf) => buf.subarray(0, 7).toString('latin1') === '#EXTM3U'

async function proxy(url) {
  const res = await net.fetch(url, { headers: { 'User-Agent': UA } })
  if (!res.ok) return new Response(null, { status: res.status })
  const body = unwrap(Buffer.from(await res.arrayBuffer()))
  if (isPlaylist(body)) {
    return new Response(rewritePlaylist(body.toString('utf8'), res.url || url), { headers: { 'Content-Type': 'application/vnd.apple.mpegurl' } })
  }
  return new Response(body, { headers: { 'Content-Type': 'video/mp2t' } })
}

/** vh://hls/<影片 id> → 這部影片的播放清單；vh://seg/?u=<網址> → 清單裡的片段 */
function registerScheme() {
  protocol.registerSchemesAsPrivileged([{ scheme: SCHEME, privileges: { standard: true, secure: true, supportFetchAPI: true, corsEnabled: true, stream: true } }])
}

function setupProtocol() {
  protocol.handle(SCHEME, async (req) => {
    try {
      const u = new URL(req.url)
      if (u.host === 'hls') {
        const id = u.pathname.replace(/^\//, '')
        if (!/^[a-z0-9]+$/i.test(id)) return new Response(null, { status: 400 })
        return await proxy(`${site.base()}/api/hls/${id}`)
      }
      if (u.host === 'seg') {
        const target = u.searchParams.get('u') || ''
        // 只代理影片 CDN 的網址
        if (!/^https:\/\/[^/]+\/hls\//.test(target)) return new Response(null, { status: 403 })
        return await proxy(target)
      }
      return new Response(null, { status: 404 })
    } catch (e) {
      return new Response(String(e.message || e), { status: 502 })
    }
  })
}

module.exports = { page, registerScheme, setupProtocol, unwrap, UA }
