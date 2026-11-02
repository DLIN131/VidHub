// 注入到網頁裡：清掉站內廣告位、電視版的遙控器操作。電腦版、手機版、電視版共用。
// 電視版會在這段前面先設 window.__VH_TV = true
(function () {
  if (window.__vh) return
  window.__vh = true
  const TV = window.__VH_TV === true
  const HOP = 'a[href*="/api/hop/"]'

  const css = `
${HOP}, a[href="/app-download"], a[href="/fuli"], .rv-player-logo { display: none !important; }
iframe:not([src^="/"]):not([src^="${location.origin}"]) { display: none !important; }
${TV ? `
a:focus, button:focus, input:focus, [tabindex]:focus, [role="button"]:focus {
  outline: 3px solid #f59e0b !important; outline-offset: 2px !important; border-radius: 6px;
}
.rv-player:focus { outline: 4px solid #f59e0b !important; }
` : ''}`

  function addStyle() {
    if (document.getElementById('__vh_css')) return
    const s = document.createElement('style')
    s.id = '__vh_css'
    s.textContent = css
    ;(document.head || document.documentElement).appendChild(s)
  }

  function hide(el) {
    if (el && el.style.display !== 'none') el.style.setProperty('display', 'none', 'important')
  }

  const visible = (el) => el.style.display !== 'none' && el.offsetParent !== null

  /** 廣告連結外層的格子：裡面全是廣告就整塊藏起來，整排都空了再往上藏一層 */
  function clean() {
    addStyle()
    for (const a of document.querySelectorAll(HOP)) {
      const dialog = a.closest('[role="dialog"]')
      if (dialog) {
        // 彈出式廣告視窗：送 Esc 讓網站自己關（直接藏起來會留下鎖住捲動的遮罩）
        document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', code: 'Escape', keyCode: 27, bubbles: true }))
        hide(dialog)
        continue
      }
      const box = a.parentElement
      if (!box || box === document.body || box.id === 'main') continue
      if ([...box.children].every((c) => c.matches(HOP) || !visible(c))) {
        hide(box)
        const row = box.parentElement
        if (row && row.id !== 'main' && row !== document.body && ![...row.children].some(visible)) hide(row)
      }
    }
    for (const a of document.querySelectorAll('a[href="/app-download"], a[href="/fuli"]')) {
      const li = a.closest('li')
      hide(li || a)
    }
    // 頁尾清單裡的站外連結（姊妹站、廣告合作）；「地址發布」留著，網站換網址時要靠它找新網址
    for (const a of document.querySelectorAll('li > a[href^="http"]')) {
      if (a.host !== location.host && !a.textContent.includes('地址')) hide(a.parentElement)
    }
    // 「商務合作」整塊（廣告合作連結和聯絡信箱）
    for (const p of document.querySelectorAll('p')) {
      if (p.textContent.trim() === '商務合作') hide(p.parentElement)
    }
  }

  let queued = false
  function schedule() {
    if (queued) return
    queued = true
    setTimeout(() => {
      queued = false
      clean()
    }, 120)
  }

  function start() {
    clean()
    new MutationObserver(schedule).observe(document.documentElement, { childList: true, subtree: true })
    if (TV) tvSetup()
  }

  // ---------- 電視版：遙控器 ----------
  const mainVideo = () => document.querySelector('.rv-player .rv-player-video') || document.querySelector('.rv-player video')
  const player = () => document.querySelector('.rv-player')

  function togglePlay() {
    const v = mainVideo()
    const p = player()
    if (!v || !p) return false
    // 網站自己的大播放鈕（第一次播放要經過它，播放器才會開始載入）
    const big = [...p.children].find((c) => c.tagName === 'DIV' && c.style.zIndex === '8' && c.style.display !== 'none')
    if (v.paused) {
      if (big && v.currentTime === 0) big.click()
      else v.play().catch(() => {})
    } else {
      v.pause()
    }
    if (!document.fullscreenElement && p.requestFullscreen) p.requestFullscreen().catch(() => {})
    return true
  }

  function seek(sec) {
    const v = mainVideo()
    if (!v || !isFinite(v.duration)) return
    v.currentTime = Math.max(0, Math.min(v.duration - 1, v.currentTime + sec))
    toast((sec > 0 ? '快轉 ' : '倒轉 ') + Math.abs(sec) + ' 秒　' + fmt(v.currentTime) + ' / ' + fmt(v.duration))
  }

  const fmt = (s) => {
    s = Math.floor(s)
    const h = Math.floor(s / 3600)
    const m = Math.floor((s % 3600) / 60)
    const ss = String(s % 60).padStart(2, '0')
    return h ? `${h}:${String(m).padStart(2, '0')}:${ss}` : `${m}:${ss}`
  }

  let toastEl = null
  let toastTimer = null
  function toast(text) {
    const host = document.fullscreenElement || document.body
    if (!toastEl) {
      toastEl = document.createElement('div')
      toastEl.style.cssText = 'position:fixed;left:50%;bottom:8%;transform:translateX(-50%);background:rgba(0,0,0,.75);color:#fff;padding:10px 22px;border-radius:10px;font-size:22px;z-index:2147483647;pointer-events:none'
    }
    if (toastEl.parentElement !== host) host.appendChild(toastEl)
    toastEl.textContent = text
    toastEl.style.display = 'block'
    clearTimeout(toastTimer)
    toastTimer = setTimeout(() => (toastEl.style.display = 'none'), 1500)
  }

  // 方向鍵移動焦點：找該方向上最近、最對齊的可點元素
  const FOCUSABLE = 'a[href], button, input, select, textarea, [tabindex]:not([tabindex="-1"]), [role="button"]'
  const DIRS = { ArrowUp: 'up', ArrowDown: 'down', ArrowLeft: 'left', ArrowRight: 'right' }

  function candidates() {
    return [...document.querySelectorAll(FOCUSABLE)].filter((el) => {
      if (el.disabled) return false
      const r = el.getBoundingClientRect()
      if (r.width < 4 || r.height < 4) return false
      const s = getComputedStyle(el)
      return s.visibility !== 'hidden' && (el.offsetParent !== null || s.position === 'fixed')
    })
  }

  function reveal(el) {
    const r = el.getBoundingClientRect()
    const header = 90
    if (r.top < header || r.bottom > innerHeight - 10 || r.left < 0 || r.right > innerWidth) el.scrollIntoView({ block: 'center', inline: 'nearest' })
  }

  function move(dir) {
    const cur = document.activeElement && document.activeElement !== document.body ? document.activeElement : null
    const list = candidates()
    if (!cur) {
      const first = list.find((el) => {
        const r = el.getBoundingClientRect()
        return r.top >= 0 && r.bottom <= innerHeight
      })
      if (first) first.focus({ preventScroll: true })
      return
    }
    const a = cur.getBoundingClientRect()
    const acx = (a.left + a.right) / 2
    const acy = (a.top + a.bottom) / 2
    let best = null
    let bestScore = Infinity
    // 跟 Android 內建的焦點搜尋一樣：只看完全在那個方向的元素，分數 = 13 × 主軸距離² + 副軸中心差²
    const curInHeader = !!cur.closest('header')
    for (const el of list) {
      if (el === cur || el.contains(cur) || cur.contains(el)) continue
      // 左右移動不要從內容跳上頂端列（側邊欄按右鍵應該進到內容，而不是跑到上面的搜尋列）
      if ((dir === 'left' || dir === 'right') && !!el.closest('header') !== curInHeader) continue
      const b = el.getBoundingClientRect()
      let major, minor
      if (dir === 'right' || dir === 'left') {
        major = dir === 'right' ? b.left - a.right : a.left - b.right
        minor = (b.top + b.bottom) / 2 - acy
      } else {
        major = dir === 'down' ? b.top - a.bottom : a.top - b.bottom
        minor = (b.left + b.right) / 2 - acx
      }
      if (major < -8) continue
      major = Math.max(0, major)
      const score = 13 * major * major + minor * minor
      if (score < bestScore) {
        bestScore = score
        best = el
      }
    }
    if (best) {
      best.focus({ preventScroll: true })
      reveal(best)
    } else if (dir === 'up' || dir === 'down') {
      scrollBy(0, (dir === 'down' ? 1 : -1) * innerHeight * 0.6)
    }
  }

  function tvSetup() {
    document.addEventListener(
      'keydown',
      (e) => {
        const p = player()
        const inPlayer = !!p && (!!document.fullscreenElement || p.contains(document.activeElement))
        if (!inPlayer || !document.fullscreenElement) {
          const dir = DIRS[e.key]
          const el = document.activeElement
          const typing = el && (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA')
          if (dir && !(typing && (dir === 'left' || dir === 'right'))) {
            move(dir)
            e.preventDefault()
            e.stopPropagation()
            return
          }
          // 確認鍵：不是連結或按鈕的可點元素（例如 div role=button）幫它點一下
          if (!inPlayer && (e.key === 'Enter' || e.keyCode === 23) && el && el !== document.body && !/^(A|BUTTON|INPUT|SELECT|TEXTAREA)$/.test(el.tagName)) {
            el.click()
            e.preventDefault()
            return
          }
        }
        if (!inPlayer) return
        const k = e.key
        if (k === 'Enter' || k === ' ' || k === 'MediaPlayPause' || e.keyCode === 23 || e.keyCode === 85) {
          if (togglePlay()) e.preventDefault(), e.stopPropagation()
        } else if (document.fullscreenElement && (k === 'ArrowLeft' || k === 'ArrowRight')) {
          seek(k === 'ArrowLeft' ? -10 : 10)
          e.preventDefault()
          e.stopPropagation()
        } else if (document.fullscreenElement && (k === 'ArrowUp' || k === 'ArrowDown')) {
          seek(k === 'ArrowDown' ? -60 : 60)
          e.preventDefault()
          e.stopPropagation()
        } else if (k === 'MediaFastForward' || k === 'MediaRewind') {
          seek(k === 'MediaRewind' ? -10 : 10)
          e.preventDefault()
        }
      },
      true,
    )

    // 打開影片頁時，把焦點直接放在播放器上，按確認鍵就能播
    let lastPath = ''
    setInterval(() => {
      if (location.pathname === lastPath) return
      const p = player()
      if (!p) return
      lastPath = location.pathname
      p.scrollIntoView({ block: 'center' })
      p.focus({ preventScroll: true })
    }, 500)
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', start)
  else start()
})()
