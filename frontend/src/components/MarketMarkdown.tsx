import { Children, cloneElement, isValidElement, type ReactElement, type ReactNode } from 'react'
import ReactMarkdown from 'react-markdown'
import './MarketMarkdown.css'

interface MarketMarkdownProps {
  children: string
}

const CHANGE_TOKEN = '((?:[↑↓]\\s*)?(?:[+＋][\\d.]+(?:%|pt|点)|[-−–][\\d.]+(?:%|pt|点)|0(?:\\.00)?(?:%|pt|点)))'

function changeTokenPattern() {
  return new RegExp(`(?<![.\\d])${CHANGE_TOKEN}(?!\\d)`, 'g')
}

function restyleLegacyChangeUnits(markdown: string) {
  return markdown
    .replace(/(?<![.\d])([+-]\d+)点(?!\d)/g, (_, token) => {
      const value = Number(token)
      return `${value > 0 ? '+' : ''}${value.toFixed(2)}%`
    })
    .replace(/(?<![.\d])0点(?!\d)/g, '0.00%')
    .replace(/(?<![.\d])([+-]?\d+(?:\.\d+)?)pt(?!\d)/g, (_, raw) => {
      const value = Number(raw)
      if (Number.isNaN(value) || Math.abs(value) < 0.005) return '0.00%'
      return `${value > 0 ? '+' : ''}${value.toFixed(2)}%`
    })
}

function decorateSignedChanges(markdown: string) {
  return markdown.replace(changeTokenPattern(), token => formatChangeToken(token))
}

function formatChangeToken(token: string) {
  const normalized = token
    .replace(/＋/g, '+')
    .replace(/[−–]/g, '-')
    .replace(/\s+/g, ' ')
    .trim()
  const body = normalized.replace(/[↑↓]/g, '').trim()
  if (body.startsWith('+')) return normalized.startsWith('↑') ? normalized : `↑ ${body}`
  if (body.startsWith('-')) return normalized.startsWith('↓') ? normalized : `↓ ${body}`
  return body
}

function changeKind(token: string) {
  if (token.includes('↑') || token.includes('+')) return 'up'
  if (token.includes('↓') || token.includes('-')) return 'down'
  return 'flat'
}

function stripNonReportMeta(markdown: string) {
  return decorateSignedChanges(restyleLegacyChangeUnits(markdown))
    .replace(/<!--[\s\S]*?-->/g, '')
    .replace(/(?:^|\n)>\s*数据说明：[^\n]*/g, '')
    .replace(/(?:^|\n)[-*]\s*候选基于公开量价[^\n]*/g, '')
    .replace(/(?:^|\n)[-*]\s*[^\n]*仅作研究线索[^\n]*/g, '')
    .replace(/\n{3,}/g, '\n\n')
    .trimEnd()
}

function paintChangeText(text: string): ReactNode {
  const pattern = changeTokenPattern()
  const nodes: ReactNode[] = []
  let lastIndex = 0
  let match: RegExpExecArray | null
  while ((match = pattern.exec(text)) !== null) {
    if (match.index > lastIndex) nodes.push(text.slice(lastIndex, match.index))
    const token = formatChangeToken(match[1])
    nodes.push(
      <span key={`${match.index}-${token}`} className={`market-change market-change-${changeKind(token)}`}>
        {token}
      </span>
    )
    lastIndex = match.index + match[0].length
  }
  if (lastIndex < text.length) nodes.push(text.slice(lastIndex))
  if (nodes.length === 0) return text
  if (nodes.length === 1) return nodes[0]
  return nodes
}

function renderMarketChanges(node: ReactNode): ReactNode {
  if (typeof node === 'string') return paintChangeText(node)

  if (Array.isArray(node)) {
    return Children.map(node, renderMarketChanges)
  }

  if (isValidElement(node)) {
    const element = node as ReactElement<{ children?: ReactNode }>
    return cloneElement(element, undefined, renderMarketChanges(element.props.children))
  }

  return node
}

export default function MarketMarkdown({ children }: MarketMarkdownProps) {
  return (
    <div className="market-markdown">
      <ReactMarkdown
        components={{
          h1: ({ children: content, ...props }) => <h1 className="md-title" {...props}>{renderMarketChanges(content)}</h1>,
          h2: ({ children: content, ...props }) => <h2 className="md-section" {...props}>{renderMarketChanges(content)}</h2>,
          h3: ({ children: content, ...props }) => <h3 className="md-subtitle" {...props}>{renderMarketChanges(content)}</h3>,
          h4: ({ children: content, ...props }) => <h4 className="md-subtitle" {...props}>{renderMarketChanges(content)}</h4>,
          p: ({ children: content, ...props }) => <p {...props}>{renderMarketChanges(content)}</p>,
          li: ({ children: content, ...props }) => <li {...props}>{renderMarketChanges(content)}</li>,
          blockquote: ({ children: content, ...props }) => (
            <blockquote {...props}>{renderMarketChanges(content)}</blockquote>
          ),
          a: ({ href, children: content, ...props }) => (
            <a href={href} target="_blank" rel="noopener noreferrer" {...props}>
              {renderMarketChanges(content)}
            </a>
          ),
        }}
      >
        {stripNonReportMeta(children)}
      </ReactMarkdown>
    </div>
  )
}
