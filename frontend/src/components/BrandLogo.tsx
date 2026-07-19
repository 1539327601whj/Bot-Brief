import { useId } from 'react'
import './BrandLogo.css'

type BrandLogoProps = {
  className?: string
  title?: string
}

export default function BrandLogo({ className = '', title = 'Brief logo' }: BrandLogoProps) {
  const id = useId().replace(/:/g, '')
  const glassTopId = `brand-logo-glass-top-${id}`
  const glassLeftId = `brand-logo-glass-left-${id}`
  const glassRightId = `brand-logo-glass-right-${id}`
  const coreId = `brand-logo-core-${id}`
  const metalId = `brand-logo-metal-${id}`
  const metalDarkId = `brand-logo-metal-dark-${id}`
  const glowId = `brand-logo-glow-${id}`

  return (
    <span className={`brand-logo ${className}`.trim()} role="img" aria-label={title}>
      <svg viewBox="0 0 48 48" aria-hidden="true" focusable="false">
        <defs>
          <linearGradient id={glassTopId} x1="11" y1="9" x2="38" y2="18" gradientUnits="userSpaceOnUse">
            <stop stopColor="#F7FBFF" stopOpacity="0.92" />
            <stop offset="0.5" stopColor="#AEBAC8" stopOpacity="0.62" />
            <stop offset="1" stopColor="#303947" stopOpacity="0.84" />
          </linearGradient>
          <linearGradient id={glassLeftId} x1="10" y1="16" x2="25" y2="40" gradientUnits="userSpaceOnUse">
            <stop stopColor="#DCE7EF" stopOpacity="0.8" />
            <stop offset="0.46" stopColor="#172530" stopOpacity="0.58" />
            <stop offset="1" stopColor="#02080C" stopOpacity="0.88" />
          </linearGradient>
          <linearGradient id={glassRightId} x1="38" y1="16" x2="23" y2="41" gradientUnits="userSpaceOnUse">
            <stop stopColor="#DDE8F0" stopOpacity="0.78" />
            <stop offset="0.45" stopColor="#132833" stopOpacity="0.64" />
            <stop offset="1" stopColor="#02080C" stopOpacity="0.9" />
          </linearGradient>
          <radialGradient id={coreId} cx="0" cy="0" r="1" gradientUnits="userSpaceOnUse" gradientTransform="translate(25 26) rotate(90) scale(16 13)">
            <stop stopColor="#DFFFF5" />
            <stop offset="0.22" stopColor="#58E7B5" />
            <stop offset="0.62" stopColor="#0D8D73" stopOpacity="0.72" />
            <stop offset="1" stopColor="#031012" stopOpacity="0.3" />
          </radialGradient>
          <linearGradient id={metalId} x1="10" y1="15" x2="30" y2="36" gradientUnits="userSpaceOnUse">
            <stop stopColor="#FFFFFF" />
            <stop offset="0.22" stopColor="#CED6DF" />
            <stop offset="0.58" stopColor="#7D8792" />
            <stop offset="1" stopColor="#F2F6FA" />
          </linearGradient>
          <linearGradient id={metalDarkId} x1="21" y1="19" x2="36" y2="32" gradientUnits="userSpaceOnUse">
            <stop stopColor="#1B222C" />
            <stop offset="1" stopColor="#060A0E" />
          </linearGradient>
          <filter id={glowId} x="3" y="2" width="42" height="44" filterUnits="userSpaceOnUse">
            <feDropShadow dx="0" dy="9" stdDeviation="4" floodColor="#3DD68C" floodOpacity="0.24" />
            <feDropShadow dx="0" dy="1" stdDeviation="1" floodColor="#FFFFFF" floodOpacity="0.3" />
          </filter>
        </defs>

        <ellipse cx="24" cy="40" rx="14" ry="3.6" fill="#02070B" opacity="0.34" />

        <g filter={`url(#${glowId})`}>
          <path d="M10.5 17.1 24 9.2 37.5 17.1 24 24.9Z" fill={`url(#${glassTopId})`} />
          <path d="M10.5 17.1 24 24.9v15L10.5 32.1Z" fill={`url(#${glassLeftId})`} />
          <path d="M24 24.9 37.5 17.1v15L24 39.9Z" fill={`url(#${glassRightId})`} />
          <path d="M13.2 19.1 24 12.8l10.8 6.3L24 25.4Z" fill="#081117" opacity="0.34" />
          <path d="M14.2 20.4 24 14.7l9.8 5.7L24 36.5Z" fill={`url(#${coreId})`} opacity="0.9" />

          <path
            d="M10.5 17.1 24 9.2 37.5 17.1 24 24.9 10.5 17.1ZM10.5 17.1v15L24 39.9l13.5-7.8v-15M24 24.9v15"
            fill="none"
            stroke="#F5FAFF"
            strokeOpacity="0.46"
            strokeWidth="1.25"
            strokeLinejoin="round"
          />
          <path d="M12.4 17.3 24 10.6 35.6 17.3" stroke="#FFFFFF" strokeOpacity="0.72" strokeWidth="1.35" strokeLinecap="round" />
          <path d="M15.2 21.1 24 26.2 32.8 21.1" stroke="#7CF0C1" strokeOpacity="0.34" strokeWidth="1" strokeLinecap="round" />

          <path
            d="M9.6 15.6h10.7c5.4 0 8.7 2.5 8.7 6.4 0 2.2-1.2 3.9-3.3 4.8 2.9.8 4.6 2.8 4.6 5.4 0 4.4-3.7 7-9.7 7h-11V15.6Zm8 9.3h3.2c1.7 0 2.7-.8 2.7-2.1s-1-2-2.7-2h-3.2v4.1Zm0 9h3.8c1.9 0 3-.8 3-2.3 0-1.4-1.1-2.2-3-2.2h-3.8v4.5Z"
            fill={`url(#${metalId})`}
          />
          <path
            d="M17.6 20.8h3.2c1.7 0 2.7.7 2.7 2s-1 2.1-2.7 2.1h-3.2v4.5h3.8c1.9 0 3 .8 3 2.2 0 1.5-1.1 2.3-3 2.3h-3.8v5.3h3c6 0 9.7-2.6 9.7-7 0-2.6-1.7-4.6-4.6-5.4 2.1-.9 3.3-2.6 3.3-4.8 0-3.9-3.3-6.4-8.7-6.4h-2.7v5.2Z"
            fill={`url(#${metalDarkId})`}
            opacity="0.18"
          />
          <path
            d="M12.2 17.7h8.1c4.2 0 6.4 1.6 6.4 4.3M17.6 24.9h3.2c1.7 0 2.7-.8 2.7-2.1M17.6 33.9h3.8c1.9 0 3-.8 3-2.3"
            fill="none"
            stroke="#FFFFFF"
            strokeOpacity="0.48"
            strokeWidth="1.1"
            strokeLinecap="round"
          />
        </g>
      </svg>
    </span>
  )
}
