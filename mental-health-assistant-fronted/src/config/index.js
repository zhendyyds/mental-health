// Production forwards /upload through the page's origin, including its port.
const configuredFileBaseUrl = import.meta.env.VITE_FILE_BASE_URL?.trim()
export const fileBaseUrl = configuredFileBaseUrl
  ? configuredFileBaseUrl.replace(/\/+$/, '')
  : (import.meta.env.DEV ? 'http://localhost:8082' : '')
