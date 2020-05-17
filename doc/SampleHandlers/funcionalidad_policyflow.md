# Autenticación

Radius clients de Fusión marcados con atributo "Tecnology". Salta en el primer plugin a otro archivo.

user.VendorName en función de los atributos radius recibidos. Puede ser #default (Redback), MX, HUAWEI, NOKIA, ...

Si la petición no tiene nasPort, entonces es CLI y devuelve Accept.

## Normalización de atributos

Nas-Port-Id slot/subslot/port:<s-vlan>:<c-vlan>
Si pw o lag, se usa 1/1/1
user.NasPortInd sustituye / por - en el Nas-Port-Id
user.NasPort y user.NasPortIPAM == user.Nas-Port-Id
user.DslamId == lo que van antes del .

En Huawei, el NAS-Port-Id debe de venir codificado con slot=<>;vlan2=<>;vlan=<> etc.

## Petición de definición de sevicio

Usa el mismo archivo que PSA

## Usuarios de pruebas (archivo TestUserProfile)

Son usuarios completos, no solamente dominios.

Se validan contra archivo local, y se lee el archivo de configuración del dominio aparentemente
para obtener el Session-Timeout y Idle-Timeout (tal vez alguna cosa más).
Añade a pelo los atributos de servicio para Huawei, Nokia y Redback

Luego devuelve Accept sin pasar por el USS

## Líneas de pruebas (archivo conf/NASPort)

Líneas identificadas por HostName_NASPortInd.
Añade atributos de respuesta a pelo

## Proxy

Lectura de los atributos por dominio.
En algunos dominios se hace proxy solamente con el base-user-name (stripRealm)
En caso de error borra los atributos de la respuesta

Guarda el número de errores por ISP en una caché y escribe log si se supera, pero no parece resetearse.

## Lectura de base de datos

Puede ir directamente a la caché si está fijada la variable user.Jump_Base.

El Bind se hace con los atributos tomados directamente de la petición NAS-Port, NAS-IP-Address, User-Name.
Devuelve todos los atributos de PSA, incluyendo IP_ADDRESS, pero no CampaignService

Verifica la password.

Lee los parámetros de
* Servicio de bloqueo si es moroso
* Servicio de override + addon si lo tiene (parece que exige los dos)
* Servico básico
	Luego tomaría el campaignService (lo que está detrás de #), pero no parece tomarse la variable. ¿Está a medias?
	
Usuario no encontrado
* Configuración de comportamiento si cliente no encontrado, pero en realidad solo escribe un log diferente
* Escribe una entrada para el cliente en la cache PSAUserNotFound. Esta caché no se le nunca y se comprueba su tamaño periódicamente. Pasado el umbral, se borra.
	
	Si no encuentra al cliente en la cache, la política es configurable y lee un servicio por defecto o envía reject

De algún modo mete el client_id en el atributo Class (no veo cómo)

## Perfil específico para algunos clientes (archivo conf/SubscriberProfile)

El archivo de ejemplo está vacío.

Parece que no se sobre-escriben atributos.

## IPAM (Autenticación)

Genera las variables para acceso a IPAM. ¿Se usan todas para algo en Fusión? e.g. ${user.ReqAttr.AAA-AVPair-2.PoolLocalidade}    := ${client.PoolLocalidade};

### Procesamiento en USS

La funcionalidad de USS está en un servidor separado, con un policy flow distinto para Fusión.

Comprueba el rol del servidor. Si es secundario, intenta enviar la petición al primario y solamente la procesa localmente
si no obtiene respuesta. Si el rol no es de secundario, procesa directamente la petición.

La clave de las sesiones es BRASLocation+NAS-Port-Id, donde hay varios BRAS con el mismo BRASLocation, probablemente por redundancia.

Inicialmente busca por esa clave y si encuentra una sesión previa con la misma MAC, devuelve error (es configurable si se realiza esta
comprobación). Para usuarios PRO, se actualiza la sesión en cambio, pero no se permite más que una sesión por login.

Los atributos almacenados son

```
		${uss.Full-User-Name}     := ${request.User-Name};
		${uss.NAS-Identifier}     := ${request.NAS-Identifier};
		${uss.NAS-IP-Address}     := ${request.NAS-IP-Address};
		${uss.NAS-Port-ID}        := ${request.NAS-Port-ID};
		${uss.NAS-Port}           := ${request.NAS-Port};
		${uss.BRAS-Slot-Port}     := ${packet.BRAS-Slot-Port};
		${uss.Client-Class}       := ${packet.Client-Class};
		${uss.AAA-Nas-IP-Address} := ${packet.Proxy-Source-Address:packet.Source-Address};
		${uss.Framed-IP-Address}  := ${reply.Framed-IP-Address};
		${uss.MAC-Address}        := ${packet.MAC-Address};
		${uss.IPAMCluster}        := ${packet.IPAMCluster};
		${uss.UsingIPAMRedundant} := ${packet.UsingIPAMRedundant};
		${uss.Session-Id}         := ${request.Acct-Multi-Session-Id:request.Session-Id:request.Acct-Session-Id:"AUTH"};
		${uss.PSA-ServiceName}   := ${request.Alc-MSAP-Policy:request.HW-Service-Info:request.PSA-ServiceName};
```

# Contabilidad

Toma los datos para hacer proxy a Plataforma DNS y a CGNAT.

Si se ha configurado el descarte de paquetes de accounting on/off y se trata de uno de estos tipos de paquetes, termina.

Se generan las variables auxiliares de forma similar a la autenticación, añadiendo

```
${user.Accounting-Sessao}
${user.Acct-Interim-Reason}
${user.Acct-Input-Total} (Incluye Gigawords)
```

Genera variables para configurar hacia cuantos sitios se envía proxy

```
${user.CDRWriteInterim}
${user.SendInterim500B}
${user.SendInterimIntercept}
${user.SendInterimCDRView}
${user.SendInterimPlataformaDNS}
```

Carga la configuración por dominio y determina si debe hacer proxy

## Proxy de contabilidad

Parece hacer proxy tanto de contabilidad de sesión como de la de servicio. No filtra.

No hace proxy de Interim.

En algunos dominios, si está configurado, quita el dominio en el User-Name

Mantiene el número de errores de proxy en la caché "failover" por dirección IP destino, mostrando
un mensaje cuando se supera un umbral, pero no parece resetearse el contador.

## Proxy copy-mode a plataformas externas

Para cada una de ellas, es configurable si se envía el paquete en función de

* Parámetro de configuración
* Vendor
* Accounting-Status-Type
* Interim reason
* Contabilidad de Sesión/Servicio
* Contiene prefijo Framed o Delegado

Estas plataformas son

* 500B
* Intercept
* CDRView

## IPAM (Contabilidad)

No envía la contabilidad de servicio

Define el IPAM activo como el Cluster/Redundante en función de la configuración

Envía al IPAM activo, utilizando el mismo balanceo que para autenticación (ver sección RADIUS -> IPAM)

Utiliza la variable "ActiveIPAM" para indicar al USS si es una petición al activo o al redundante.

El IPAM puede enviar un flag de desconexión del usuario, que es ejecutada por el Radius si es el caso.

Luego realiza la misma petición al IPAM reundante, en copy mode. Sin embargo, no lo hace si la dirección
destino coincide con la local del Radius **No entiendo por qué**

### Procesamiento en USS

Tratamiento común: carga variables, comprueba el rol y trata de procesar la petición en el primario si la instancia
actual no lo es.

Genera variables
* user.CDRWriteInterim
* user.PSA-WAN/LAN-IPv6-Prefix
* user.PSA-Session-Start-Time

Si la petición no tiene dirección IP, no hace nada en el USS (IPAM-CheckAttributes y IPAM-CheckAttributesPRO
saltan a IPAM-CheckActiveIPAM)

	#### Usuarios PRO
	En los Stop, solamente actualiza la sesión si llega del mismo NAS-Identifier (la clave es por BRASLocation).

	#### Resto de usuarios
	Si está definido packet.IPAMCluster, que contiene el dato de IPAMCluster en radius_clients, y que en fusión
	parece no ser enviado por el Radius, no hace nada (salta a IPAM-CheckActiveIPAM).

	En otro caso:

	Si es stop, actualiza la sesión en el USS si llega del mismo NAS-Identifier.

	Si es otra cosa, busca la dirección IP en el USS, y si la encuentra y es de otro Key, responde con un paquete
	para desconexión del cliente

Si ActiveIPAM es false, termina. En otro caso

Determina a qué USS debe enviar la copia. Si la configuración es normal, no invertida (primario al Cluster), entonces
la copia va al redundante. En otro caso, la copia va al cluster.

Verifica que no se esté enviando el paquete a él mismo.

## Escritura de CDR

Solo escribe el CDR en el USS si es el activo, no el de redundancia.



## Almacenamiento de CDR

Guarda dos copias del CDR, configurable en función de
* Vendor
* Acct-Status-Type
* Interim-Reason
* Framed/Delegated IPv6 prefix

Algunos tipos solamente escriben el duplicado.



# RADIUS -> IPAM

## Balanceo

Envía a los servidores configurados como IPAM, tunelando algunos atributos propios del policy-flow con AAA-AVPair.

La caché "ipam" tiene claves por los nombres de los servidores Radius, y guarda un flag que indica si está "offline".
En cada petición OK, verifica la caché y si estaba "offline=true" envía un Trap SNMP y cambia el valor de la caché.

La caché "failover" tiene claves por los nombres de los servidores radius, y guarda un contador con número de errores.
Cuando ese contador pasa de un umbral, envía un trap SNMP y cambia el valor de la caché "ipam" a "offline=true". El
contador no parece modificarse en las peticiones correctas (¿Error?).



