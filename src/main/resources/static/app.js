import {translations} from './translations.js';
import {LocalTranslator} from './translator.js';
import {initResizableSidebar} from './sidebar.js';
import {MessageFeed} from './message-feed.js';

const $ = id => document.getElementById(id);
let language = 'ja';
let readingLanguage = 'ja';
let mode = 'create';
let membership = null;
const messageFeed = new MessageFeed();
let pollTimer = null;
let pollGeneration = 0;
let sending = false;
let pendingSend = null;
let membersSignature = '';
let toastTimer;
const messageEntries = new Map();
const translator = new LocalTranslator(updateTranslationStatus);

function t(key) { return translations[language][key] ?? translations[language].ERROR_UNKNOWN; }
function readStorage(storage, key) {
  try { return JSON.parse(storage.getItem(key)); } catch { return null; }
}
function saveStorage(storage, key, value) {
  try { value === null ? storage.removeItem(key) : storage.setItem(key, JSON.stringify(value)); } catch { /* Chat still works when storage is disabled. */ }
}
function changeLanguage(value) {
  language = value === 'en' ? 'en' : 'ja';
  document.documentElement.lang = language;
  document.title = t('appTitle');
  document.querySelector('meta[name="description"]').content = t('appDescription');
  document.querySelectorAll('[data-i18n]').forEach(node => { node.textContent = t(node.dataset.i18n); });
  document.querySelectorAll('[data-placeholder]').forEach(node => { node.placeholder = t(node.dataset.placeholder); });
  document.querySelectorAll('[data-i18n-label]').forEach(node => { node.setAttribute('aria-label',t(node.dataset.i18nLabel)); });
  document.querySelectorAll('[data-i18n-title]').forEach(node => { node.title = t(node.dataset.i18nTitle); });
  $('enter-label').textContent = t(mode === 'create' ? 'createAction' : 'joinAction');
  $('ui-language-switch').setAttribute('aria-label',t('displayLanguage'));
  document.querySelectorAll('[data-ui-language]').forEach(button => {
    button.setAttribute('aria-pressed',String(button.dataset.uiLanguage === language));
  });
  $('current-language').textContent = t(readingLanguage === 'ja' ? 'languageJa' : 'languageEn');
  document.querySelectorAll('[data-error-code]').forEach(node => {
    node.textContent = t(`ERROR_${node.dataset.errorCode}`);
  });
  updateTranslationStatus(translator.status);
}
function setMode(value) {
  mode = value;
  $('create-tab').setAttribute('aria-selected', String(mode === 'create'));
  $('join-tab').setAttribute('aria-selected', String(mode === 'join'));
  $('room-code-field').hidden = mode !== 'join';
  $('room-code').required = mode === 'join';
  $('enter-label').textContent = t(mode === 'create' ? 'createAction' : 'joinAction');
  $('entry-error').hidden = true;
}
function errorText(error) { return t(`ERROR_${error.code ?? 'UNKNOWN'}`); }
function showError(id, error, focus = false) {
  const element = $(id);
  element.dataset.errorCode = error.code ?? 'UNKNOWN';
  element.textContent = errorText(error);
  element.hidden = false;
  if (focus) { element.focus(); }
}
function toast(key) {
  clearTimeout(toastTimer);
  $('toast').dataset.i18n = key;
  $('toast').textContent = t(key);
  $('toast').hidden = false;
  toastTimer = setTimeout(() => { $('toast').hidden = true; }, 3500);
}

async function api(path, {body, method, token = membership?.token} = {}) {
  const headers = {};
  if (body !== undefined) { headers['Content-Type'] = 'application/json'; }
  if (token) { headers['X-Participant-Token'] = token; }
  let response;
  try {
    response = await fetch(`/api${path}`, {method:method ?? (body === undefined ? 'GET' : 'POST'), headers,
      body:body === undefined ? undefined : JSON.stringify(body), cache:'no-store', signal:AbortSignal.timeout(12000)});
  } catch { throw {code:'NETWORK_ERROR'}; }
  if (!response.ok) {
    let data;
    try { data = await response.json(); } catch { data = {code:'UNKNOWN'}; }
    throw {code:data.code ?? 'UNKNOWN', status:response.status};
  }
  return response.status === 204 ? null : response.json();
}

function updateTranslationStatus(status, progress) {
  const keys = {idle:'translationIdle',preparing:'translationPreparing',ready:'translationReady',unsupported:'translationUnsupported',error:'translationError'};
  $('translation-status').textContent = t(keys[status] ?? 'translationIdle')
    + (status === 'preparing' && Number.isFinite(progress) ? ` (${Math.round(progress * 100)}%)` : '');
  $('translation-dot').className = `status-dot ${status}`;
  $('prepare-translation').hidden = status === 'ready' || status === 'preparing' || status === 'unsupported';
  $('prepare-translation').textContent = t(status === 'error' ? 'retryTranslation' : 'prepareTranslation');
  if (status === 'ready') {
    messageEntries.forEach(entry => { if (entry.failed) { translateEntry(entry); } });
  }
}

function prettyCode(code) { return code.match(/.{1,4}/g)?.join('-') ?? code; }
function element(tag, className, text) {
  const node = document.createElement(tag);
  if (className) { node.className = className; }
  if (text !== undefined) { node.textContent = text; }
  return node;
}
function renderMembers(people) {
  const signature = JSON.stringify(people);
  if (signature === membersSignature) { return; }
  membersSignature = signature;
  const counts = new Map();
  people.forEach(person => counts.set(person.displayName, (counts.get(person.displayName) ?? 0) + 1));
  const nodes = people.map(person => {
    const row = element('li');
    const avatar = element('span','avatar',Array.from(person.displayName)[0]);
    avatar.setAttribute('aria-hidden','true');
    const info = element('div','member-info');
    const ownLabel = person.id === membership.participantId ? ` (${t('you')})` : '';
    const sameName = counts.get(person.displayName) > 1 ? ` · ${person.id.slice(0,4)}` : '';
    info.append(element('strong','',person.displayName + sameName + ownLabel), element('small','',t(person.language === 'ja' ? 'languageJa' : 'languageEn')));
    row.append(avatar,info,element('span','member-dot'));
    return row;
  });
  $('members-list').replaceChildren(...nodes);
  $('member-count').textContent = String(people.length);
}

async function translateEntry(entry) {
  const {message, text, label, details, node} = entry;
  entry.failed = false;
  if (message.participantId === membership?.participantId || message.sourceLanguage === readingLanguage || message.sourceLanguage === 'und') {
    text.textContent = message.text;
    label.hidden = true;
    details.hidden = true;
    return;
  }
  const target = readingLanguage;
  text.textContent = message.text;
  label.textContent = t('translating');
  label.hidden = false;
  details.hidden = true;
  try {
    const result = await translator.translate(message, target);
    if (!node.isConnected || target !== readingLanguage) { return; }
    text.textContent = result.text;
    label.textContent = t(target === 'ja' ? 'translatedJa' : 'translatedEn');
    details.hidden = !result.translated;
  } catch {
    if (!node.isConnected) { return; }
    entry.failed = true;
    text.textContent = message.text;
    label.textContent = t('originalFallback');
    details.hidden = true;
  }
}

function appendMessages(messages, prepend = false, received = false) {
  const scroll = $('messages-scroll');
  const nearBottom = scroll.scrollHeight - scroll.scrollTop - scroll.clientHeight < 140;
  const oldHeight = scroll.scrollHeight;
  const incoming = received ? messageFeed.receive(messages) : messageFeed.add(messages);
  if (!incoming.length) { return; }
  const fragment = document.createDocumentFragment();
  const newEntries = [];
  incoming.forEach(message => {
    const own = message.participantId === membership.participantId;
    const row = element('li',`message${own ? ' own' : ''}`);
    row.dataset.messageId = String(message.id);
    const avatar = element('span','avatar',Array.from(message.displayName)[0]);
    avatar.setAttribute('aria-hidden','true');
    const content = element('div','message-content');
    const meta = element('div','message-meta');
    const date = new Date(message.createdAt);
    const time = element('time','',date.toLocaleTimeString(language === 'ja' ? 'ja-JP' : 'en-GB',{hour:'2-digit',minute:'2-digit'}));
    time.dateTime = message.createdAt;
    time.title = date.toLocaleString(language === 'ja' ? 'ja-JP' : 'en-GB');
    meta.append(element('strong','',message.displayName + (own ? ` (${t('you')})` : '')),time);
    const bubble = element('div','bubble');
    const text = element('p','message-text',message.text);
    const label = element('small','translation-label');
    const details = element('details','original-details');
    details.hidden = true;
    details.append(element('summary','',t('showOriginal')),element('p','',message.text));
    bubble.append(text,label,details);
    content.append(meta,bubble);
    row.append(avatar,content);
    fragment.append(row);
    const entry = {message,node:row,text,label,details,failed:false};
    messageEntries.set(message.id,entry);
    newEntries.push(entry);
  });
  if (prepend) { $('messages').prepend(fragment); }
  else {
    // A send response can arrive before an earlier message from another participant.
    for (const entry of newEntries) {
      const next = Array.from($('messages').children).find(row => Number(row.dataset.messageId) > entry.message.id);
      $('messages').insertBefore(entry.node, next ?? null);
    }
  }
  $('empty-chat').hidden = true;
  newEntries.forEach(translateEntry);
  if (prepend) { scroll.scrollTop += scroll.scrollHeight - oldHeight; }
  else if (nearBottom || incoming.some(message => message.participantId === membership.participantId)) { scrollToLatest(); }
  else { $('new-messages').hidden = false; }
}
function scrollToLatest() {
  $('messages-scroll').scrollTop = $('messages-scroll').scrollHeight;
  $('new-messages').hidden = true;
}

function resetToEntry() {
  clearTimeout(pollTimer);
  pollGeneration++;
  membership = null;
  saveStorage(sessionStorage,'translate-chat-session',null);
  $('chat-view').hidden = true;
  $('entry-view').hidden = false;
  $('display-name').focus();
}

async function openRoom(member) {
  clearTimeout(pollTimer);
  const generation = ++pollGeneration;
  membership = member;
  messageFeed.clear();
  pendingSend = null;
  messageEntries.clear();
  membersSignature = '';
  $('messages').replaceChildren();
  $('empty-chat').hidden = false;
  $('load-history').hidden = true;
  $('send-error').hidden = true;
  $('message-input').value = '';
  $('character-count').textContent = '0 / 2,000';
  $('current-room-code').textContent = prettyCode(member.roomCode);
  $('room-code').value = prettyCode(member.roomCode);
  readingLanguage = member.language;
  $('language').value = readingLanguage;
  changeLanguage(language);
  saveStorage(sessionStorage,'translate-chat-session',member);
  $('entry-view').hidden = true;
  $('chat-view').hidden = false;
  $('message-input').focus();
  await poll(generation);
}

async function poll(generation) {
  if (!membership || generation !== pollGeneration) { return; }
  let delay = 2000;
  try {
    const wasInitial = messageFeed.cursor === 0;
    const result = await api(`/rooms/${membership.roomCode}/state?after=${messageFeed.cursor}`);
    if (generation !== pollGeneration) { return; }
    renderMembers(result.participants);
    appendMessages(result.messages,false,true);
    if (wasInitial) { $('load-history').hidden = !result.hasMoreHistory; }
    $('connection-status').hidden = true;
  } catch (error) {
    if (generation !== pollGeneration) { return; }
    if (error.code === 'SESSION_EXPIRED') {
      resetToEntry();
      setMode('join');
      showError('entry-error',error,true);
      return;
    }
    $('connection-status').textContent = error.code === 'STORAGE_ERROR' ? errorText(error) : t('reconnecting');
    $('connection-status').hidden = false;
    delay = 4000;
  }
  if (generation === pollGeneration) { pollTimer = setTimeout(() => poll(generation),delay); }
}

$('room-form').addEventListener('submit',async event => {
  event.preventDefault();
  if ($('enter-button').disabled) { return; }
  const displayName = $('display-name').value.trim();
  if (!displayName || Array.from(displayName).length > 30 || /[\u0000-\u001f\u007f]/u.test(displayName)) {
    showError('entry-error',{code:'INVALID_NAME'},true); return;
  }
  const roomCode = $('room-code').value.replace(/[-\s]/g,'').toUpperCase();
  if (mode === 'join' && !/^[A-HJ-NP-Z2-9]{12}$/.test(roomCode)) {
    showError('entry-error',{code:'ROOM_NOT_FOUND'},true); return;
  }
  // Starts the browser's model setup on this user gesture, without delaying entry.
  translator.prepare(true);
  $('enter-button').disabled = true;
  $('enter-label').textContent = t('entering');
  $('entry-error').hidden = true;
  saveStorage(localStorage,'translate-chat-preferences',{displayName,language:readingLanguage});
  try {
    const member = await api(mode === 'create' ? '/rooms' : `/rooms/${roomCode}/join`,{body:{displayName,language:readingLanguage},token:null});
    await openRoom(member);
  } catch (error) { showError('entry-error',error,true); }
  finally { $('enter-button').disabled = false; $('enter-label').textContent = t(mode === 'create' ? 'createAction' : 'joinAction'); }
});

$('message-form').addEventListener('submit',async event => {
  event.preventDefault();
  if (sending || !membership) { return; }
  const snapshot = $('message-input').value;
  const text = snapshot.trim();
  if (!text) { showError('send-error',{code:'EMPTY_MESSAGE'}); return; }
  if (Array.from(text).length > 2000) { showError('send-error',{code:'MESSAGE_TOO_LONG'}); return; }
  const room = membership.roomCode;
  const generation = pollGeneration;
  sending = true;
  $('send-button').disabled = true;
  $('send-button').querySelector('[data-i18n]').textContent = t('sending');
  $('send-error').hidden = true;
  try {
    if (!pendingSend || pendingSend.text !== text) {
      pendingSend = {clientId:crypto.randomUUID(),text,sourceLanguage:await translator.detect(text)};
    }
    const result = await api(`/rooms/${room}/messages`,{body:pendingSend});
    if (generation !== pollGeneration) { return; }
    appendMessages([result]);
    pendingSend = null;
    if ($('message-input').value === snapshot) { $('message-input').value = ''; updateCharacterCount(); }
    $('message-input').focus();
    scrollToLatest();
  } catch (error) {
    if (generation === pollGeneration) { showError('send-error',error); }
  } finally {
    sending = false;
    $('send-button').disabled = false;
    $('send-button').querySelector('[data-i18n]').textContent = t('send');
  }
});

function updateCharacterCount() {
  $('character-count').textContent = `${Array.from($('message-input').value).length.toLocaleString('en-US')} / 2,000`;
}
$('message-input').addEventListener('input',updateCharacterCount);
$('message-input').addEventListener('keydown',event => {
  if (event.key === 'Enter' && !event.shiftKey && !event.isComposing && event.keyCode !== 229) {
    event.preventDefault(); $('message-form').requestSubmit();
  }
});
$('create-tab').addEventListener('click',() => setMode('create'));
$('join-tab').addEventListener('click',() => setMode('join'));
function saveEntryPreferences() {
  saveStorage(localStorage,'translate-chat-preferences',{displayName:$('display-name').value,language:readingLanguage});
}
$('language').addEventListener('change',event => {
  readingLanguage = event.target.value === 'en' ? 'en' : 'ja';
  saveEntryPreferences();
});
document.querySelectorAll('[data-ui-language]').forEach(button => {
  button.addEventListener('click',() => {
    readingLanguage = button.dataset.uiLanguage === 'en' ? 'en' : 'ja';
    $('language').value = readingLanguage;
    changeLanguage(button.dataset.uiLanguage);
    saveStorage(localStorage,'translate-chat-ui-language',language);
    saveEntryPreferences();
  });
});
$('prepare-translation').addEventListener('click',() => translator.prepare(true));
$('new-messages').addEventListener('click',scrollToLatest);
$('messages-scroll').addEventListener('scroll',() => {
  const scroll = $('messages-scroll');
  if (scroll.scrollHeight - scroll.scrollTop - scroll.clientHeight < 100) { $('new-messages').hidden = true; }
});
$('copy-code').addEventListener('click',async () => {
  try { await navigator.clipboard.writeText(prettyCode(membership.roomCode)); toast('copied'); }
  catch { toast('copyFailed'); }
});
$('load-history').addEventListener('click',async () => {
  if (!membership || !messageEntries.size) { return; }
  const button = $('load-history');
  const generation = pollGeneration;
  button.disabled = true;
  button.textContent = t('loadingHistory');
  try {
    const before = Math.min(...messageEntries.keys());
    const result = await api(`/rooms/${membership.roomCode}/messages?before=${before}`);
    if (generation !== pollGeneration) { return; }
    appendMessages(result.messages,true);
    button.hidden = !result.hasMoreHistory;
  } catch (error) { showError('send-error',error); }
  finally { button.disabled = false; button.textContent = t('olderMessages'); }
});

async function leaveRoom() {
  if (!membership) { return; }
  $('leave-room').disabled = true;
  try {
    await api(`/rooms/${membership.roomCode}/leave`,{body:{}});
    $('message-input').value = '';
    resetToEntry();
    setMode('join');
    toast('left');
  } catch (error) { showError('send-error',error); }
  finally { $('leave-room').disabled = false; }
}
$('leave-room').addEventListener('click',() => {
  if ($('message-input').value.trim()) { $('leave-confirm').showModal(); }
  else { leaveRoom(); }
});
$('cancel-leave').addEventListener('click',() => $('leave-confirm').close());
$('confirm-leave').addEventListener('click',() => { $('leave-confirm').close(); leaveRoom(); });
window.addEventListener('beforeunload',event => {
  if (membership && $('message-input').value.trim()) { event.preventDefault(); event.returnValue = ''; }
});

const preferences = readStorage(localStorage,'translate-chat-preferences');
initResizableSidebar($('chat-view'),$('room-sidebar'),$('sidebar-resizer'),{
  initialWidth:readStorage(localStorage,'translate-chat-sidebar-width'),
  saveWidth:width => saveStorage(localStorage,'translate-chat-sidebar-width',width)
});
$('display-name').value = preferences?.displayName ?? '';
const savedUiLanguage = readStorage(localStorage,'translate-chat-ui-language') === 'en' ? 'en' : 'ja';
readingLanguage = ['ja','en'].includes(preferences?.language) ? preferences.language : savedUiLanguage;
$('language').value = readingLanguage;
changeLanguage(savedUiLanguage);
const savedMembership = readStorage(sessionStorage,'translate-chat-session');
if (savedMembership?.roomCode && savedMembership?.token && savedMembership?.participantId && ['ja','en'].includes(savedMembership.language)) {
  translator.prepare(false);
  openRoom(savedMembership).catch(error => { resetToEntry(); showError('entry-error',error,true); });
}
