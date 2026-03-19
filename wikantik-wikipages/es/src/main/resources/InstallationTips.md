
[{TableOfContents }]()

### Problemas con Microsoft Windows

#### [Configuraci�]()�n de la Wiki
El enfoque menos doloroso para definir la localización de las páginas de la Wiki es tenerlas en la misma unidad que tu instalación de Tomcat (u otro servidor de aplicaciones), y definir su localización con formato Unix. Por ejemplo, los archivos en C:\Wikantik\files son definidos en el fichero de configuración como /Wikantik/files.
[Tambi�]()�n, comprueba que cambias el valor del parámetro para `appender.rolling.fileName` en `wikantik.properties` si quieres que se cree un fichero de log. Este parámetro está en la sección de configuración Log4j 2.x cerca del final del fichero de configuración. El formato es el mismo que en el parámetro `jspwiki.fileSystemProvider.pageDir`, es decir, las contrabarras deben ser duplicadas.

### Problemas con UTF-8

Si estás teniendo problemas con Tomcat 5.x (o superior) y Wikantik con UTF-8, por favor, ¡presta atención!

Tienes que editar el fichero server.xml de Tomcat (localizado en el directorio conf del Tomcat). Por favor añade a

``<Connector port="8080"
               maxThreads="150" minSpareThreads="25" maxSpareThreads="75"
               enableLookups="false" redirectPort="8443" acceptCount="100"
               debug="0" connectionTimeout="20000"
               disableUploadTimeout="true"/>``

el término '``URIEncoding="UTF-8"``'.

[Deber�]()�a quedar algo parecido a esto:

``
<Connector port="8080"
               maxThreads="150" minSpareThreads="25" maxSpareThreads="75"
               enableLookups="false" redirectPort="8443" acceptCount="100"
               debug="0" connectionTimeout="20000"
               disableUploadTimeout="true"
               URIEncoding="UTF-8"/>
``

Recuerda que si estás usando mod_jk, también deberías definir el `URIEncoding` para el Conector JK:

``
.........
  <service name="Catalina">
    <connector port="8080" />
.........
    <connector protocol="AJP/1.3" uriencoding="UTF-8" port="8009" />
.........
  </service>
``

### Otros consejos

#### Editar el menu situado a la izquierda.

El menú situado a la izquierda (ahí <-----) es solo otra página del Wiki como las demás, llamada [LeftMenu]().

Si tú (el administrador) no quieres que sea editable, siempre puedes editar la página [LeftMenu](), y hacerla de solo lectura poniendo una ACL en ella.

La parte inferior de la sección de la izquierda es otra página más, llamada [LeftMenuFooter](), y también es totalmente editable.

#### Activando el feed [RSS](http://blogspace.com/rss/)

En tu archivo wikantik.properties, tienes que establecer el parámetro "wikantik.rss.generate" a "true".

#### No me gusta que~ LasPalabrasSeAmontonenEnLosTitulos en los títulos de las páginas. ¿Puedo hacer algo al respecto?

Como dicen los finlandeses, "voe tokkiinsa", es decir "sí, por supuesto". Simplemente establece el parámetro "wikantik.breakTitleWithSpaces" a "true" en tu fichero wikantik.properties.
