import test from 'node:test';
import assert from 'node:assert/strict';
import {MessageFeed} from '../src/main/resources/static/message-feed.js';

test('sending between polls must not skip an unseen message from a friend', () => {
  const feed = new MessageFeed();
  feed.receive([{id: 10, text: 'Earlier message'}]);
  feed.add([{id: 12, text: 'My send response'}]);
  assert.equal(feed.cursor, 10, 'The next poll must still ask for messages after 10');
  const received = feed.receive([
    {id: 11, text: 'A friend sent this before my message'},
    {id: 12, text: 'My send response'},
  ]);
  assert.deepEqual(received.map(message => message.id), [11]);
  assert.equal(feed.cursor, 12);
  assert.equal(feed.messages.size, 3);
});

test('history and duplicate send responses do not move the polling cursor', () => {
  const feed = new MessageFeed();
  feed.receive([{id: 30}, {id: 31}]);
  assert.deepEqual(feed.add([{id: 29}, {id: 28}, {id: 30}]).map(message => message.id), [28, 29]);
  feed.add([{id: 35}]);
  feed.add([{id: 35}]);
  assert.equal(feed.cursor, 31);
  assert.deepEqual(feed.receive([{id: 32}, {id: 33}, {id: 34}, {id: 35}]).map(message => message.id), [32, 33, 34]);
  assert.equal(feed.cursor, 35);
});

test('entering another room clears messages and the cursor', () => {
  const feed = new MessageFeed();
  feed.receive([{id: 100}]);
  feed.clear();
  assert.equal(feed.cursor, 0);
  assert.equal(feed.messages.size, 0);
  assert.deepEqual(feed.receive([{id: 5}]).map(message => message.id), [5]);
  assert.equal(feed.cursor, 5);
});
