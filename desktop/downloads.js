// 影片下載佇列（主程序）：可暫停 / 續傳，關掉程式再開會從上次的位置繼續。
// 播放清單和片段都包在 PNG 裡（見 source.js 的 unwrap），拆開後是一般的 TS 片段：
// 依序接進 .ts 暫存檔，全部下載完再用 ffmpeg 轉成 .mp4（不重新編碼，很快）。
// 寫法參考 GrabBox / MPlayer 的下載器。
const { app, ipcMain, dialog, shell, BrowserWindow, net } = require('electron')
const fs = require('original-fs')
const path = require('path')
const { spawn } = require('child_process')
const { pathToFileURL } = require('url')
const site = require('./site')
const paths = require('./paths')
const { unwrap, UA } = require('./source')

const MAX_RUNNING = 2
/** 每部影片同時抓幾個片段 */
const PARALLEL = 6
const RETRIES = 4
const jobsFile = () => path.join(app.getPath('userData'), 'downloads.json')
let jobs = []
let dir = ''
const running = new Map() // job.id → AbortController
let saveTimer = null
const sendTimers = new Map()
/** 程式正在關閉：被中斷的下載記成排隊中，下次開啟自動接著下 */
let quitting = false

// ---------- 播放清單 ----------

/** 抓一個網址、拆掉 PNG 包裝；回傳（內容, 轉址後的網址） */
async function fetchUnwrapped(url, signal) {
  const res = await net.fetch(url, { headers: { 'User-Agent': UA }, signal })
  if (!res.ok) throw new Error(`HTTP ${res.status}`)
  return { buf: unwrap(Buffer.from(await res.arrayBuffer())), url: res.url || url }
}

/** 播放清單 → 片段網址（網站只有一種畫質，不會是主清單） */
async function segments(id, signal) {
  const { buf, url } = await fetchUnwrapped(`${site.base()}/api/hls/${id}`, signal)
  const lines = buf.toString('utf8').split(/\r?\n/).map((l) => l.trim())
  if (lines[0] !== '#EXTM3U') throw new Error('讀不到影片的播放清單')
  const key = lines.find((l) => l.startsWith('#EXT-X-KEY:'))
  if (key && !/METHOD=NONE/.test(key)) throw new Error('這部影片有加密，無法下載')
  return lines.filter((l) => l && !l.startsWith('#')).map((l) => new URL(l, url).href)
}

// ---------- 工作清單 ----------

function load() {
  try {
    const d = JSON.parse(fs.readFileSync(jobsFile(), 'utf8'))
    jobs = Array.isArray(d.jobs) ? d.jobs : []
    dir = d.dir || ''
  } catch {}
  if (!dir) dir = paths.defaultDownloadDir()
  for (const j of jobs) {
    // 上次關程式時還在下載 / 轉檔的，這次接著做
    if (j.status === 'running' || j.status === 'merging') j.status = 'queued'
    j.speed = 0
  }
}

function save() {
  clearTimeout(saveTimer)
  saveTimer = setTimeout(flush, 500)
}

function flush() {
  clearTimeout(saveTimer)
  try {
    const f = jobsFile()
    fs.writeFileSync(f + '.tmp', JSON.stringify({ dir, jobs }))
    fs.renameSync(f + '.tmp', f)
  } catch {}
}

function publicJob(j) {
  return { ...j, fileUrl: j.status === 'done' && j.file ? pathToFileURL(j.file).href : '' }
}

/** 通知頁面；下載中每 300ms 最多送一次 */
function emit(j, now = false) {
  const send = () => {
    sendTimers.delete(j.id)
    for (const w of BrowserWindow.getAllWindows()) if (!w.isDestroyed()) w.webContents.send('dl:update', publicJob(j))
  }
  if (now) {
    clearTimeout(sendTimers.get(j.id))
    send()
  } else if (!sendTimers.has(j.id)) sendTimers.set(j.id, setTimeout(send, 300))
}

const clean = (s) =>
  String(s || '')
    .replace(/[\\/:*?"<>|\x00-\x1f]/g, ' ')
    .replace(/\s+/g, ' ')
    .trim()
    .replace(/[. ]+$/, '')
    .slice(0, 100) || 'video'

const partDir = () => path.join(dir, '.parts')
const partOf = (j) => path.join(partDir(), `${clean(j.id)}.ts`)

/** 檔名用影片標題；同名檔案已存在就加 (2)、(3)… */
function pickFile(j, ext) {
  fs.mkdirSync(dir, { recursive: true })
  const name = clean(j.title || j.id)
  for (let i = 1; ; i++) {
    const f = path.join(dir, `${name}${i > 1 ? ` (${i})` : ''}${ext}`)
    if (!fs.existsSync(f)) return f
  }
}

// ---------- 下載 ----------

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

/** TS 每 188 位元組一個封包、開頭都是 0x47；前面多了別的東西就從連續三個封包開頭的位置開始 */
function alignTs(buf) {
  if (buf.length < 188 * 3 || (buf[0] === 0x47 && buf[188] === 0x47)) return buf
  const limit = Math.min(buf.length - 188 * 2, 1024 * 1024)
  for (let i = 1; i < limit; i++) {
    if (buf[i] === 0x47 && buf[i + 188] === 0x47 && buf[i + 376] === 0x47) return buf.subarray(i)
  }
  return buf
}

async function fetchSegment(url, signal) {
  let last = null
  for (let attempt = 0; attempt < RETRIES; attempt++) {
    if (signal.aborted) throw new Error('aborted')
    try {
      return alignTs((await fetchUnwrapped(url, signal)).buf)
    } catch (e) {
      if (signal.aborted) throw e
      last = e
      await sleep(1000 * (attempt + 1))
    }
  }
  throw new Error(`網路錯誤：${last?.message || last}`)
}

const writeAsync = (out, buf) => new Promise((resolve, reject) => out.write(buf, (err) => (err ? reject(err) : resolve())))

async function download(j, signal) {
  // 片段網址有時效（簽章會過期），每次開始都重新拿播放清單
  const segs = await segments(j.id, signal)
  if (!segs.length) throw new Error('找不到影片片段')
  // 片段數變了（網站重新轉檔）就不能接著用舊的暫存檔
  if (j.total && j.total !== segs.length) j.done = j.bytes = 0
  j.total = segs.length
  fs.mkdirSync(partDir(), { recursive: true })
  const part = partOf(j)
  // 上次中斷時寫到一半的片段丟掉，從記錄的位置接著寫
  if (fs.existsSync(part)) fs.truncateSync(part, Math.min(j.bytes || 0, fs.statSync(part).size))
  else j.done = j.bytes = 0
  if (fs.existsSync(part) && fs.statSync(part).size !== j.bytes) j.done = j.bytes = 0
  const out = fs.createWriteStream(part, { flags: j.bytes ? 'a' : 'w' })
  // 同時抓好幾個片段，但依序寫入
  const pending = new Map()
  let next = j.done
  const fill = () => {
    while (pending.size < PARALLEL && next < segs.length) {
      const i = next++
      const p = fetchSegment(segs[i], signal)
      p.catch(() => {})
      pending.set(i, p)
    }
  }
  let windowStart = Date.now()
  let windowBytes = 0
  try {
    fill()
    while (j.done < segs.length) {
      const buf = await pending.get(j.done)
      pending.delete(j.done)
      await writeAsync(out, buf)
      j.done++
      j.bytes += buf.length
      windowBytes += buf.length
      const dt = Date.now() - windowStart
      if (dt > 2000) {
        j.speed = Math.round((windowBytes * 1000) / dt)
        windowStart = Date.now()
        windowBytes = 0
      }
      save()
      emit(j)
      fill()
    }
  } finally {
    await new Promise((r) => out.end(r))
  }
  return part
}

function ffmpegPath() {
  try {
    // 打包後 ffmpeg.exe 在 app.asar.unpacked 裡
    const p = require('ffmpeg-static').replace('app.asar' + path.sep, 'app.asar.unpacked' + path.sep)
    return fs.existsSync(p) ? p : ''
  } catch {
    return ''
  }
}

/** TS → MP4（只換容器，不重新編碼） */
function remux(input, outFile, signal) {
  const ffmpeg = ffmpegPath()
  if (!ffmpeg) return Promise.reject(new Error('找不到 ffmpeg'))
  // 只取影像和聲音，TS 裡的其他資料軌放進 MP4 會出錯
  const args = ['-hide_banner', '-loglevel', 'error', '-y', '-i', input, '-map', '0:v?', '-map', '0:a?', '-c', 'copy', '-movflags', '+faststart', outFile]
  return new Promise((resolve, reject) => {
    const p = spawn(ffmpeg, args, { windowsHide: true })
    let err = ''
    p.stderr.on('data', (d) => (err = (err + d).slice(-2000)))
    const onAbort = () => p.kill()
    signal.addEventListener('abort', onAbort, { once: true })
    p.on('error', reject)
    p.on('close', (code) => {
      signal.removeEventListener('abort', onAbort)
      if (code === 0 && fs.existsSync(outFile) && fs.statSync(outFile).size > 0) resolve()
      else {
        try {
          fs.rmSync(outFile, { force: true })
        } catch {}
        reject(new Error(err.trim() || 'ffmpeg 結束代碼 ' + code))
      }
    })
  })
}

async function runJob(j) {
  const ctl = new AbortController()
  running.set(j.id, ctl)
  j.status = 'running'
  j.error = ''
  j.speed = 0
  emit(j, true)
  try {
    const part = await download(j, ctl.signal)
    j.status = 'merging'
    j.speed = 0
    emit(j, true)
    const mp4 = pickFile(j, '.mp4')
    try {
      await remux(part, mp4, ctl.signal)
      fs.rmSync(part, { force: true })
      j.file = mp4
    } catch (e) {
      if (ctl.signal.aborted) throw e
      // 轉檔失敗就保留 .ts（大部分播放器也能播）
      const ts = pickFile(j, '.ts')
      fs.renameSync(part, ts)
      j.file = ts
      j.note = `轉成 MP4 失敗，保留 TS 檔：${e.message}`
    }
    try {
      j.size = fs.statSync(j.file).size
    } catch {}
    j.status = 'done'
    j.doneAt = Date.now()
  } catch (e) {
    if (ctl.signal.aborted) {
      if (j.status !== 'removed') j.status = quitting ? 'queued' : 'paused'
    } else {
      j.status = 'error'
      j.error = e.message || String(e)
    }
  } finally {
    running.delete(j.id)
    j.speed = 0
    if (j.status !== 'removed') {
      flush()
      emit(j, true)
    }
    pump()
  }
}

/** 有空位就開始下一個排隊中的（先加入的先下載） */
function pump() {
  if (quitting) return
  for (const j of [...jobs].reverse()) {
    if (running.size >= MAX_RUNNING) break
    if (j.status === 'queued' && !running.has(j.id)) runJob(j)
  }
}

/** v = { id, title, cover, duration } */
function add(v) {
  const id = String(v.id || '')
  if (!/^[a-z0-9]+$/i.test(id)) throw new Error('缺少影片資料')
  const old = jobs.find((j) => j.id === id)
  if (old && old.status === 'done' && old.file && fs.existsSync(old.file)) return publicJob(old)
  if (old) {
    if (old.status === 'done') remove(id, false)
    else {
      if (old.status !== 'running') old.status = 'queued'
      flush()
      pump()
      return publicJob(old)
    }
  }
  const j = {
    id,
    title: v.title || '',
    cover: v.cover || '',
    duration: Number(v.duration) || 0,
    status: 'queued',
    total: 0,
    done: 0,
    bytes: 0,
    speed: 0,
    error: '',
    addedAt: Date.now(),
  }
  jobs.unshift(j)
  flush()
  emit(j, true)
  pump()
  return publicJob(j)
}

function remove(id, deleteFile) {
  const j = jobs.find((x) => x.id === id)
  if (!j) return
  const ctl = running.get(id)
  j.status = 'removed'
  ctl?.abort()
  jobs = jobs.filter((x) => x !== j)
  flush()
  // 等正在寫的片段停下來再刪
  setTimeout(() => {
    try {
      fs.rmSync(partOf(j), { force: true })
    } catch {}
    if (deleteFile && j.file) {
      try {
        fs.rmSync(j.file, { force: true })
      } catch {}
    }
  }, 500)
  for (const w of BrowserWindow.getAllWindows()) if (!w.isDestroyed()) w.webContents.send('dl:removed', id)
}

function setup() {
  load()
  ipcMain.handle('dl:list', () =>
    jobs.map((j) => {
      // 已下載的檔案被使用者刪掉了
      if (j.status === 'done' && j.file && !fs.existsSync(j.file)) j.missing = true
      return publicJob(j)
    }),
  )
  ipcMain.handle('dl:add', (_e, v) => add(v))
  ipcMain.handle('dl:pause', (_e, id) => {
    const j = jobs.find((x) => x.id === id)
    if (!j) return
    if (running.has(id)) running.get(id).abort()
    else if (j.status === 'queued') {
      j.status = 'paused'
      flush()
      emit(j, true)
    }
  })
  ipcMain.handle('dl:resume', (_e, id) => {
    const j = jobs.find((x) => x.id === id)
    if (!j || running.has(id) || j.status === 'done') return
    j.status = 'queued'
    j.error = ''
    flush()
    emit(j, true)
    pump()
  })
  ipcMain.handle('dl:remove', (_e, id, deleteFile) => remove(id, deleteFile))
  ipcMain.handle('dl:show', (_e, id) => {
    const j = jobs.find((x) => x.id === id)
    if (j?.file && fs.existsSync(j.file)) shell.showItemInFolder(j.file)
    else shell.openPath(dir)
  })
  ipcMain.handle('dl:dir', () => dir)
  ipcMain.handle('dl:openDir', () => {
    fs.mkdirSync(dir, { recursive: true })
    return shell.openPath(dir)
  })
  ipcMain.handle('dl:chooseDir', async (e) => {
    const r = await dialog.showOpenDialog(BrowserWindow.fromWebContents(e.sender), { defaultPath: dir, properties: ['openDirectory', 'createDirectory'] })
    if (r.canceled || !r.filePaths[0]) return dir
    // 還在下載的暫存檔留在舊位置會接不上，先暫停全部
    for (const [, ctl] of running) ctl.abort()
    dir = r.filePaths[0]
    flush()
    return dir
  })
  app.on('before-quit', () => {
    quitting = true
    for (const [, ctl] of running) ctl.abort()
    // 程式關掉時還在下載的，下次開啟接著下
    for (const j of jobs) if (j.status === 'running' || j.status === 'merging') j.status = 'queued'
    flush()
  })
  // 程式開啟時自動接著下載上次沒下完的
  setTimeout(pump, 1500)
}

/** 程式正在下載（更新前要先確認） */
const busy = () => [...running.keys()].length > 0

module.exports = { setup, add, segments, busy }
