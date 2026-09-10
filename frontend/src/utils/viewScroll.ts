const positions = new Map<string, number>()
let ignoreSaves = 0

export function getWindowScrollY() {
  return window.scrollY || document.documentElement.scrollTop || 0
}

export function setWindowScrollY(y: number) {
  window.scrollTo(0, y)
}

export function saveViewScroll(key: string, y = getWindowScrollY()) {
  if (!key || ignoreSaves > 0) return
  positions.set(key, y)
}

export function readViewScroll(key: string) {
  return positions.get(key)
}

export function restoreViewScroll(y: number, timeoutMs = 2500) {
  ignoreSaves += 1
  const started = performance.now()
  let frame = 0
  let released = false

  const release = () => {
    if (released) return
    released = true
    ignoreSaves = Math.max(0, ignoreSaves - 1)
    cancelAnimationFrame(frame)
  }

  const tick = () => {
    const max = Math.max(0, document.documentElement.scrollHeight - window.innerHeight)
    setWindowScrollY(Math.min(y, max))
    const tallEnough = max >= y - 1
    const reached = Math.abs(getWindowScrollY() - Math.min(y, max)) <= 1
    if ((reached && tallEnough) || performance.now() - started > timeoutMs) {
      release()
      return
    }
    frame = requestAnimationFrame(tick)
  }
  frame = requestAnimationFrame(tick)
  return release
}

export function lockBodyScroll() {
  const y = getWindowScrollY()
  const previous = {
    overflow: document.body.style.overflow,
    position: document.body.style.position,
    top: document.body.style.top,
    width: document.body.style.width,
  }
  document.body.style.overflow = 'hidden'
  document.body.style.position = 'fixed'
  document.body.style.top = `-${y}px`
  document.body.style.width = '100%'
  return () => {
    document.body.style.overflow = previous.overflow
    document.body.style.position = previous.position
    document.body.style.top = previous.top
    document.body.style.width = previous.width
    setWindowScrollY(y)
  }
}
