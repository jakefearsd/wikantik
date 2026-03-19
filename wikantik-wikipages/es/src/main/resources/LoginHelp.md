
Esto es una breve ayuda sobre cómo identifcarse en **[{$applicationname}]**. El texto se encuentra [aquí](LoginHelp).   

Puedes leer más sobre de las características de seguridad de Wikantik en las [páginas de la documentación](Doc:Security).

### [Identificaci�]()�n

Wikantik soporta múltiples niveles de **autenticación** y confianza. Los usuarios pueden ser anónimos, tener identidades "declaradas" usando cookies, estar autenticados, o ser administradores:

| Estado | [Descripci�]()�n | El saludo al usuario muestra..
| [An�]()�nimo | Usuario sin identificar, y no ha suministrado una cookie | "Buenas, (anónimo)"
| Declarado | User's browser contains a cookie called `~WikantikAssertedName` | "Buenas, _usuario_(no has iniciado sesión)"
| Autenticado | User logged in with a login id and password | "Buenas, _usuario_ (autenticado)"

Dependiendo de la política de seguridad por defecto y de las ACLs, a los usuarios tal vez (o tal vez no) se les requiera autenticarse.

Cuando un usuario decide identificarse - o se le pide que lo haga mediante ACL o política de seguridad - el o ella verá un formulario web estándar con un campo de identificador de usuario y un campo enmascarado de contraseña. [Despu�]()�s de haber recibido la petición de la página de identificación, Wikantik intenta identificar al usuario.

### Pérdida de contraseña

Si has perdido tu contraseña, puedes pedir que se te envíe una nueva contraseña aleatoria a la dirección de correo electrónico registrada en tu Perfil de Usuario.

### Registro de nuevos usuarios

Aunque algunas wikis son anónimas, unas cuantas no. A menudo, las wikis dan a los usuarios la habilidad de crear una identidad para el sitio web.Wikantik incluye un mecanismo básico de auto-registro que permite a los usuarios establecer y configurar su propios perfiles en la wiki.

Por defecto, el formulario pregunta por:

* Un ID de usuario
* Una contraseña
* El "wiki nombre" de usuario preferido (p.ej., [JanneJalkanen]()). Puedes usar este nombre en las ACLs o Wiki Grupos
* [Direcci�]()�n de correo electrónico. Puede ser usada cuando se necesite reestablecer la contraseña. (cfr. Pérdida de contraseña)

Si se está usando autenticación gestionado por el contenedor, el ID de usuario no será editable; el contenedor de aplicaciones suministrará este valor.

Cuando el usuario salva el perfil, Wikantik verifica que los datos introducidos (exceptuando la contraseña) no están siendo usados por otra persona. De serlo, se le da otra oportunidad al usuario de elegir valores distintos.

Una vez el usuario crea su perfil, el o ella puede editarlo después vía el enlace _Mis Preferencias_. Por defecto, los usuarios deben estar autenticados para editar sus propios perfiles...
