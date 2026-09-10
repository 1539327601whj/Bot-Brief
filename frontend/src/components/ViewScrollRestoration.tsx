import { useEffect, useLayoutEffect } from 'react'
import { useLocation, useNavigationType } from 'react-router-dom'
import { getWindowScrollY, readViewScroll, restoreViewScroll, saveViewScroll, setWindowScrollY } from '../utils/viewScroll'

/**
 * 记住每个历史记录的窗口滚动位置。点返回时回到离开前的位置，点新链接仍从顶部打开。
 */
export default function ViewScrollRestoration() {
  const location = useLocation()
  const navigationType = useNavigationType()

  useLayoutEffect(() => {
    return () => {
      saveViewScroll(location.key, getWindowScrollY())
    }
  }, [location.key])

  useLayoutEffect(() => {
    if (location.hash) {
      const id = decodeURIComponent(location.hash.slice(1))
      const el = id ? document.getElementById(id) : null
      if (el) {
        el.scrollIntoView()
        return
      }
    }
    if (navigationType === 'POP') {
      const y = readViewScroll(location.key)
      if (y != null && y > 0) return restoreViewScroll(y)
    }
    setWindowScrollY(0)
  }, [location.hash, location.key, navigationType])

  useEffect(() => {
    const persist = () => saveViewScroll(location.key, getWindowScrollY())
    window.addEventListener('scroll', persist, { passive: true })
    return () => window.removeEventListener('scroll', persist)
  }, [location.key])

  return null
}
