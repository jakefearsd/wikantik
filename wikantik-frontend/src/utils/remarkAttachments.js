import { visit } from 'unist-util-visit';

const SCHEME = /^[a-z][a-z0-9+.-]*:/i;

/** A URL with a scheme (`https:`, `mailto:`, `ftp:`, …) or a root-relative path: never an attachment name. */
function isAbsoluteUrl(url) {
  return SCHEME.test(url) || url.startsWith('/');
}

export function remarkAttachments({ attachments = [], pageName }) {
  const attachUrl = (fileName) => `/attach/${pageName}/${fileName}`;

  return (tree) => {
    visit(tree, ['image', 'link'], (node) => {
      const url = node.url;
      if (!url || isAbsoluteUrl(url)) return;

      // Check if this relative URL matches a known attachment (case-insensitive)
      const matchedAtt = attachments.find(a => a.fileName.toLowerCase() === url.toLowerCase());
      if (matchedAtt) {
        node.url = attachUrl(matchedAtt.fileName);
      } else if (url.includes('.')) {
        // Looks like a filename but not in attachment list — mark as missing
        if (!node.data) node.data = {};
        if (!node.data.hProperties) node.data.hProperties = {};
        node.data.hProperties['data-attachment-missing'] = 'true';
      }
    });
  };
}
