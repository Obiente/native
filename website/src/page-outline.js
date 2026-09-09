export function pageSections(headings = []) {
  const seen = new Set();
  return headings.filter((heading) => {
    if (heading.level !== undefined && heading.level !== 2) return false;
    if (!heading.anchor || !heading.title || seen.has(heading.anchor)) return false;
    seen.add(heading.anchor);
    return true;
  });
}
