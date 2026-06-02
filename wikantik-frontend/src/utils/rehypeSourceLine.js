// rehype plugin: stamp each rendered element with `data-line`, the 1-based
// source line (within the markdown body) it was produced from. Enables
// click-to-source in the editor preview — a click walks up to the nearest
// [data-line] and the editor jumps its caret to that line.
//
// Self-contained recursive walk (no unist-util-visit dependency). Source
// positions are carried onto the hast tree by remark-rehype, so node.position
// is available here.
export default function rehypeSourceLine() {
  return (tree) => {
    const walk = (node) => {
      if (node && node.type === 'element') {
        const line = node.position && node.position.start && node.position.start.line;
        if (line != null) {
          node.properties = node.properties || {};
          node.properties['data-line'] = line;
        }
      }
      if (node && node.children) {
        for (const child of node.children) walk(child);
      }
    };
    walk(tree);
    return tree;
  };
}
