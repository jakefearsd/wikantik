
This page gives a quick overview of the search engine query syntax.This page is called [Search Page Help](SearchPageHelp).

Use '+' to require a word, '-' to forbid a word. For example:

``
          +java -emacs jsp
``

finds pages that MUST include the word "java", and MAY NOT includethe word "emacs". Also, pages that contain the word "jsp" areranked before the pages that don't.

All searches are case insensitive. If a page contains bothforbidden and required keywords, it is not shown.

### Query syntax table

For more info look at [Lucene's query syntax](http://lucene.apache.org/core/4_4_0/queryparser/org/apache/lucene/queryparser/classic/package-summary.html#package_description) {.slimbox}.

| term | find a single Term is easy | `hello`
| "..." | find a group of words | `"hello dolly"`
| ? | any single character (the ? can not be the 1st character of a search) | `te?t`
| * | any multiple character (the * can not be the 1st character of a search) | `test*`
| OR | match document if either of the terms exist | `"hello dolly" hello`   
`"hello dolly" OR hello`
| AND | match documents where both terms exists | `"hello dolly" AND "dolly lucy"`
| + | requires that the term after the "+" symbol exist | `+hello dolly`
| -   
NOT | exclude documents that contain the term after the "-" symbol   
exclude also supported with NOT or ! | `"hello dolly" -"dolly lucy"`   
`"hello dolly" NOT "dolly lucy"`
| (...) | use parentheses to form sub queries | `(hello OR dolly) AND website`
| ~~ | _fuzzy_ searchs to match terms similar in spelling | `roam~ `
| ~n | _proximity_ search, within a distance expressed in number of words | `"hello dolly"~10`
| ^n | _boost_ factor to increase importance in a search | `"hello dolly"^4 "dolly lucy"`
| \ | escape special characters: ** + - && ~|~| ! ( ) { } [[ ] ^ " ~ * ? : \ ** | `\(1\+1\)\:2` to find (1+1):2

You can restrict the search domain by prefixing your query:

| author:_term_ | find pages modified by certain author(s) | `author~:JohnFoo`
| attachment:_term_ | find pages with certain attachment name | `attachment:brushed*`
| name:_term_ | find pages with certain page name | `name:Main`
| content:_term_ | find pages with certain content | `content:jspwiki`
