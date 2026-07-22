import { Children, cloneElement, isValidElement, type ReactElement, type ReactNode } from 'react'
import ReactMarkdown from 'react-markdown'
import './MarketMarkdown.css'

interface MarketMarkdownProps {
  children: string
}

const changePattern = /(↑ \+\d+(?:\.\d+)?%|↓ -\d+(?:\.\d+)?%|— 0(?:\.0+)?%)/g

function renderMarketChanges(node: ReactNode): ReactNode {
  if (typeof node === 'string') {
    return node.split(changePattern).map((part, index) => {
      if (/^↑ \+/.test(part)) {
        return <span key={index} className="market-change market-change-up">{part}</span>
      }
      if (/^↓ -/.test(part)) {
        return <span key={index} className="market-change market-change-down">{part}</span>
      }
      if (/^— 0/.test(part)) {
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
    <ReactMarkdown
      components={{
        p: ({ children: content, ...props }) => <p {...props}>{renderMarketChanges(content)}</p>,
        li: ({ children: content, ...props }) => <li {...props}>{renderMarketChanges(content)}</li>,
        blockquote: ({ children: content, ...props }) => (
          <blockquote {...props}>{renderMarketChanges(content)}</blockquote>
        ),
      }}
    >
      {children}
    </ReactMarkdown>
  )
}
