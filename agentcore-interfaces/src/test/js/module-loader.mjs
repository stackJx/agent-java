// Load browser ES modules without adding a production package.json or dependencies.
import { readFile } from 'node:fs/promises';
const root = new URL('../../main/resources/agentcore-ui/js/', import.meta.url);
const urls = new Map();
async function moduleUrl(name) {
  if (urls.has(name)) return urls.get(name);
  let source = await readFile(new URL(name, root), 'utf8');
  const dependencies = [...source.matchAll(/from\s+["']\.\/([^"']+)["']/g)];
  for (const match of dependencies) {
    source = source.replace(match[0], `from ${JSON.stringify(await moduleUrl(match[1]))}`);
  }
  const url = `data:text/javascript;base64,${Buffer.from(source).toString('base64')}`;
  urls.set(name, url);
  return url;
}
export async function loadModule(name) { return import(await moduleUrl(name)); }
