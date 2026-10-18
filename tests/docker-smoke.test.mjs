import test from 'node:test';
import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import {existsSync} from 'node:fs';

const base = process.env.CHAT_SMOKE_BASE_URL || 'http://localhost:8080';
const origin = new URL(base).origin;

async function request(path, body, member, requestOrigin = origin) {
  return fetch(`${base}${path}`, {
    method: body === undefined ? 'GET' : 'POST',
    headers: {
      Origin: requestOrigin,
      ...(body === undefined ? {} : {'Content-Type': 'application/json'}),
      ...(member ? {'X-Participant-Token': member.token} : {}),
    },
    ...(body === undefined ? {} : {body: JSON.stringify(body)}),
    // Public Java containers and the database can both be cold on the first request.
    signal: AbortSignal.timeout(30000),
  });
}

async function waitForApp() {
  let ready = false;
  for (let attempt = 0; attempt < 60; attempt++) {
    try {
      const response = await request('/');
      if (response.ok) { ready = true; break; }
    } catch { /* The application may still be starting. */ }
    await new Promise(resolve => setTimeout(resolve, 1000));
  }
  assert.ok(ready, 'Application did not become ready');
}

test('Docker UI, group chat, origin validation and saved history', {timeout: 180000}, async () => {
  await waitForApp();
  const home = await request('/');
  const html = await home.text();
  assert.match(html, /app\.js/);
  assert.match(html, /<title>トークリア｜自動翻訳チャット<\/title>/);

  const rejected = await request('/api/rooms', {displayName: 'Untrusted', language: 'ja'}, null, 'https://untrusted.example');
  assert.equal(rejected.status, 403);

  const created = await request('/api/rooms', {displayName: 'Docker test', language: 'ja'});
  assert.equal(created.status, 201);
  const owner = await created.json();
  const room = `/api/rooms/${owner.roomCode}`;
  const joined = await request(`${room}/join`, {displayName: 'Friend', language: 'en'});
  assert.equal(joined.status, 201);
  const friend = await joined.json();
  const third = await request(`${room}/join`, {displayName: 'Third friend', language: 'en'});
  assert.equal(third.status, 201);
  const thirdMember = await third.json();

  const sent = await request(`${room}/messages`, {
    clientId: crypto.randomUUID(), text: 'Docker persistence check', sourceLanguage: 'en',
  }, friend);
  assert.equal(sent.status, 200);
  const stateResponse = await request(`${room}/state`, undefined, owner);
  assert.equal(stateResponse.status, 200);
  const state = await stateResponse.json();
  assert.equal(state.participants.length, 3);
  assert.ok(state.messages.some(message => message.text === 'Docker persistence check'));

  if (process.env.CHAT_SMOKE_RECREATE === '1') {
    const compose = ['compose', '--project-name', 'translate-chat-local', '-f', 'compose.yaml'];
    if (existsSync('.local/docker.env')) compose.push('--env-file', '.local/docker.env');
    // Only this project's containers are recreated. The named data volume is retained.
    execFileSync('docker', [...compose, 'down'], {stdio: 'inherit', timeout: 60000});
    execFileSync('docker', [...compose, 'up', '-d', '--no-build'], {stdio: 'inherit', timeout: 90000});
    await waitForApp();
    const restoredResponse = await request(`${room}/state`, undefined, owner);
    assert.equal(restoredResponse.status, 200);
    const restored = await restoredResponse.json();
    assert.ok(restored.messages.some(message => message.text === 'Docker persistence check'), 'History must survive container recreation');
  }

  for (const member of [owner, friend, thirdMember]) {
    assert.equal((await request(`${room}/leave`, {}, member)).status, 204);
  }
  const rejoined = await request(`${room}/join`, {displayName: 'Returning friend', language: 'ja'});
  assert.equal(rejoined.status, 201);
  const returning = await rejoined.json();
  const history = await (await request(`${room}/state`, undefined, returning)).json();
  assert.ok(history.messages.some(message => message.text === 'Docker persistence check'));
  assert.equal((await request(`${room}/leave`, {}, returning)).status, 204);
});
