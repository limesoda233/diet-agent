import { CLOUD_ENV_ID, CLOUD_SERVICE_NAME } from '../config'

export function callBackend(path: string, method: string, data?: unknown,
                            header: Record<string, string> = {}): Promise<{ statusCode: number; data: unknown }> {
  return wx.cloud.callContainer({
    config: { env: CLOUD_ENV_ID },
    path,
    method,
    data,
    header: { 'X-WX-SERVICE': CLOUD_SERVICE_NAME, ...header },
  })
}
