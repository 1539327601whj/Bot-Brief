import { Children, cloneElement, isValidElement, type ReactElement, type ReactNode } from 'react'
import ReactMarkdown from 'react-markdown'
import './MarketMarkdown.css'

interface MarketMarkdownProps {
  children: string
}

const changePattern = /(?<![.\d])(\+[\d.]+(?:%|pt|点)|-[\d.]+(?:%|pt|点)|0(?:\.00)?(?:%|pt|点))(?!\d)/g

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

function stripNonReportMeta(markdown: string) {
  return restyleLegacyChangeUnits(markdown)
    .replace(/<!--[\s\S]*?-->/g, '')
    .replace(/(?:^|\n)>\s*数据说明：[^\n]*/g, '')
    .replace(/\n{3,}/g, '\n\n')
    .trimEnd()
}

function renderMarketChanges(node: ReactNode): ReactNode {
  if (typeof node === 'string') {
    return node.split(changePattern).map((part, index) => {
      if (/^\+\d/.test(part)) {
        return <span key={index} className="market-change market-change-up">{part}</span>
      }
      if (/^-\d/.test(part)) {
        return <span key={index} className="market-change market-change-down">{part}</span>
      }
      if (/^0(?:\.00)?(?:%|pt|点)$/.test(part)) {
        return <span key={index} className="market-change market-change-flat">{part}</span>
      }
      return part
    })
  }

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
