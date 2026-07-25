// Applies the stored theme before React mounts, so the page never paints in the
// wrong theme and then flips — which is ugly, and for light-sensitive users
// genuinely unpleasant.
//
// This lives in public/ rather than inline in index.html for two reasons:
//   1. The production CSP is script-src 'self'. An inline script is blocked
//      outright unless we whitelist a sha256 hash of its contents.
//   2. That hash would have to match Vite's *build output*, not this source, so
//      any change in how Vite emits index.html would silently break it and the
//      flash would quietly return.
//
// Files in public/ are copied verbatim and served same-origin, so 'self' covers
// them and there is no hash to drift. Loaded synchronously in <head>, it still
// runs before first paint.
;(function () {
  try {
    var stored = localStorage.getItem('f1-theme')
    var dark =
      stored === 'dark' ||
      ((stored === 'system' || stored === null) &&
        window.matchMedia('(prefers-color-scheme: dark)').matches)
    if (dark) document.documentElement.classList.add('dark')
    document.documentElement.style.colorScheme = dark ? 'dark' : 'light'
  } catch (e) {
    /* no stored preference available; React resolves it on mount */
  }
})()
