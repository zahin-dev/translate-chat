// Keep the polling cursor separate from messages received through send/history responses.
export class MessageFeed {
  constructor() {
    this.messages = new Map();
    this.cursor = 0;
  }

  clear() {
    this.messages.clear();
    this.cursor = 0;
  }

  add(messages) {
    const added = [];
    for (const message of messages) {
      if (!this.messages.has(message.id)) {
        this.messages.set(message.id, message);
        added.push(message);
      }
    }
    return added.sort((a, b) => a.id - b.id);
  }

  receive(messages) {
    const added = this.add(messages);
    for (const message of messages) { this.cursor = Math.max(this.cursor, message.id); }
    return added;
  }
}
