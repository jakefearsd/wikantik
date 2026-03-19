
Esta página tiene un repaso general de la sintaxis de consulta usada por el motor de búsquedas.Esta página se llama [Search Page Help](SearchPageHelp).

Usa '+' para exigir una palabra, '-' para prohibirla. Por ejemplo:

``
          +java -emacs jsp
``

encontrará todas las páginas que incluyan la palabra "java", y PUEDAN NO incluirla palabra "emacs". [Adem�]()�s, las páginas que contengan la palabra "jsp" serán mostradasantes que las páginas que no la tengan.

Las búsquedas no son sensibles a mayúsculas/minúsculas. Si una página incluye palabras requeridasy prohibidas, no es mostrada.

### Tabla con la sintaxis de consulta

Para más información echa un ojo a la [sintaxis de consulta de Lucene](http://lucene.apache.org/core/4_4_0/queryparser/org/apache/lucene/queryparser/classic/package-summary.html#package_description) {.slimbox}.

| término | encontrar un término es fácil | `hola`
| "..." | encuentra un grupo de palabras | `"hola fondo norte"`
| ? | comodín para un solo carácter (la ? no puede ser el primer carácter de la búsqueda) | `prue?a`
| * | comodín para varios caracteres (el * no puede ser el primer carácter de la búsqueda) | `prueba*`
| OR | marca el documento si cualquiera de los dos términos existe | `"hola fondo norte" "hola fondo sur"`   
`"hola fondo norte" OR "hola fondo sur"`
| AND | marca el documento si los dos términos existen | `"hola fondo" AND norte`
| + | requiere que el término a continuación del símbolo "+" exista | `+hola fondo norte`
| -   
NOT | excluye los documentos que contengan el término a continuación del símbolo "-"   
la exclusión está soportada tanto por NOT como por ! | `"hola fondo" -"norte y tal"`   
`"hola fondo" NOT "norte y tal"`
| (...) | usa los paréntesis para formar subconsultas | `(hola OR fondo) AND norte`
| ~~ | búsquedas _borrosas_ para encontrar términos cuya grafía sea similar | `pera~ `
| ~n | búsquedas de _proximidad_, con una distancia expresada en número de palabras | `"hola sur"~2`
| ^n | factor de _importancia_ para incrementar la importancia en una búsqueda | `"hola fondo norte"^4 sur`
| \ | para escapar caracteres especiales: ** + - && ~|~| ! ( ) { } [[ ] ^ " ~ * ? : \ ** | `\(1\+1\)\:2` para encontrar (1+1):2

Puedes restringir el dominio de la búsqueda añadiendo un prefijo a la consulta:

| author:_term_ | encuentra las páginas modificadas por determinado(s) autor(es) | `author~:PerryMason`
| attachment:_term_ | encuentra las páginas con un determinado nombre de fichero adjunto | `attachment:brushed*`
| name:_term_ | encuentra las páginas con un determinado nombre de página | `name:Main`
| content:_term_ | encuentra las páginas con un determinado contenido | `content:jspwiki`
