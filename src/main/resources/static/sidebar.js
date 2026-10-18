// Keep a useful conversation area while allowing mouse, touch and keyboard resizing.
export function initResizableSidebar(container, sidebar, handle, {initialWidth, saveWidth}) {
  let preferredWidth = Number.isFinite(initialWidth) && initialWidth > 0 ? initialWidth : null;
  let drag = null;

  function bounds() {
    const available = container.getBoundingClientRect().width;
    const max = Math.max(100, Math.min(480, available / 2, available - 248));
    return {min:Math.min(180, max), max};
  }
  function clamp(width) {
    const {min, max} = bounds();
    return Math.round(Math.min(max, Math.max(min, width)));
  }
  function refresh() {
    if (!container.clientWidth) { return; }
    if (preferredWidth === null) { container.style.removeProperty('--sidebar-width'); }
    else { container.style.setProperty('--sidebar-width',`${clamp(preferredWidth)}px`); }
    const {min, max} = bounds();
    handle.setAttribute('aria-valuemin',String(Math.round(min)));
    handle.setAttribute('aria-valuemax',String(Math.round(max)));
    handle.setAttribute('aria-valuenow',String(Math.round(sidebar.getBoundingClientRect().width)));
  }
  function finish(commit) {
    if (!drag) { return; }
    const previous = drag;
    drag = null;
    container.classList.remove('sidebar-resizing');
    if (!commit) { preferredWidth = previous.preferredWidth; refresh(); }
    else { saveWidth(preferredWidth); }
    if (handle.hasPointerCapture(previous.id)) { handle.releasePointerCapture(previous.id); }
  }

  handle.addEventListener('pointerdown',event => {
    if (event.button !== 0 || drag) { return; }
    event.preventDefault();
    handle.focus({preventScroll:true});
    drag = {id:event.pointerId,x:event.clientX,width:sidebar.getBoundingClientRect().width,preferredWidth};
    handle.setPointerCapture(event.pointerId);
    container.classList.add('sidebar-resizing');
  });
  handle.addEventListener('pointermove',event => {
    if (!drag || event.pointerId !== drag.id) { return; }
    preferredWidth = clamp(drag.width + event.clientX - drag.x);
    refresh();
  });
  handle.addEventListener('pointerup',event => {
    if (event.pointerId === drag?.id) { finish(true); }
  });
  handle.addEventListener('pointercancel',() => finish(false));
  handle.addEventListener('lostpointercapture',() => finish(false));
  window.addEventListener('keydown',event => {
    if (drag && event.key === 'Escape') { event.preventDefault(); finish(false); }
  });
  handle.addEventListener('keydown',event => {
    if (drag || !['ArrowLeft','ArrowRight','Home','End'].includes(event.key)) { return; }
    event.preventDefault();
    const {min, max} = bounds();
    const step = event.shiftKey ? 40 : 10;
    const width = sidebar.getBoundingClientRect().width;
    preferredWidth = clamp(event.key === 'Home' ? min : event.key === 'End' ? max
      : width + (event.key === 'ArrowLeft' ? -step : step));
    refresh();
    saveWidth(preferredWidth);
  });
  handle.addEventListener('dblclick',() => {
    preferredWidth = null;
    refresh();
    saveWidth(null);
  });
  new ResizeObserver(refresh).observe(container);
  refresh();
}
