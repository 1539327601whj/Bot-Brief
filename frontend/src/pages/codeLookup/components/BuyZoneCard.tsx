import type { BuyZoneView } from '../types'
import { buyZoneStatusText, num, signedPct } from '../format'

interface Props {
  zone: BuyZoneView | null
  code: string
}

/**
 * 参考买入区间与参考止损位。
 *
 * 这两个词是章程允许的**规则计算参考位**，不是目标价、不是买入建议；
 * 卡片上把「不是目标价」写在数字旁边，而不是只放页脚。
 */
export default function BuyZoneCard({ zone, code }: Props) {
  if (!zone) return null

  const withheld = zone.status === 'WITHHELD' || zone.low === null || zone.high === null
  const digits = code.length >= 6 && code.startsWith('5') ? 3 : 2

  return (
    <div className={`cl-zone cl-zone-${withheld ? 'withheld' : zone.status.toLowerCase()}`}>
      <header>
        <h3>参考买入区间 <span>规则计算</span></h3>
        <span className="cl-zone-status">{buyZoneStatusText(zone.status)}</span>
      </header>

      {withheld ? (
        <div className="cl-zone-withheld">
          <p>
            <b>本次不给区间。</b>
            {zone.notes.length > 0 ? zone.notes.join('；') : '估值锚或技术支撑不满足计算条件。'}
          </p>
        </div>
      ) : (
        <>
          <div className="cl-zone-numbers">
            <div>
              <label>区间下沿</label>
              <strong>{num(zone.low, digits)}</strong>
            </div>
            <div>
              <label>区间上沿</label>
              <strong>{num(zone.high, digits)}</strong>
            </div>
            <div>
              <label>参考止损位</label>
              <strong className="cl-danger">{num(zone.stopLoss, digits)}</strong>
            </div>
            <div>
              <label>距区间上沿</label>
              <strong>{signedPct(zone.distanceToZonePct)}</strong>
            </div>
            <div>
              <label>止损距离</label>
              <strong>{zone.riskPct === null ? '未给' : `${num(zone.riskPct, 1)}%`}</strong>
            </div>
          </div>
          {zone.notes.length > 0 && (
            <ul className="cl-zone-notes">
              {zone.notes.map(note => <li key={note}>{note}</li>)}
            </ul>
          )}
          {zone.basis.length > 0 && (
            <details className="cl-details">
              <summary>区间是怎么算出来的</summary>
              <ul>
                {zone.basis.map(item => <li key={item.text}>{item.text}</li>)}
              </ul>
            </details>
          )}
          <p className="cl-zone-disclaimer">
            这是<b>规则计算出的参考位</b>，不是目标价，也不是买入建议；估值与支撑任一条件变化，
            区间就会变。
          </p>
        </>
      )}
    </div>
  )
}
