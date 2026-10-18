import {test, afterEach} from 'node:test';
import assert from 'node:assert/strict';
import {LocalTranslator} from '../src/main/resources/static/translator.js';

afterEach(() => { delete globalThis.window; });

test('a late model download event cannot hide a failed setup', async () => {
  let lateProgress;
  globalThis.window = {Translator:{create: ({sourceLanguage, monitor}) => {
    monitor({addEventListener: (_name, callback) => { lateProgress = callback; }});
    return sourceLanguage === 'ja' ? Promise.reject(new Error('download failed'))
      : Promise.resolve({translate:async text => text,destroy(){}});
  }}};
  const translator = new LocalTranslator(() => {});
  assert.equal(await translator.prepare(),false);
  lateProgress({loaded:0.7});
  assert.equal(translator.status,'error');
  assert.equal(translator.pending,null);
});

test('a synchronous browser setup error can be retried', async () => {
  globalThis.window = {Translator:{create: () => { throw new Error('activation required'); }}};
  const translator = new LocalTranslator(() => {});
  assert.equal(await translator.prepare(),false);
  assert.equal(translator.pending,null);
  window.Translator.create = async () => ({translate:async () => 'こんにちは',destroy(){}});
  assert.equal(await translator.prepare(),true);
  assert.equal(translator.status,'ready');
});

test('a failed message does not block the next translation', async () => {
  globalThis.window = {Translator:{create: async () => ({
    translate:async text => { if (text === 'broken') { throw new Error('unavailable'); } return 'translated'; },
    destroy(){}
  })}};
  const translator = new LocalTranslator(() => {});
  await translator.prepare();
  await assert.rejects(translator.translate({id:1,text:'broken',sourceLanguage:'en'},'ja'));
  assert.deepEqual(await translator.translate({id:2,text:'hello',sourceLanguage:'en'},'ja'),{text:'translated',translated:true});
});
