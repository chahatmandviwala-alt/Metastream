if (window !== window.parent) document.documentElement.classList.add("embedded");

// Keep controls at their designed size whenever they fit. On a short screen,
// measuring after each zoom change lets text reflow across the full panel width.
for (const area of document.querySelectorAll("[data-fit]")) {
  const panel = area.firstElementChild;
  let scheduled = false;
  const fit = () => {
    scheduled = false;
    const height = area.clientHeight;
    if (!height) return;
    panel.style.zoom = 1;
    // clientHeight is rounded to CSS pixels. Use the same precision for panels
    // that grow to fill a fractional grid track.
    const naturalHeight = Math.round(panel.getBoundingClientRect().height);
    if (naturalHeight <= height) return;

    let fits = 0;
    let overflows = 1;
    let scale = height / naturalHeight;
    for (let attempt = 0; attempt < 6; attempt++) {
      panel.style.zoom = scale;
      if (Math.round(panel.getBoundingClientRect().height) <= height) fits = scale;
      else overflows = scale;
      scale = (fits + overflows) / 2;
    }
    panel.style.zoom = fits;
  };
  const observer = new ResizeObserver(() => {
    if (!scheduled) requestAnimationFrame(fit);
    scheduled = true;
  });
  observer.observe(area);
  observer.observe(panel);
}

document.addEventListener("click", event => {
  const button = event.target.closest("button[data-dialog]");
  if (button) document.getElementById(button.dataset.dialog).showModal();
});
