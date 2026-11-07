// A deliberately small DOM double for component state tests, NOT an HTML parser.
class TestNode {
  constructor(tag = '#text', text = '') {
    this.tagName = tag;
    this.children = [];
    this.attributes = {};
    this.listeners = new Map();
    this.hidden = false;
    this.disabled = false;
    this.value = '';
    this._text = text;
    this.className = '';
    this.classList = {
      contains: value => this.className.split(/\s+/).includes(value),
      add: value => { if (!this.classList.contains(value)) this.className += ` ${value}`; },
      remove: value => { this.className = this.className.split(/\s+/).filter(v => v !== value).join(' '); },
      toggle: (value, force) => {
        const add = force ?? !this.classList.contains(value);
        this.classList[add ? 'add' : 'remove'](value);
        return add;
      },
    };
  }
  append(...children) {
    for (const child of children) {
      const node = child instanceof TestNode ? child : new TestNode('#text', String(child));
      node.remove();
      node.parentNode = this;
      this.children.push(node);
    }
  }
  remove() {
    if (this.parentNode) this.parentNode.children = this.parentNode.children.filter(child => child !== this);
    this.parentNode = null;
  }
  replaceChildren(...children) {
    for (const child of [...this.children]) child.remove();
    this._text = '';
    this.append(...children);
  }
  set textContent(text) { this.replaceChildren(); this._text = String(text); }
  get textContent() { return this._text + this.children.map(child => child.textContent).join(''); }
  set innerHTML(html) {
    this._html = html;
    this.replaceChildren(new TestNode('svg'));
    if (this.tagName === 'template') this.content = new TestNode('#fragment', html);
  }
  get innerHTML() { return this._html ?? ''; }
  get firstElementChild() { return this.children[0]; }
  setAttribute(name, value) {
    this.attributes[name] = value;
    if (name === 'class') this.className = value;
    if (name === 'hidden') this.hidden = true;
  }
  getAttribute(name) { return this.attributes[name] ?? null; }
  addEventListener(name, callback) { this.listeners.set(name, callback); }
  async click() { if (!this.disabled) await this.listeners.get('click')?.(); }
  querySelectorAll(selector) {
    return this.children.flatMap(child => [
      ...((selector.startsWith('.') ? child.classList.contains(selector.slice(1)) : child.tagName === selector) ? [child] : []),
      ...child.querySelectorAll(selector),
    ]);
  }
}
export function installDom() {
  const frames = [];
  globalThis.Node = TestNode;
  globalThis.document = {
    createElement: tag => new TestNode(tag),
    createTextNode: text => new TestNode('#text', text),
    body: new TestNode('body'),
  };
  globalThis.requestAnimationFrame = callback => { frames.push(callback); return frames.length; };
  return { create: tag => new TestNode(tag), flush: () => { while (frames.length) frames.shift()(); } };
}
