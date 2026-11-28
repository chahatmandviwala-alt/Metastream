const sidebar = document.getElementById("sidebar");
const toggleBtn = document.getElementById("toggleBtn");
const toolNav = document.getElementById("toolNav");
const pageTitle = document.getElementById("pageTitle");
const cards = Array.from(document.querySelectorAll(".tool-card"));
const panels = document.querySelectorAll(".tab-content");
let currentTab = "home";

// The sidebar shortcuts use the same icons and names as the Home grid.
for (const card of cards) {
  const name = card.querySelector(".tool-name").textContent;
  const item = document.createElement("button");
  item.type = "button";
  item.className = "nav-item";
  item.dataset.tab = card.dataset.tab;
  item.title = name;
  item.setAttribute("aria-label", name);
  const label = document.createElement("span");
  label.textContent = name;
  item.append(card.querySelector(".tool-icon svg").cloneNode(true), label);
  toolNav.append(item);
}

function setSidebarCollapsed(collapsed) {
  sidebar.classList.toggle("collapsed", collapsed);
  toggleBtn.setAttribute("aria-expanded", String(!collapsed));
  toggleBtn.title = collapsed ? "Expand sidebar" : "Collapse sidebar";
  toggleBtn.setAttribute("aria-label", toggleBtn.title);
}

try {
  setSidebarCollapsed(localStorage.getItem("sidebarCollapsed") === "1");
} catch (_) { /* The sidebar is also usable with storage disabled. */ }

toggleBtn.addEventListener("click", () => {
  const collapsed = !sidebar.classList.contains("collapsed");
  setSidebarCollapsed(collapsed);
  try { localStorage.setItem("sidebarCollapsed", collapsed ? "1" : "0"); } catch (_) {}
});

function openTab(tabId) {
  const target = document.getElementById(tabId);
  if (!target?.classList.contains("tab-content") || currentTab === tabId) return;
  const previous = currentTab;
  document.getElementById(previous).contentDocument?.querySelector("dialog[open]")?.close();
  currentTab = tabId;
  const atHome = tabId === "home";
  for (const panel of panels) panel.classList.toggle("active", panel === target);
  for (const button of document.querySelectorAll(".nav-item")) {
    if (button.dataset.tab === tabId) button.setAttribute("aria-current", "page");
    else button.removeAttribute("aria-current");
  }
  // The iframes stay mounted. The keyboard must never target the previous tool.
  vkActiveEl = null;
  vkActiveFrame = null;
  document.body.classList.toggle("in-tool", !atHome);
  pageTitle.textContent = atHome ? "Home" : target.title;
  const focusTarget = atHome ? cards.find(card => card.dataset.tab === previous) : pageTitle;
  focusTarget?.focus({ preventScroll: true });
}

document.addEventListener("click", event => {
  const shortcut = event.target.closest("button[data-tab]");
  if (shortcut) openTab(shortcut.dataset.tab);
});

// --------------------------------------------------
// Clear everything and attempt to close the window
// --------------------------------------------------
function clearAndClose() {
  // 1) Reset UI to a neutral state (Home if it exists)
  if (document.getElementById("home")) {
    openTab("home");
  }

  // 2) Clear persisted storage
  try { localStorage.clear(); } catch (e) {}
  try { sessionStorage.clear(); } catch (e) {}

  // 3) Unload all iframe contents
  document.querySelectorAll("iframe.tab-content").forEach(frame => {
    frame.src = "about:blank";
    frame.classList.remove("active");
  });

  // 4) Attempt to close the window
  fetch("/api/shutdown", { method: "POST" }).catch(() => {});
  window.close();

  // 5) Fallback if browser blocks closing
  setTimeout(() => {
    try {
      window.location.replace("about:blank");
    } catch (e) {
      document.documentElement.innerHTML = "";
    }
  }, 150);
}

// --------------------------------------------------
// Floating Virtual Keyboard
// - Types into focused inputs inside the active iframe
// - Shift/Caps + common US keyboard symbols
// --------------------------------------------------
const vkToggleBtn = document.getElementById("vkToggleBtn");
const vk = document.getElementById("vk");
const vkHeader = document.getElementById("vkHeader");
const vkCloseBtn = document.getElementById("vkCloseBtn");
const vkMinBtn = document.getElementById("vkMinBtn");

let vkVisible = false;
let vkShift = false;   // momentary
let vkCaps = false;    // toggle
let vkMinimized = false;

let vkActiveEl = null;       // element within an iframe
let vkActiveFrame = null;    // iframe element hosting vkActiveEl

function isTextTarget(el) {
  if (!el || el.disabled || el.readOnly || !el.getClientRects().length || el.closest("[inert]")) return false;
  const dialog = el.ownerDocument.querySelector("dialog[open]");
  if (dialog && !dialog.contains(el)) return false;

  // contenteditable
  if (el.isContentEditable) return true;

  const tag = (el.tagName || "").toLowerCase();
  if (tag === "textarea") return true;

  if (tag === "input") {
    const type = (el.getAttribute("type") || "text").toLowerCase();
    // exclude non-textual input types
    const excluded = new Set([
      "button", "submit", "reset", "checkbox", "radio",
      "file", "range", "color", "date", "datetime-local",
      "month", "week", "time", "image"
    ]);
    return !excluded.has(type);
  }
  return false;
}

function wireFrameFocusTracking(frame) {
  let doc;
  try { doc = frame.contentDocument; } catch (e) { return; }
  if (!doc || doc.__vkWired) return;

  doc.__vkWired = true;

  // Track focus for inputs/textareas/contenteditable within the iframe
  doc.addEventListener("focusin", (ev) => {
    const t = ev.target;
    if (isTextTarget(t)) {
      vkActiveEl = t;
      vkActiveFrame = frame;
    }
  }, true);

  // Also track on mouse down (some UIs do not focus until mouseup)
  doc.addEventListener("mousedown", (ev) => {
    const t = ev.target;
    if (isTextTarget(t)) {
      vkActiveEl = t;
      vkActiveFrame = frame;
    }
  }, true);
}

// Wire all iframes on load
document.querySelectorAll("iframe.tab-content").forEach((frame) => {
  frame.addEventListener("load", () => wireFrameFocusTracking(frame));
  // Attempt immediate wire if already loaded
  try {
    if (frame.contentDocument && frame.contentDocument.readyState === "complete") {
      wireFrameFocusTracking(frame);
    }
  } catch (e) {}
});

function getActiveDoc() {
  if (vkActiveFrame) {
    try { return vkActiveFrame.contentDocument; } catch (e) { return null; }
  }
  const activeFrame = document.querySelector("iframe.tab-content.active");
  if (!activeFrame) return null;
  try { return activeFrame.contentDocument; } catch (e) { return null; }
}

function dispatchInputEvents(el) {
  try {
    el.dispatchEvent(new Event("input", { bubbles: true }));
    el.dispatchEvent(new Event("change", { bubbles: true }));
  } catch (e) {}
}

function focusFallbackTarget() {
  // If we don't have a tracked target yet, try to focus the first visible input in the active iframe
  const doc = getActiveDoc();
  if (!doc) return null;

  const candidates = Array.from(doc.querySelectorAll("input, textarea, [contenteditable=''], [contenteditable='true']"))
    .filter(isTextTarget)
    .filter(el => !el.disabled && el.tabIndex !== -1 && el.getClientRects().length);

  if (candidates.length) {
    try { candidates[0].focus(); } catch (e) {}
    vkActiveEl = candidates[0];
    vkActiveFrame = document.querySelector("iframe.tab-content.active");
    return vkActiveEl;
  }
  return null;
}

function insertText(text) {
  let el = vkActiveEl;
  if (!el || !isTextTarget(el)) el = focusFallbackTarget();
  if (!el || !isTextTarget(el)) return;

  // Ensure focus stays where we type
  try { el.focus(); } catch (e) {}

  const doc = getActiveDoc();

  if (el.isContentEditable) {
    try {
      // execCommand is deprecated but remains broadly supported for this purpose
      doc.execCommand("insertText", false, text);
    } catch (e) {}
    return;
  }

  // input/textarea
  try {
    const start = el.selectionStart ?? el.value.length;
    const end = el.selectionEnd ?? el.value.length;
    if (typeof el.setRangeText === "function") {
      el.setRangeText(text, start, end, "end");
    } else {
      const v = el.value || "";
      el.value = v.slice(0, start) + text + v.slice(end);
      const pos = start + text.length;
      el.selectionStart = el.selectionEnd = pos;
    }
    dispatchInputEvents(el);
  } catch (e) {}
}

function backspace() {
  let el = vkActiveEl;
  if (!el || !isTextTarget(el)) el = focusFallbackTarget();
  if (!el || !isTextTarget(el)) return;

  try { el.focus(); } catch (e) {}

  const doc = getActiveDoc();

  if (el.isContentEditable) {
    try { doc.execCommand("delete", false, null); } catch (e) {}
    return;
  }

  try {
    const start = el.selectionStart ?? 0;
    const end = el.selectionEnd ?? 0;

    if (start !== end) {
      el.setRangeText("", start, end, "end");
    } else if (start > 0) {
      el.setRangeText("", start - 1, start, "end");
    }
    dispatchInputEvents(el);
  } catch (e) {}
}

function delForward() {
  let el = vkActiveEl;
  if (!el || !isTextTarget(el)) el = focusFallbackTarget();
  if (!el || !isTextTarget(el)) return;

  try { el.focus(); } catch (e) {}

  const doc = getActiveDoc();

  if (el.isContentEditable) {
    try { doc.execCommand("forwardDelete", false, null); } catch (e) {}
    return;
  }

  try {
    const start = el.selectionStart ?? 0;
    const end = el.selectionEnd ?? 0;
    const vlen = (el.value || "").length;

    if (start !== end) {
      el.setRangeText("", start, end, "end");
    } else if (start < vlen) {
      el.setRangeText("", start, start + 1, "end");
    }
    dispatchInputEvents(el);
  } catch (e) {}
}

function moveCaret(delta) {
  let el = vkActiveEl;
  if (!el || !isTextTarget(el)) el = focusFallbackTarget();
  if (!el || !isTextTarget(el)) return;

  try { el.focus(); } catch (e) {}

  if (el.isContentEditable) return; // keep simple

  try {
    const pos = el.selectionStart ?? 0;
    const next = Math.max(0, pos + delta);
    el.selectionStart = el.selectionEnd = next;
  } catch (e) {}
}

function focusNext(direction = 1) {
  const doc = getActiveDoc();
  if (!doc) return;

  const scope = doc.querySelector("dialog[open]") || doc;
  const focusables = Array.from(scope.querySelectorAll("input, textarea, button, select, a[href], [tabindex]"))
    .filter(el => !el.disabled && el.tabIndex !== -1 && el.getClientRects().length);

  if (!focusables.length) return;

  let idx = Math.max(0, focusables.indexOf(vkActiveEl));
  idx = (idx + direction + focusables.length) % focusables.length;

  try { focusables[idx].focus(); } catch (e) {}
  if (isTextTarget(focusables[idx])) {
    vkActiveEl = focusables[idx];
    vkActiveFrame = document.querySelector("iframe.tab-content.active");
  }
}

function updateKeyLabels() {
  const letterUpper = vkCaps ^ vkShift;

  vk.querySelectorAll(".vk-key[data-letter]").forEach((btn) => {
    const base = btn.getAttribute("data-letter") || "";
    btn.textContent = letterUpper ? base.toUpperCase() : base.toLowerCase();
  });

  vk.querySelectorAll(".vk-key[data-normal]").forEach((btn) => {
    const normal = btn.getAttribute("data-normal") || "";
    const shifted = btn.getAttribute("data-shift") || normal;
    btn.textContent = vkShift ? shifted : normal;
  });

  // toggle styling
  vk.querySelectorAll(".vk-key[data-action='shift']").forEach(b => b.classList.toggle("toggled", vkShift));
  vk.querySelectorAll(".vk-key[data-action='caps']").forEach(b => b.classList.toggle("toggled", vkCaps));
}

function showKeyboard() {
  vkVisible = true;
  vk.classList.remove("hidden");
  vk.setAttribute("aria-hidden", "false");
  vkToggleBtn.setAttribute("aria-pressed", "true");
  positionKeyboard(offsetX, offsetY);
  updateKeyLabels();
}

function hideKeyboard() {
  vkVisible = false;
  vk.classList.add("hidden");
  vk.setAttribute("aria-hidden", "true");
  vkToggleBtn.setAttribute("aria-pressed", "false");
  vkShift = false;
  updateKeyLabels();
}

function toggleKeyboard() {
  if (vkVisible) hideKeyboard();
  else showKeyboard();
}

if (vkToggleBtn) {
  vkToggleBtn.addEventListener("click", toggleKeyboard);
}
if (vkCloseBtn) vkCloseBtn.addEventListener("click", hideKeyboard);

if (vkMinBtn) {
  vkMinBtn.addEventListener("click", () => {
    vkMinimized = !vkMinimized;
    vk.classList.toggle("vk-minimized", vkMinimized);
    vkMinBtn.setAttribute("aria-expanded", String(!vkMinimized));
    positionKeyboard(offsetX, offsetY);
  });
}

// Key click handler
vk.addEventListener("click", (ev) => {
  const btn = ev.target.closest(".vk-key");
  if (!btn) return;

  const action = btn.getAttribute("data-action");
  if (action) {
    switch (action) {
      case "backspace": backspace(); break;
      case "del": delForward(); break;
      case "enter":
        // For textarea/contenteditable, insert newline; for input, trigger enter key events only
        if (isTextTarget(vkActiveEl) && vkActiveEl.tagName === "TEXTAREA") insertText("\n");
        else {
          // Try to dispatch Enter key events to the focused element
          try {
            const doc = getActiveDoc();
            const el = isTextTarget(vkActiveEl) ? vkActiveEl : focusFallbackTarget();
            if (doc && el) {
              el.dispatchEvent(new KeyboardEvent("keydown", { key: "Enter", code: "Enter", bubbles: true }));
              el.dispatchEvent(new KeyboardEvent("keyup", { key: "Enter", code: "Enter", bubbles: true }));
            }
          } catch (e) {}
        }
        break;
      case "tab": focusNext(1); break;
      case "esc": hideKeyboard(); break;
      case "left": moveCaret(-1); break;
      case "right": moveCaret(1); break;
      case "space": insertText(" "); break;
      case "shift":
        vkShift = !vkShift;
        updateKeyLabels();
        break;
      case "caps":
        vkCaps = !vkCaps;
        updateKeyLabels();
        break;
    }
    return;
  }

  // Printable keys
  let text = "";
  if (btn.hasAttribute("data-letter")) {
    const base = btn.getAttribute("data-letter") || "";
    const upper = vkCaps ^ vkShift;
    text = upper ? base.toUpperCase() : base.toLowerCase();
  } else if (btn.hasAttribute("data-normal")) {
    const normal = btn.getAttribute("data-normal") || "";
    const shifted = btn.getAttribute("data-shift") || normal;
    text = vkShift ? shifted : normal;
    // HTML entities in attributes need decoding for insertion
    text = text
      .replaceAll("&amp;", "&")
      .replaceAll("&lt;", "<")
      .replaceAll("&gt;", ">")
      .replaceAll("&quot;", '"');
  }

  if (text) insertText(text);

  // Shift is momentary (unless Caps is on)
  if (vkShift && !btn.hasAttribute("data-action")) {
    vkShift = false;
    updateKeyLabels();
  }
});

// The keyboard docks below the tool on phones. On larger screens, keep the
// entire floating keyboard reachable while dragging or resizing the window.
let dragOrigin = null;
let offsetX = 0;
let offsetY = 0;

function positionKeyboard(x, y) {
  if (!vkVisible || getComputedStyle(vk).position !== "fixed") return;
  const rect = vk.getBoundingClientRect();
  const left = rect.left - offsetX;
  const top = rect.top - offsetY;
  offsetX = Math.min(Math.max(x, -left), innerWidth - left - rect.width);
  offsetY = Math.min(Math.max(y, -top), innerHeight - top - rect.height);
  vk.style.setProperty("--vk-x", offsetX + "px");
  vk.style.setProperty("--vk-y", offsetY + "px");
}

vkHeader.addEventListener("pointerdown", event => {
  if (event.target.closest(".vk-actions") || getComputedStyle(vk).position !== "fixed") return;
  dragOrigin = { x: event.clientX - offsetX, y: event.clientY - offsetY };
  vkHeader.setPointerCapture(event.pointerId);
});

vkHeader.addEventListener("pointermove", event => {
  if (dragOrigin) positionKeyboard(event.clientX - dragOrigin.x, event.clientY - dragOrigin.y);
});

vkHeader.addEventListener("lostpointercapture", () => { dragOrigin = null; });
window.addEventListener("resize", () => positionKeyboard(offsetX, offsetY));
