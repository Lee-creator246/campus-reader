export function glyph(name) {
  const svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
  const use = document.createElementNS(svg.namespaceURI, "use");
  svg.classList.add("glyph");
  svg.setAttribute("aria-hidden", "true");
  use.setAttribute("href", "./glyphs.svg#" + name);
  svg.append(use);
  return svg;
}

// Insert only successfully loaded images. A missing or blocked image uses no layout space.
export function loadPhoto(container, url, {thumbnail = false} = {}) {
  if (!url) return;
  const photo = new Image();
  photo.crossOrigin = "anonymous";
  photo.referrerPolicy = "no-referrer";
  photo.decoding = "async";
  photo.alt = thumbnail ? "" : "通知图片";
  if (thumbnail) photo.className = "row-thumb";
  photo.onload = () => {
    if (!container.isConnected || container.dataset.photo !== url) return;
    container.prepend(photo);
    container.classList.add("has-image");
  };
  container.dataset.photo = url;
  photo.src = url;
}

// Preserve ordinary click/scroll and desktop modifiers. A held row opens a preview once.
export function holdToPreview(anchor, preview, open) {
  let timer, x = 0, y = 0, held = false;
  const cancel = () => clearTimeout(timer);
  const show = () => {
    cancel();
    if (!held) { held = true; preview(); }
  };
  anchor.addEventListener("pointerdown", event => {
    cancel();
    held = false;
    if (!event.isPrimary || event.button !== 0) return;
    x = event.clientX; y = event.clientY;
    timer = setTimeout(show, 550);
  });
  anchor.addEventListener("pointermove", event => {
    if (Math.abs(event.clientX - x) + Math.abs(event.clientY - y) > 12) cancel();
  });
  for (const name of ["pointerup", "pointercancel", "pointerleave"]) anchor.addEventListener(name, cancel);
  anchor.addEventListener("contextmenu", event => { event.preventDefault(); show(); });
  anchor.addEventListener("click", event => {
    if (held) { event.preventDefault(); held = false; return; }
    if (event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
    event.preventDefault();
    open();
  });
}

export function activateDialog(dialog, focus) {
  if (!dialog.open) dialog.showModal();
  if (focus) focus.focus();
}
export function dismissOnBackdrop(dialog) {
  dialog.addEventListener("click", event => {
    if (event.target !== dialog) return;
    const rect = dialog.getBoundingClientRect();
    if (event.clientX < rect.left || event.clientX > rect.right ||
        event.clientY < rect.top || event.clientY > rect.bottom) dialog.close();
  });
}
