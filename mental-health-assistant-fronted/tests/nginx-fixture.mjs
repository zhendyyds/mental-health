import assert from 'node:assert/strict'
import { spawn, spawnSync } from 'node:child_process'
import { createSocket } from 'node:dgram'
import { once } from 'node:events'
import { mkdir, readFile, writeFile } from 'node:fs/promises'
import { createServer } from 'node:net'
import { resolve } from 'node:path'
import { setTimeout as delay } from 'node:timers/promises'

const nginxPath = (path) => path.replaceAll('\\', '/')

export const eventually = async (condition, description) => {
  const deadline = Date.now() + 8000
  do {
    if (await condition()) return
    await delay(50)
  } while (Date.now() < deadline)
  assert.fail(description)
}

const reservePort = async () => {
  const server = createServer()
  server.listen(0, '127.0.0.1')
  await once(server, 'listening')
  const { port } = server.address()
  await new Promise((done) => server.close(done))
  return port
}

// Answer the single A-record question emitted by the local Nginx resolver.
const startDnsFixture = async () => {
  const socket = createSocket('udp4')
  let address = [127, 0, 0, 1]
  socket.on('message', (query, sender) => {
    let typeOffset = 12
    while (typeOffset < query.length && query[typeOffset]) typeOffset += query[typeOffset] + 1
    typeOffset++
    if (typeOffset + 4 > query.length || query.readUInt16BE(typeOffset) !== 1) return
    const header = Buffer.alloc(12)
    query.copy(header, 0, 0, 2)
    header.writeUInt16BE(0x8180, 2)
    header.writeUInt16BE(1, 4)
    header.writeUInt16BE(1, 6)
    const answer = Buffer.from([0xc0, 0x0c, 0, 1, 0, 1, 0, 0, 0, 1, 0, 4, ...address])
    socket.send(Buffer.concat([header, query.subarray(12, typeOffset + 4), answer]), sender.port, sender.address)
  })
  socket.bind(0, '127.0.0.1')
  await once(socket, 'listening')
  return {
    port: socket.address().port,
    changeLoopback: (lastOctet) => { address = [127, 0, 0, lastOctet] },
    close: () => socket.close()
  }
}

export async function startNginxFixture({ binary, root, artifacts, backendPort }) {
  const productionConfig = resolve(root, 'nginx/default.conf')
  const dist = resolve(root, 'dist')
  await readFile(resolve(dist, 'index.html'))
  const prefix = `${nginxPath(artifacts)}/`
  await Promise.all(['logs', 'temp'].map((directory) => mkdir(resolve(artifacts, directory), { recursive: true })))
  const wrapper = resolve(artifacts, 'nginx.conf')
  const wrap = (config) => `worker_processes 1;
error_log stderr notice;
pid logs/nginx.pid;
events { worker_connections 256; }
http {
  types { text/html html; text/css css; application/javascript js; image/png png; image/svg+xml svg; }
  include "${nginxPath(config)}";
}
`
  const args = ['-p', prefix, '-c', nginxPath(wrapper)]
  await writeFile(wrapper, wrap(productionConfig))
  const syntax = spawnSync(binary, [...args, '-t'], { encoding: 'utf8', windowsHide: true, timeout: 5000 })
  assert.equal(syntax.status, 0, syntax.error?.message || syntax.stderr)
  const dns = await startDnsFixture()
  let processHandle
  let log = ''
  const close = async () => {
    if (processHandle?.pid && processHandle.exitCode === null && processHandle.signalCode === null) {
      const exited = once(processHandle, 'exit')
      processHandle.kill()
      await exited
    }
    dns.close()
    await writeFile(resolve(artifacts, 'nginx.log'), log)
  }
  try {
    const port = await reservePort()
    const fixture = resolve(artifacts, 'default.fixture.conf')
    const production = await readFile(productionConfig, 'utf8')
    const bound = production.replace('listen 8080;', `listen 127.0.0.1:${port};`)
      .replace('root /usr/share/nginx/html;', `root "${nginxPath(dist)}";`)
      .replace('127.0.0.11 valid=5s', `127.0.0.1:${dns.port} valid=1s`)
      .replace('backend:8082 resolve;', `backend:${backendPort} resolve;`)
    await writeFile(fixture, bound)
    await writeFile(wrapper, wrap(fixture))
    processHandle = spawn(binary, [...args, '-g', 'daemon off; master_process off;'], { windowsHide: true, stdio: ['ignore', 'ignore', 'pipe'] })
    processHandle.stderr.on('data', (chunk) => { log += chunk.toString() })
    processHandle.on('error', (error) => { log += error.message })
    const origin = `http://127.0.0.1:${port}`
    await eventually(async () => {
      try { return (await fetch(`${origin}/healthz`, { signal: AbortSignal.timeout(500) })).ok }
      catch { return false }
    }, 'Nginx health probe did not become ready')
    return { origin, close, changeBackendLoopback: dns.changeLoopback, syntax: syntax.stderr.trim() }
  } catch (error) {
    await close()
    throw error
  }
}
