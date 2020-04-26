= Creación de esquema y objetos de soporte

https://apacheignite-sql.readme.io/docs/schema-and-indexes

= Creación de cluster con persistencia y un nodo

En el archivo de configuración, incluir las líneas referenciadas en https://apacheignite.readme.io/docs/distributed-persistent-store

Ejecutar "control --activate"
Ejecutar "control --baseline version 1"

=Añadir un nodo al baseline

Si no se añade explícitamente el nodo, no se replican las cachés

control --baseline
control --baseline add <persistent-id>

= Sqlline

sqlline -u jdbc:ignite:thin://127.0.0.1/ or sqlline -u jdbc:ignite:thin://127.0.0.1:10800/ (ports ascending)
> !set maxWidth 400
> !tables
> !exit

= Resetear todo

Borrar el directorio "work"
Si se ejecuta desde aplicación windows standalone, del /S /Q C:\Users\frodriguezg\AppData\Local\Temp\ignite\work\*.*