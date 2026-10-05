# Wiki Names

Every page has a name, which is also the last part of its address (`/wiki/PageName`). Page names in this wiki are CamelCase words made of letters and digits, such as `OneMinuteWiki`. The **New Article** dialog builds one from the title you type and lets you edit it before the page is created. It cannot be changed there once the page exists.

## How a link finds its page

Links such as `[[Page Name]]` ignore spaces and case, so `[[one minute wiki]]` finds `OneMinuteWiki`. A link is matched in this order:

1. the exact page name, including plural forms
2. the page name ignoring case
3. a page whose `title` or `aliases` entry matches, ignoring case

If two pages tie, the one that sorts first alphabetically wins. To give a page another name, see [PageAlias](PageAlias).

## Good names

* Keep them short and descriptive, like a chapter title.
* Check for typos before you create the page. Misnamed pages are hard to link to.

See [Linking](https://github.com/jakefearsd/wikantik/blob/main/docs/user/Linking.md) for every link form, including headings and attachments, and [TextFormattingRules](TextFormattingRules) for the syntax.
