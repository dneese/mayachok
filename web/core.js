/* Shared browser/Node helpers. */
(function (root, factory) {
  const api = factory();
  if (typeof module === 'object' && module.exports) module.exports = api;
  else root.MayaCore = api;
})(typeof globalThis !== 'undefined' ? globalThis : this, function () {
  function cleanCode(raw) {
    return String(raw || '').trim().toLowerCase().replace(/[^a-z0-9]/g, '');
  }

  function codeFromInput(raw) {
    let text = String(raw || '').trim();
    if (!text) return null;
    const slash = text.lastIndexOf('/');
    if (slash >= 0 && slash < text.length - 1) text = text.slice(slash + 1);
    const hash = text.lastIndexOf('#');
    if (hash >= 0 && hash < text.length - 1) text = text.slice(hash + 1);
    const clean = cleanCode(text);
    return clean.length >= 8 && clean.length <= 64 ? clean : null;
  }

  function zoomPoints(users, tracks) {
    const points = [];
    for (const user of users || []) {
      if (user && user.lat != null && user.lon != null) points.push([user.lat, user.lon]);
    }
    for (const trail of Object.values(tracks || {})) {
      for (const point of trail || []) {
        if (point && point.lat != null && point.lon != null) points.push([point.lat, point.lon]);
      }
    }
    return points;
  }

  function staleCount(users, now, staleAfterMs) {
    let count = 0;
    for (const user of users || []) {
      if (user && user.lat != null && user.lon != null && now - user.ts >= staleAfterMs) count++;
    }
    return count;
  }

  return { cleanCode, codeFromInput, zoomPoints, staleCount };
});
