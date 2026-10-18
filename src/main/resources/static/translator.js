export class LocalTranslator {
  constructor(onStatus) {
    this.onStatus = onStatus;
    this.status = 'idle';
    this.translators = new Map();
    this.cache = new Map();
    this.queue = Promise.resolve();
    this.pending = null;
    this.detector = null;
    this.attempt = 0;
  }

  update(status, progress) {
    this.status = status;
    this.onStatus(status, progress);
  }

  prepare(allowDownload = true) {
    if (this.status === 'ready') { return Promise.resolve(true); }
    if (this.pending) { return this.pending; }
    if (!('Translator' in window)) { this.update('unsupported'); return Promise.resolve(false); }
    const attempt = ++this.attempt;
    const created = [];
    this.update('preparing');
    // On a join/setup click, call create immediately while user activation is present.
    const pairs = [['ja', 'en'], ['en', 'ja']];
    this.pending = (async () => {
      try {
        if (!allowDownload) {
          const states = await Promise.all(pairs.map(([sourceLanguage,targetLanguage]) => window.Translator.availability({sourceLanguage,targetLanguage})));
          if (!states.every(state => state === 'available')) { this.update('idle'); return false; }
        }
        const jobs = pairs.map(([sourceLanguage,targetLanguage]) => window.Translator.create({
          sourceLanguage, targetLanguage,
          monitor: monitor => monitor.addEventListener('downloadprogress', event => {
            if (this.attempt === attempt && this.status === 'preparing') { this.update('preparing', event.loaded); }
          })
        }).then(engine => {
          if (this.attempt !== attempt) { engine.destroy(); }
          else { created.push(engine); }
          return engine;
        }));
        if ('LanguageDetector' in window) {
          window.LanguageDetector.create().then(detector => { this.detector = detector; }).catch(() => {});
        }
        const engines = await Promise.all(jobs);
        pairs.forEach(([source,target], index) => this.translators.set(`${source}:${target}`, engines[index]));
        this.update('ready');
        return true;
      } catch {
        this.attempt++;
        created.forEach(engine => engine.destroy());
        this.update('error');
        return false;
      }
    })().finally(() => { this.pending = null; });
    return this.pending;
  }

  async detect(text) {
    if (!/\p{L}/u.test(text)) { return 'und'; }
    if (this.detector) {
      try {
        const results = await this.detector.detect(text);
        if (['ja','en'].includes(results[0]?.detectedLanguage) && results[0].confidence > 0.3) {
          return results[0].detectedLanguage;
        }
      } catch { /* A short greeting may not be identifiable; use the script below. */ }
    }
    return /[\p{Script=Hiragana}\p{Script=Katakana}\p{Script=Han}]/u.test(text) ? 'ja' : 'en';
  }

  translate(message, target) {
    if (message.sourceLanguage === target || message.sourceLanguage === 'und') {
      return Promise.resolve({text:message.text, translated:false});
    }
    const key = `${message.id}:${target}`;
    if (this.cache.has(key)) { return Promise.resolve(this.cache.get(key)); }
    const job = this.queue.then(async () => {
      if (this.pending) { await this.pending; }
      const engine = this.translators.get(`${message.sourceLanguage}:${target}`);
      if (!engine || this.status !== 'ready') { throw new Error('TRANSLATION_UNAVAILABLE'); }
      const text = await engine.translate(message.text);
      if (!text?.trim()) { throw new Error('EMPTY_TRANSLATION'); }
      const result = {text, translated:true};
      this.cache.set(key, result);
      if (this.cache.size > 500) { this.cache.delete(this.cache.keys().next().value); }
      return result;
    });
    this.queue = job.catch(() => {});
    return job;
  }
}
