import { API_BASE_URL } from '@/config/env'

/**
 * SSE helper functions
 */
export interface SSEMessage {
  type: string
  data?: any
  seq?: number
  [key: string]: any
}

export interface SSEOptions {
  onMessage: (message: SSEMessage) => void
  onError?: (error: Event) => void
  onComplete?: () => void
}

/**
 * Create SSE connection
 * @param taskId  - The task identifier
 * @param options - Callbacks and configuration
 * @param lastSeq - The last sequence number the client received (default 0)
 */
export const connectSSE = (
  taskId: string,
  options: SSEOptions,
  lastSeq: number = 0
): EventSource => {
  const { onMessage, onError, onComplete } = options

  let url = `${API_BASE_URL}/article/progress/${taskId}`
  if (lastSeq > 0) {
    url += `?lastSeq=${lastSeq}`
  }

  const eventSource = new EventSource(url, {
    withCredentials: true,
  })

  eventSource.onmessage = (event) => {
    try {
      const message: SSEMessage = JSON.parse(event.data)
      onMessage(message)

      if (message.type === 'ALL_COMPLETE' || message.type === 'ERROR') {
        eventSource.close()
        onComplete?.()
      }
    } catch (error) {
      console.error('SSE message parse failed:', error)
    }
  }

  eventSource.onerror = (error) => {
    console.error('SSE connection error:', error)
    onError?.(error)
  }

  return eventSource
}

/**
 * Close SSE connection
 */
export const closeSSE = (eventSource: EventSource | null) => {
  if (eventSource) {
    eventSource.close()
  }
}
