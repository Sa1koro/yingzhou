import { createReadStream, statSync } from 'node:fs';
import { createServer } from 'node:http';
import { join, resolve } from 'node:path';

const root = resolve(process.argv[2] ?? 'engine/build/playback-fixture');
const base = 'http://127.0.0.1:19979';
const siteFor = (kind) => ({ key: `local-${kind}`, name: `本地验收站 ${kind}`, type: 1, api: `${base}/cms?kind=${kind}`, searchable: 1, quickSearch: 1 });
const movieFor = (kind) => ({ vod_id: 'fixture', vod_name: '本地验收色条', type_id: '1', vod_play_from: '本地测试线路',
  vod_play_url: `${kind}$${base}/fixture.${kind === 'HLS' ? 'm3u8' : 'mp4'}` });
const server = createServer((request, response) => {
  const url = new URL(request.url, base);
  const route = url.pathname;
  if (route === '/config.json' || route === '/cms') {
    const payload = route === '/config.json'
      ? { sites: ['MP4', 'HLS'].map(siteFor) }
      : { class: [{ type_id: '1', type_name: '验收分类' }], list: [movieFor(url.searchParams.get('kind'))], page: 1, pagecount: 1, total: 1 };
    response.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' }).end(JSON.stringify(payload));
    return;
  }
  if (!/^\/(fixture\.(mp4|m3u8)|fixture[0-9]{2}\.ts)$/.test(route)) {
    response.writeHead(404).end();
    return;
  }
  const file = join(root, route.slice(1));
  const size = statSync(file).size;
  const match = /^bytes=(\d+)-(\d*)$/.exec(request.headers.range ?? '');
  const start = match ? Number(match[1]) : 0;
  const end = match && match[2] ? Math.min(Number(match[2]), size - 1) : size - 1;
  if (start > end || start >= size) {
    response.writeHead(416, { 'Content-Range': `bytes */${size}` }).end();
    return;
  }
  const type = route.endsWith('.mp4') ? 'video/mp4' : route.endsWith('.ts') ? 'video/mp2t' : 'application/vnd.apple.mpegurl';
  const headers = { 'Content-Type': type, 'Accept-Ranges': 'bytes', 'Content-Length': end - start + 1 };
  if (match) headers['Content-Range'] = `bytes ${start}-${end}/${size}`;
  const status = match ? 206 : 200;
  process.stdout.write(`${request.method} ${route} ${status} ${start}-${end}/${size}\n`);
  response.writeHead(status, headers);
  if (request.method === 'HEAD') response.end();
  else {
    const stream = createReadStream(file, { start, end });
    response.on('close', () => stream.destroy());
    stream.pipe(response);
  }
});
server.listen(19979, '127.0.0.1');
