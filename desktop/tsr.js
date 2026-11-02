// 網頁裡內嵌的頁面資料（框架把伺服器端讀好的資料寫成一段 JS：$_TSR.router=($R=>$R[0]={…})(…)）。
// 不執行網站的程式碼，自己解析這種格式：物件、陣列、字串、數字、!0/!1、null，
// 以及「$R[n]=值」（定義）和「$R[n]」（重複出現時的參照）。Android 版的 Tsr.kt 是同一套規則。

class Parser {
  constructor(src, pos) {
    this.s = src
    this.i = pos
    this.refs = []
  }

  fail(msg) {
    throw new Error(`頁面資料格式不認得（${msg}，位置 ${this.i}）`)
  }

  ws() {
    while (this.i < this.s.length && /\s/.test(this.s[this.i])) this.i++
  }

  eat(str) {
    this.ws()
    if (this.s.startsWith(str, this.i)) {
      this.i += str.length
      return true
    }
    return false
  }

  value() {
    this.ws()
    const c = this.s[this.i]
    if (this.s.startsWith('$R[', this.i)) {
      this.i += 3
      const end = this.s.indexOf(']', this.i)
      const n = Number(this.s.slice(this.i, end))
      this.i = end + 1
      // 「$R[n]=」是定義，不是「==」
      if (this.s[this.i] === '=' && this.s[this.i + 1] !== '=') {
        this.i++
        const v = this.value()
        this.refs[n] = v
        return v
      }
      return this.refs[n]
    }
    if (c === '{') return this.object()
    if (c === '[') return this.array()
    if (c === '"' || c === "'") return this.string()
    if (this.eat('!0')) return true
    if (this.eat('!1')) return false
    if (this.eat('null')) return null
    if (this.eat('void 0')) return null
    if (this.eat('true')) return true
    if (this.eat('false')) return false
    const m = /^-?(?:\d+\.?\d*|\.\d+)(?:e[+-]?\d+)?/i.exec(this.s.slice(this.i, this.i + 40))
    if (m) {
      this.i += m[0].length
      return Number(m[0])
    }
    if (this.eat('Infinity')) return Infinity
    if (this.eat('NaN')) return NaN
    this.fail(`意外的字元 ${JSON.stringify(this.s.slice(this.i, this.i + 12))}`)
  }

  key() {
    this.ws()
    const c = this.s[this.i]
    if (c === '"' || c === "'") return this.string()
    const m = /^[A-Za-z_$][\w$]*|^\d+/.exec(this.s.slice(this.i, this.i + 200))
    if (!m) this.fail('物件的鍵')
    this.i += m[0].length
    return m[0]
  }

  object() {
    this.i++
    const o = {}
    if (this.eat('}')) return o
    for (;;) {
      const k = this.key()
      if (!this.eat(':')) this.fail('缺少冒號')
      o[k] = this.value()
      if (this.eat(',')) continue
      if (this.eat('}')) return o
      this.fail('物件沒有結束')
    }
  }

  array() {
    this.i++
    const a = []
    for (;;) {
      this.ws()
      if (this.eat(']')) return a
      // 空位（[1,,2]）
      if (this.s[this.i] === ',') {
        this.i++
        a.push(null)
        continue
      }
      a.push(this.value())
      if (this.eat(',')) continue
      if (this.eat(']')) return a
      this.fail('陣列沒有結束')
    }
  }

  string() {
    const q = this.s[this.i++]
    let out = ''
    for (;;) {
      const c = this.s[this.i++]
      if (c === undefined) this.fail('字串沒有結束')
      if (c === q) return out
      if (c !== '\\') {
        out += c
        continue
      }
      const e = this.s[this.i++]
      if (e === 'n') out += '\n'
      else if (e === 'r') out += '\r'
      else if (e === 't') out += '\t'
      else if (e === 'b') out += '\b'
      else if (e === 'f') out += '\f'
      else if (e === 'v') out += '\v'
      else if (e === '0') out += '\0'
      else if (e === 'x') {
        out += String.fromCharCode(parseInt(this.s.slice(this.i, this.i + 2), 16))
        this.i += 2
      } else if (e === 'u') {
        if (this.s[this.i] === '{') {
          const end = this.s.indexOf('}', this.i)
          out += String.fromCodePoint(parseInt(this.s.slice(this.i + 1, end), 16))
          this.i = end + 1
        } else {
          out += String.fromCharCode(parseInt(this.s.slice(this.i, this.i + 4), 16))
          this.i += 4
        }
      } else if (e === '\n') {
        // 續行
      } else out += e
    }
  }
}

const MARK = '$_TSR.router=($R=>'

/** 從頁面 HTML 取出這一頁的資料（最後一個路由的 loader 資料）；找不到回傳 null */
function pageData(html) {
  const at = html.indexOf(MARK)
  if (at < 0) return null
  const p = new Parser(html, at + MARK.length)
  const router = p.value()
  const matches = router?.matches || []
  for (let i = matches.length - 1; i >= 0; i--) {
    const l = matches[i]?.l
    if (l && typeof l === 'object') {
      delete l.ads
      return l
    }
  }
  return {}
}

module.exports = { pageData, Parser }
