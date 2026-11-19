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

const isDisplayMath = (node) => node.type === 'element' && node.tagName === 'pre' && node.children?.length === 1
  && node.children[0].tagName === 'code' && (node.children[0].properties?.className || []).includes('math-display');

/**
 * rehype plugin, run before rehype-katex: each display formula's `<pre>` is put in a `<div>` holding its source
 * position. rehype-katex replaces the `<pre>` with nodes that have none, so without the div the drawn formula would
 * carry no data-line (the preview's scroll sync anchors blocks by it). The reader draws a display formula in a div
 * too.
 */
export function rehypeDisplayMathLines() {
  return (tree) => {
    const walk = (node) => {
      const children = node.children || [];
      for (let i = 0; i < children.length; i++) {
        const child = children[i];
        if (isDisplayMath(child)) {
          children[i] = { type: 'element', tagName: 'div', properties: {}, position: child.position, children: [child] };
        } else {
          walk(child);
        }
      }
    };
    walk(tree);
    return tree;
  };
}
