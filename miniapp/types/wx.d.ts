declare const wx: {
  cloud: {
    init(options: { env: string }): void
    callContainer(options: { config: { env: string }; path: string; method: string;
      data?: unknown; header: Record<string, string> }): Promise<{ statusCode: number; data: unknown }>
  }
  login(options: { success(result: { code: string }): void; fail(error: unknown): void }): void
  getStorageSync(key: string): unknown
  setStorageSync(key: string, value: unknown): void
  removeStorageSync(key: string): void
  setClipboardData(options: { data: string; success?(): void }): void
  navigateTo(options: { url: string }): void
  switchTab(options: { url: string }): void
  reLaunch(options: { url: string }): void
  showToast(options: { title: string; icon?: 'none' | 'success' }): void
  showModal(options: { title: string; content: string; confirmText?: string;
    success(result: { confirm: boolean }): void }): void
}
declare function App(options: Record<string, unknown>): void
declare function Page<D extends object, M extends object>(options: { data: D } & M &
  ThisType<M & { data: D; setData(value: Partial<D> | Record<string, unknown>): void }>): void
