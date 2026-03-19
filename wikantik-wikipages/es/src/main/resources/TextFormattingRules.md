
Esta página describe la sintaxis usada por Wikantik. Para ver los detalles de cómo difiera de la sintaxis usada por la Wikipedia, por favor verifica [MigrandoDesdeMediaWiki](Wikantik:MigratingFromMediaWiki).

[{TableOfContents }]()   
Cuando ya te hayas hecho a cómo funciona el editor, entonces deberías leer [WikiEtiqueta](WikiEtiquette) de tal modo que sepas como emplear tus habilidades recién adquiridas. El [Área de Pruebas](http://sandbox.jspwiki.org) es un sitio fenomenal para probarlas.

#### Referencia rápida

``
----       = crea una regla horizontal. Los '-' extra son ignorados.
\\         = fuerza un salto de línea

[enlace]     = crea un hiperenlace a una página de la Wiki llamada 'Enlace'.
[esto es otro enlace] = crea un hiperenlace a una página de la Wiki llamada
             'EstoEsOtroEnlace' pero muestra el enlace conservando los espacios en blanco.
[un ejemplo|enlace] = crea un hiperenlace a una página de la Wiki llamada
             'Enlace', pero muestra al usuario el texto 'un ejemplo'
             en vez de 'Enlace'.
~NoLink    = desactiva la creación del enlace para la palabra en CamelCase.
[1]        = crea un referencia a una nota al pie numerada con 1.
[#1]       = crea la nota al pie numerada con 1.
[[enlace]     = crea el textp '[enlace]'.

!encabezado   = encabezado pequeño con el texto 'encabezado'
!!encabezado  = encabezado medio con el texto 'encabezado'
!!!heading = encabezado grande con el texto 'encabezado'

''texto''   = muestra 'texto' en cursiva.
__texto__   = muestra 'texto' en negrita.
{{texto}}   = muestra 'texto' en monoespaciado.
[texto|]    = muestra 'texto' subrayado (enlace ''dummy'')
* texto     = crea un ítem de una lista sin numerar con el texto 'texto'
# texto     = crea un ítem de una lista numerada con el texto 'texto'
;term:ex   = crea una definición para 'term' con la explicación 'ex'
``

#### Escribiendo texto
No necesitas saber nada acerca de las reglas de formato usadas por la Wiki para usarla. Simplemente escribe texto nomral. y usa una línea en blanco para marcar un párrafo.Es como escribir un correo electrónico.Siempre puedes editar esta página (mira en el menú lateral de la izquierda) para ver cómo aplican los distintos efectos usados en esta página.
#### Hiperenlaces
El enlace también puede ser una URL que comience por `http:`, `ftp:`, `mailto:`, `https:`, o `news:`, en cuyo caso el enlace apuntará a una entidad externa. Por ejemplo, para apuntar a la página java.sun.com, utiliza `[[http://java.sun.com]`, que se conviertirá en [http://java.sun.com/]() o `[[Página principal de Java|[http://java.sun.com]()]`, que se convertirá en [Página principal de Java](http://java.sun.com).Si no empiezas el enlace con alguno de los "protocolos" anteriores, la wiki asume un enlace normal a otra página de la wiki; es obligatorio empezar los nombres de una página con una letra, los nombres de página basados únicamente en números no están permitidos. Si quieres usar corchetes (`[[]`) en la página sin crear un hipernenlace, usa dos corchetes de apertura. De tal modo que, el texto `[[[Ejemplo No-Enlace]`, aparecerá como `[[Ejemplo No-Enlace]`.Para añadir una nueva página simplemente crea un enlace a ella desde cualquier sitio. [Despu�]()�s de todo, ¡no tiene mucho sentido tener una página si no puedes acceder a ella! [Ver�]()�s un enlace subrayado en rojo con línea discontinua con el nombre de la página. Haz click entonces en dicho enlace y ¡habrás creado una nueva página![Est�]()� permitido el uso de casi todo tipo de caracteres dentro de un [nombre de una página de la Wiki](Wikantik:WikiName), siempre que se usen letras o números.Date cuenta también de que esta Wiki puede configurarse para soportar enlazado [CamelCase](Wikantik:CamelCase) (si está soportado, la palabra [CamelCase]() debería ser un enlace, si no empieza con '~~'). Por defecto está desactivado, pero si tu amistoso administrador ha activado esta característica, entonces, bueno, [CamelCase]() toda la que quieras =).
#### Notas al pie
Son un tipo especial de hipernelace. Usando simplemente un número dentro de un enlace se crea un referencia a la nota al pie, como por ejemplo así `[[1]`, que crea la nota al pie[1](). Para crear la nota al pie, simplemente pon un `[[#1]` dónde quieres que apunte la nota. Mira más abajo para encontrar la nota al pie.[Tambi�]()�n es posible nombrar los pies de página, exactamente del mismo modo en que se hace con un enlace normal, por ejemplo `[[Nota al pie nombrada|1]` es otra manera de referirse a la primera nota al pie [Nota al pie nombrada](1). O puedes poner también el nombre personalizado en la propia nota al pie[2]().
#### Enlaces [InterWiki](Wikantik:InterWiki)
[Tambi�]()�n es posible enlazar wikis distintos sin necesidad de saber la URL. Simplemente utiliza el enlace del siguiente modo: `[[Wiki:[WikiPagina]()]` y Wikantik creará un enlace por ti. Por ejemplo, este enlace apunta a las [reglas de formato de texto de Wikantik](Wikantik:TextFormattingRules). Mira la [Información del Sistema](SystemInfo) para ver cuáles enlaces [InterWiki]() festán disponibles.Si un enlace [InterWiki](Wikantik:InterWiki) no está soportado, recibirás una notificación al guardar la página.
#### Añadiendo imágenes
Puedes embeber cualquier imagen en el código wiki haciendo disponible la imagen en cualquier sitio web en cualquiera de los formatos permitidos, y simplemente enlazando a ella.Si especificas un texto del enlace (`[[este texto aquí|[http://example.com/example.png]()]`) formará parte del texto ALT para aquellos que no puedan o no quieran ver las imágenes.La lista de los formatos permitidos depende de cada Wiki. Consulta la [Información del sistema](SystemInfo)para ver todos los tipos de formatos admitidos.[Tambi�]()�n se puede usar el [plugin Image](Wikantik:Image) para obtener más control sobre la posición y los atributos de la imagen.Para forzar una limpia de la posición (un _flush_) después de la imagen, utiliza \ \ \ (esto es, tres contrabarras consecutivas en vez de dos).
#### Listas sin numerar
Utiliza un asterisco (*) en la primera columna para generar listas sin numerar. Usa más asteriscos para conseguir mayores niveles de anidamiento. Por ejemplo:
``
* Uno \\ uno y medio
* Dos
* Tres
** Tres.Uno``
crea
* Uno   
uno y medio
* Dos
* Tres
    * Tres.Uno

#### Listas numeradas
Se generan de igual modo que las listas sin numerar, pero usando una almohadilla (#) en vez de un asterisco. Como en el siguiente ejemplo:
``
# Uno \\ uno y medio
# Dos
# Tres
## Tres.Uno
``
crea
1. Uno   
uno y medio
1. Dos
1. Tres
    1. Tres.Uno

Si quieres escribir el elemento de la lista varias líneas, simplemente añade uno o más espacios en blanco en la siguiente línea y ésta será añadida automáticamente al elemento anterior. Por ejemplo:
``
* Esto es un elemento de una sola línea
* Esto es un elemento de que continua a través de varias líneas.
  Continuamos la segunda sentencia en una nueva línea.
  Podríamos también añadir una tercera línea, ya que estamos...
  Fíjate que, sin embargo, ¡todas estas frases caen dentro de un mismo elemento!
* Esta tercera línea también es un elemento de una sola línea
``
genera:
* Esto es un elemento de una sola línea
* Esto es un elemento de que continua a través de varias líneas. Continuamos la segunda sentencia en una nueva línea. [Podr�]()�amos también añadir una tercera línea, ya que estamos... Fíjate que, sin embargo, ¡todas estas frases caen dentro de un mismo elemento!
* Esta tercera línea también es un elemento de una sola línea

#### Listas de definiciones y comments
Una manera sencilla de hacer listas de definiciones es usar la estructura ';:':
``
;__Autoreferente__:''cfr. con Autoreferente''
``
se renderiza como:
**Autoreferente**: _cfr. con Autoreferente_
Otro uso simpático de ';:' es que puede ser usado para comentar brévemente los textos de otras personas, añadiendo un 'término' vacío en la definición, por ejemplo, así:
``
;:''Comentario aquí.''
``
Que se vería cómo
: _Comentario aquí._

#### Efectos del texto
Puedes usar texto en **negrita** o en _cursiva_, utilizando para ello dos guiones bajos (_) y dos comillas simples ('), respectivamente. Si estás utilizando Windows, asegúrate de utilizar el símbolo correcto de la comilla, hay otro que es muy similar y es fácil condudirlos.Se puede conseguir el efecto de <!---->subrayado{style:'text-decoration:underline;'} utilizando un enlace que apunte a ninguna parte, [[como éste|]
#### Texto preformateado
Si quieres añadir texto preformateado (como por ejemplo código) utiliza tres llaves consecutivas ({) para abrir un bloque, y tres llaves consecutivas (}) para cerrarlo. Edita esta página para ver un ejemplo.
#### Tablas
Puedes obtener tablas simples usando tuberías ('|'). Usa una doble tuberíapara empezar el encabezado de una tabla, y tuberías simples para comenzar las filasde la tabla. Termina con una línea que no sea una tabla.Por ejemplo:
``
|| Encabezado 1 || Encabezado 2
| ''Gobble'' | Bar \\ foo
| [Principal|Main] | [Pruebas|SandBox]
``
genera la siguiente tabla. Fíjate que también puedes usar enlaces dentro de las tablas.
| Encabezado 1 | Encabezado 2
| _Gobble_ | Bar   
foo
| [Principal](Main) | [Pruebas](SandBox)

#### Estilos CSS
Aunque no está alineado con el principio de Mantener Las Cosas Simples, se pueden embeber [estilos CSS](Wikantik:CSSInWikipages) para aquellos casos en los que realmente necesites enfatizar parte de una página.
#### Conflictos
Si quien sea edita la misma página que tú al mismo tiempo, Wikantik te prevendrá de hacer cambios, mostrándote una página de conflicto. Es triste decirlo, pero el primero en hacer el cambio gana...**Aviso:** Si utilizas el botón [Atr�]()�s del navegador y llegas a la página de [Edici�]()�n, es casi seguro que se cree un conflicto. Esto es porque el navegador aun piensa que está en una copia antigua de la página.
#### Borrar páginas
No es posible. Puedes, sin embargo, borrar todos los enlaces a esa página, haciéndola inaccesible. O puedes mandar un correo electrónico al administrador, y éste borrará la página. [Tambi�]()�n, puedes añadir un enlace You [DELETEME](Wikantik:DELETEME).
#### Añadiendo nuevas páginas
Crea un enlace que apunta a una página nueva (=inexistente) usando su [WikiNombre](Wikantik:WikiName).Haz click en este nuevo enlace, que debería estar subrayado en rojo y aparecerá el editor para la nueva página. -- [Asser](Wikantik:Asser)
#### Estableciendo un alias para la página
A veces interesa que un enlace a una página del wiki en realidad apunte a un sitio distinto al indicado. Esto puede hacerse utilizando un [Alias](PageAlias).
#### Insertando variables
Hay muchas variables distintas que puedes insertar en una página. La forma básica es:` [[{$nombrevariable}], `donde _nombrevariable_ es el nombre de la variable que quieres insertar. Los nombres de las variables no son sensibles a mayúsculas, esto es,"pagename" es lo mismo que "paGeNamE" y que "[PageName]()".Puedes ver la lista completa de variables en [WikiVariables](Wikantik:WikiVariables).
#### Insertando plugins
La instanciación básica de un plugin tiene esta pinta:[[{INSERT <plugin class> WHERE param1=value, param2=value, ...}]Hay más información disponible en [WikantikPlugins](Wikantik:WikantikPlugins).
* * *
[#1] [Aqu�]()� está la nota al pie que mencioné.[2-La otra nota al pie] La otra nota al pie. ¿Has visto cómo el nombre es diferente?
* * *
¿Alguna [idea](Wikantik:IdeasTextFormattingRules)?¿Alguna [pregunta](Wikantik:TextFormattingRulesDiscussion)?
