document.addEventListener('DOMContentLoaded', function() {
  const wrappers = document.querySelectorAll('.pgv-description-wrapper');

  // must match CSS
  const LINE_HEIGHT_EM = 1.66;
  const MAX_LINES_COLLAPSED = 2;

  wrappers.forEach((wrapper) => {
    const container = wrapper.querySelector(".pgv-collapsible-container");
    const content = container.querySelector(".pgv-description-content");
    const toggle = container.querySelector(".pgv-collapsible-toggle");

    if (!content || !toggle) {
      return;
    }

    // Temporarily set max-height to 'none' to get the full scrollHeight without current constraints
    const originalMaxHeight = content.style.maxHeight;
    content.style.maxHeight = "none";
    const fullContentHeight = content.scrollHeight;
    content.style.maxHeight = originalMaxHeight;

    const fontSize = parseFloat(window.getComputedStyle(content).fontSize);
    const lineHeightPx = fontSize * LINE_HEIGHT_EM * MAX_LINES_COLLAPSED;

    // AI suggests adding a small tolerance (e.g., 5 pixels) for browser rendering differences
    if (fullContentHeight <= lineHeightPx + 5) {
      // Content does NOT overflow, hide the button and ensure full visibility
      toggle.style.display = "none";
      content.style.maxHeight = "none";
      content.classList.add("is-expanded");
      return;
    }
    // Initialise the content as collapsed
    container.classList.remove("is-expanded");
    content.style.maxHeight = `${lineHeightPx}px`;
    toggle.style.display = "block";

    toggle.addEventListener("click", function () {
      content.style.maxHeight = container.classList.toggle("is-expanded")
        ? `${fullContentHeight}px`
        : `${lineHeightPx}px`;
    });
  });
});
