// Every /api/* request → backend (see ../../cloudflare/proxy.ts).
export { proxy as onRequest } from '../../cloudflare/proxy.ts'
